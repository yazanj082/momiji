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
import com.metallic.chiaki.lib.*

sealed class StreamState
object StreamStateIdle: StreamState()
object StreamStateConnecting: StreamState()
object StreamStateConnected: StreamState()
data class StreamStateCreateError(val error: CreateError): StreamState()
data class StreamStateQuit(val reason: QuitReason, val reasonString: String?): StreamState()
data class StreamStateLoginPinRequest(val pinIncorrect: Boolean): StreamState()

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
		session?.stop()
		session?.dispose()
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
		if(session != null)
			return
		try
		{
			val session = Session(connectInfo, logManager.createNewFile().file.absolutePath, logVerbose)
			_state.value = StreamStateConnecting
			session.eventCallback = this::eventCallback
			applyAudioRouting(session)
			session.start()
			val surface = surface
			if(surface != null)
				session.setSurface(surface)
			this.session = session
		}
		catch(e: CreateError)
		{
			_state.value = StreamStateCreateError(e)
		}
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
		private const val HAPTICS_TIMEOUT_MS = 100L
		// CHIAKI_QUIT_REASON_SESSION_REQUEST_RP_IN_USE
		private const val QUIT_REASON_RP_IN_USE = 4
		private const val IN_USE_RETRIES_MAX = 5
		private const val IN_USE_RETRY_DELAY_MS = 2000L
	}
}