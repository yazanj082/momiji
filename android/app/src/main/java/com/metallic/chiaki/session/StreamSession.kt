// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.metallic.chiaki.common.LogManager
import com.metallic.chiaki.common.PsnAccount
import com.metallic.chiaki.lib.*
import com.metallic.chiaki.regist.PsnAuth
import java.io.IOException
import kotlin.concurrent.thread

sealed class StreamState
object StreamStateIdle: StreamState()
object StreamStateConnecting: StreamState()
/** Connecting through PSN, which comes before the stream's own connection */
data class StreamStatePsnConnecting(val step: PsnConnectStep): StreamState()
object StreamStateConnected: StreamState()
data class StreamStateCreateError(val error: CreateError): StreamState()
data class StreamStateQuit(val reason: QuitReason, val reasonString: String?): StreamState()
data class StreamStateLoginPinRequest(val pinIncorrect: Boolean): StreamState()
data class StreamStatePsnError(val error: PsnConnectError, val errorCode: ErrorCode?): StreamState()

enum class PsnConnectStep
{
	SIGNING_IN,
	CREATING_SESSION,
	WAKING_CONSOLE,
	CONNECTING_CONSOLE
}

enum class PsnConnectError
{
	/** PSN wants a new sign-in, or there is none */
	SIGN_IN,
	/** This device can't reach PSN */
	PSN_UNREACHABLE,
	/** The console didn't join, so it may be off or offline, or have Remote Play off */
	CONSOLE_NO_ANSWER,
	/** Both are on PSN, but the networks between them don't let a direct connection through */
	NETWORK_BLOCKED,
	FAILED
}

class StreamSession(val connectInfo: ConnectInfo, val logManager: LogManager, val logVerbose: Boolean, val input: StreamInput)
{
	var session: Session? = null
		private set

	fun stats() = session?.getStats()

	private val _state = MutableLiveData<StreamState>(StreamStateIdle)
	val state: LiveData<StreamState> get() = _state
	private val _rumbleState = MutableLiveData<RumbleEvent>(RumbleEvent(0U, 0U))
	/** Vibration from the console's rumble and DualSense haptics combined */
	val rumbleState: LiveData<RumbleEvent> get() = _rumbleState
	private val _cantDisplay = MutableLiveData(false)
	val cantDisplay: LiveData<Boolean> get() = _cantDisplay

	private val mainHandler = Handler(Looper.getMainLooper())
	private val psnLock = Any()
	/** Counts the connections through PSN, so that one that was superseded stops. Guarded by psnLock. */
	private var psnAttempt = 0
	/** The connection through PSN until a session takes it over. Guarded by psnLock. */
	private var psnConnection: HolepunchConnection? = null
	private var psnConnecting = false
	private val dualSenseFeedback = if(connectInfo.enableDualSense) DualSenseFeedback() else null
	private val controllerLights = if(connectInfo.enableDualSense && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ControllerLights() else null
	private var consoleRumble = RumbleEvent(0U, 0U)
	private var hapticsRumble = HapticsEvent(0, 0)
	private var hapticIntensity = DualSenseIntensity.STRONG
	// The console stops sending haptics without a final silent frame
	private val hapticsTimeout = Runnable {
		hapticsRumble = HapticsEvent(0, 0)
		updateRumble()
	}

	private val audioRouting = AudioRouting(input.context, input.preferences.controllerHeadphones) { session?.let { applyAudioRouting(it) } }

	private var surfaceTexture: SurfaceTexture? = null
	private var surface: Surface? = null

	init
	{
		input.controllerStateChangedCallback = {
			session?.setControllerState(it)
		}
		input.controllerMotionCallback = { gyroX, gyroY, gyroZ, accelX, accelY, accelZ, timestampUs ->
			session?.setMotion(gyroX, gyroY, gyroZ, accelX, accelY, accelZ, timestampUs)
		}
	}

	fun shutdown()
	{
		synchronized(psnLock) {
			psnAttempt++
			psnConnection?.cancel()
			psnConnection = null
		}
		psnConnecting = false
		session?.let {
			it.stop()
			// Ending the session on PSN takes a few seconds
			if(connectInfo.psnConsoleUid != null)
				thread(name = "StreamSession dispose") { it.dispose() }
			else
				it.dispose()
		}
		session = null
		_state.value = StreamStateIdle
		dualSenseFeedback?.reset()
		controllerLights?.close()
		mainHandler.removeCallbacks(hapticsTimeout)
		consoleRumble = RumbleEvent(0U, 0U)
		hapticsRumble = HapticsEvent(0, 0)
		updateRumble()
		//surfaceTexture?.release()
	}

	/**
	 * For when the stream is gone for good.
	 */
	fun release()
	{
		shutdown()
		dualSenseFeedback?.close()
		audioRouting.close()
	}

	private fun applyAudioRouting(session: Session)
	{
		session.setAudioDevice(audioRouting.deviceId)
		session.setHapticsDevice(if(connectInfo.enableDualSense) audioRouting.hapticsDeviceId else 0)
	}

	fun onInputDevicesChanged()
	{
		dualSenseFeedback?.rescan()
		controllerLights?.rescan()
	}

	private fun updateRumble()
	{
		val scale = when(hapticIntensity)
		{
			DualSenseIntensity.OFF -> 0
			DualSenseIntensity.WEAK -> 1
			DualSenseIntensity.MEDIUM -> 2
			DualSenseIntensity.STRONG -> 3
		}
		fun combine(rumble: UByte, haptics: Int) = (maxOf(rumble.toInt(), haptics) * scale / 3).toUByte()
		val value = RumbleEvent(combine(consoleRumble.left, hapticsRumble.left), combine(consoleRumble.right, hapticsRumble.right))
		if(value != _rumbleState.value)
			_rumbleState.value = value
	}

	fun pause()
	{
		shutdown()
		// The views hand over a new surface when they come back.
		// A reconnect keeps the current one, as nothing would hand it over again.
		surface = null
	}

	fun resume()
	{
		if(session != null || psnConnecting)
			return
		val psnConsoleUid = connectInfo.psnConsoleUid
		if(psnConsoleUid != null)
		{
			connectPsn(psnConsoleUid)
			return
		}
		try
		{
			startSession(Session(connectInfo, logManager.createNewFile().file.absolutePath, logVerbose))
		}
		catch(e: CreateError)
		{
			_state.value = StreamStateCreateError(e)
		}
	}

	private fun startSession(session: Session)
	{
		_state.value = StreamStateConnecting
		session.eventCallback = this::eventCallback
		applyAudioRouting(session)
		session.start()
		val surface = surface
		if(surface != null)
			session.setSurface(surface)
		this.session = session
	}

	private fun isCurrentPsnAttempt(attempt: Int) = synchronized(psnLock) { attempt == psnAttempt }

	/** The steps block for seconds each, so they run on their own thread */
	private fun connectPsn(consoleUid: ByteArray)
	{
		val attempt = synchronized(psnLock) { ++psnAttempt }
		psnConnecting = true
		_state.value = StreamStatePsnConnecting(PsnConnectStep.SIGNING_IN)
		val logFile = logManager.createNewFile().file.absolutePath
		val context = input.context.applicationContext
		thread(name = "StreamSession PSN") {
			val error = connectPsnSteps(attempt, consoleUid, PsnAccount(context), logFile) ?: return@thread
			mainHandler.post {
				if(!isCurrentPsnAttempt(attempt))
					return@post
				psnConnecting = false
				_state.value = StreamStatePsnError(error.first, error.second)
			}
		}
	}

	/** @return why connecting failed, or null if it worked or was superseded */
	private fun connectPsnSteps(attempt: Int, consoleUid: ByteArray, account: PsnAccount, logFile: String): Pair<PsnConnectError, ErrorCode?>?
	{
		val token: String
		val accountId: ByteArray
		try
		{
			token = account.accessToken()
			accountId = account.accountId ?: return PsnConnectError.SIGN_IN to null
		}
		catch(e: PsnAuth.SignInRejectedException)
		{
			return PsnConnectError.SIGN_IN to null
		}
		catch(e: IOException)
		{
			Log.e(TAG, "Getting the PSN token failed", e)
			return PsnConnectError.PSN_UNREACHABLE to null
		}

		val connection = try
		{
			HolepunchConnection(token, logFile, logVerbose)
		}
		catch(e: CreateError)
		{
			return PsnConnectError.FAILED to e.errorCode
		}
		synchronized(psnLock) {
			if(attempt == psnAttempt)
				psnConnection = connection
			else
				connection.cancel()
		}

		val steps = listOf<Pair<PsnConnectStep, () -> ErrorCode>>(
			PsnConnectStep.CREATING_SESSION to { connection.createSession() },
			PsnConnectStep.WAKING_CONSOLE to { connection.startSession(consoleUid, connectInfo.ps5) },
			PsnConnectStep.CONNECTING_CONSOLE to { connection.punchHole() })
		var failedStep: PsnConnectStep? = null
		var errorCode = ErrorCode(0)
		for((step, action) in steps)
		{
			mainHandler.post {
				if(isCurrentPsnAttempt(attempt))
					_state.value = StreamStatePsnConnecting(step)
			}
			errorCode = action()
			if(!errorCode.isSuccess)
			{
				failedStep = step
				break
			}
		}
		synchronized(psnLock) {
			if(psnConnection === connection)
				psnConnection = null
		}

		if(failedStep == null)
		{
			val session = try
			{
				Session(connectInfo, null, logVerbose, connection, accountId)
			}
			catch(e: CreateError)
			{
				// Also when it was canceled meanwhile
				return if(isCurrentPsnAttempt(attempt)) PsnConnectError.FAILED to e.errorCode else null
			}
			mainHandler.post {
				if(isCurrentPsnAttempt(attempt) && this.session == null)
				{
					psnConnecting = false
					startSession(session)
				}
				else
					thread(name = "StreamSession dispose") { session.dispose() }
			}
			return null
		}

		Log.e(TAG, "Connecting through PSN failed at $failedStep: $errorCode")
		// Ends the session on PSN, which takes a few seconds
		connection.free()
		if(!isCurrentPsnAttempt(attempt) || errorCode.value == ErrorCode.CANCELED)
			return null
		return when
		{
			failedStep == PsnConnectStep.CREATING_SESSION -> PsnConnectError.PSN_UNREACHABLE
			errorCode.value == ErrorCode.HOST_DOWN -> PsnConnectError.CONSOLE_NO_ANSWER
			errorCode.value == ErrorCode.HOST_UNREACH -> PsnConnectError.NETWORK_BLOCKED
			else -> PsnConnectError.FAILED
		} to errorCode
	}

	/**
	 * Runs on the main thread, unless the session has ended by then,
	 * so a late event can't start the vibration again after shutdown() stopped it.
	 */
	private fun postWhileRunning(action: () -> Unit)
	{
		mainHandler.post {
			if(session != null)
				action()
		}
	}

	private fun eventCallback(event: Event)
	{
		when(event)
		{
			is ConnectedEvent -> mainHandler.post {
				inUseRetries = 0
				_state.value = StreamStateConnected
			}
			// A session that just ended may still count as in use on the console for a moment
			is QuitEvent -> if(event.reason.value == QUIT_REASON_RP_IN_USE && inUseRetries < IN_USE_RETRIES_MAX)
				postWhileRunning {
					inUseRetries++
					Log.i("StreamSession", "Console still in use, retry $inUseRetries in ${IN_USE_RETRY_DELAY_MS}ms")
					mainHandler.postDelayed({
						if(session != null)
						{
							shutdown()
							resume()
						}
					}, IN_USE_RETRY_DELAY_MS)
				}
			else _state.postValue(
				StreamStateQuit(
					event.reason,
					event.reasonString
				)
			)
			is LoginPinRequestEvent -> _state.postValue(
				StreamStateLoginPinRequest(
					event.pinIncorrect
				)
			)
			is RumbleEvent -> postWhileRunning {
				consoleRumble = event
				updateRumble()
			}
			is HapticsEvent -> postWhileRunning {
				hapticsRumble = event
				mainHandler.removeCallbacks(hapticsTimeout)
				mainHandler.postDelayed(hapticsTimeout, HAPTICS_TIMEOUT_MS)
				updateRumble()
			}
			is HapticIntensityEvent -> postWhileRunning {
				hapticIntensity = event.intensity
				updateRumble()
			}
			is TriggerEffectsEvent -> dualSenseFeedback?.setTriggerEffects(event.typeLeft, event.typeRight, event.left, event.right)
			is TriggerIntensityEvent -> dualSenseFeedback?.setTriggerIntensity(event.intensity)
			is CantDisplayEvent -> _cantDisplay.postValue(event.cantDisplay)
			is LedColorEvent ->
			{
				dualSenseFeedback?.setLightbar(event.red, event.green, event.blue)
				controllerLights?.let { postWhileRunning { it.setColor(event.red, event.green, event.blue) } }
			}
		}
	}

	fun attachToSurfaceView(surfaceView: SurfaceView)
	{
		surfaceView.holder.addCallback(object: SurfaceHolder.Callback {
			override fun surfaceCreated(holder: SurfaceHolder)
			{
				val surface = holder.surface
				this@StreamSession.surface = surface
				session?.setSurface(surface)
			}

			override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { }

			override fun surfaceDestroyed(holder: SurfaceHolder)
			{
				this@StreamSession.surface = null
				session?.setSurface(null)
			}
		})
		
		val surface = surfaceView.holder.surface
		if (surface?.isValid == true) {
			this.surface = surface
			session?.setSurface(surface)
		}
	}

	/**
	 * Attach to a custom Surface (e.g., from GLSurfaceView with debanding)
	 */
	fun attachToSurface(surface: Surface)
	{
		this.surface = surface
		session?.setSurface(surface)
	}

	fun detachSurface()
	{
		this.surface = null
		session?.setSurface(null)
	}

	fun attachToTextureView(textureView: TextureView)
	{
		textureView.surfaceTextureListener = object: TextureView.SurfaceTextureListener {
			override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int)
			{
				if(surfaceTexture != null)
					return
				surfaceTexture = surface
				this@StreamSession.surface = Surface(surfaceTexture)
				session?.setSurface(Surface(surface))
			}

			override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean
			{
				// return false if we want to keep the surface texture
				return surfaceTexture == null
			}

			override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { }
			override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
		}

		val surfaceTexture = surfaceTexture
		if(surfaceTexture != null)
			textureView.setSurfaceTexture(surfaceTexture)
	}

	fun setLoginPin(pin: String)
	{
		session?.setLoginPin(pin)
	}

	private var inUseRetries = 0

	companion object
	{
		private const val TAG = "StreamSession"
		private const val HAPTICS_TIMEOUT_MS = 100L
		// CHIAKI_QUIT_REASON_SESSION_REQUEST_RP_IN_USE
		private const val QUIT_REASON_RP_IN_USE = 4
		private const val IN_USE_RETRIES_MAX = 5
		private const val IN_USE_RETRY_DELAY_MS = 2000L
	}
}