// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.ActivityManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Matrix
import android.hardware.input.InputManager
import android.net.wifi.WifiManager
import android.os.*
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.Rational
import android.view.*
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.ConnectionHelp
import com.metallic.chiaki.common.ControllerProfiles
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.isDesktopMode
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityStreamBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.main.MainActivity
import com.metallic.chiaki.session.*
import com.metallic.chiaki.touchcontrols.DefaultTouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchControlsFragment
import com.metallic.chiaki.settings.SettingsActivity
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import java.util.Locale
import kotlin.math.roundToInt

private sealed class DialogContents
private object StreamQuitDialog: DialogContents()
private object CreateErrorDialog: DialogContents()
private object PinRequestDialog: DialogContents()
private object StreamMenuDialog: DialogContents()
private object PsnErrorDialog: DialogContents()

class StreamActivity : AppCompatActivity(), View.OnSystemUiVisibilityChangeListener
{
	companion object
	{
		private const val TAG = "StreamActivity"
		private const val HINT_SHORT_MS = 2500L
		private const val HINT_LONG_MS = 4000L
		private const val HINT_FADE_MS = 150L
		const val EXTRA_CONNECT_INFO = "connect_info"
		private const val HIDE_UI_TIMEOUT_MS = 2000L
		private const val STATS_INTERVAL_MS = 1000L
		private const val BATTERY_CHECK_INTERVAL_MS = 60_000L
		// Battery level in percent at which a controller's battery is reported as low, once,
		// so that it never interrupts a game more than that
		private const val BATTERY_WARNING_LEVEL = 10
	}

	private lateinit var viewModel: StreamViewModel
	private lateinit var binding: ActivityStreamBinding

	private val uiVisibilityHandler = Handler()

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		val connectInfo = intent.getParcelableExtra<ConnectInfo>(EXTRA_CONNECT_INFO)
		if(connectInfo == null)
		{
			finish()
			return
		}

		viewModel = ViewModelProvider(this, viewModelFactory {
			StreamViewModel(application, connectInfo)
		})[StreamViewModel::class.java]

		viewModel.input.observe(this)
		viewModel.setControllerConnected(isControllerConnected())

		// Locking the orientation in the manifest makes DeX open the stream in a small fixed-size window
		if(!isDesktopMode())
			requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE

		// Use the full screen on phones with a notch/punch hole
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
			window.attributes = window.attributes.also {
				it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
			}

		// Cutscenes can run for minutes without any input, the screen must not go to sleep meanwhile
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		// Ask TVs to switch to their low latency game mode (ALLM) over HDMI
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
			window.setPreferMinimalPostProcessing(true)
		// Steady clocks instead of full speed until the phone gets hot and throttles hard
		if((getSystemService(POWER_SERVICE) as PowerManager).isSustainedPerformanceModeSupported)
			window.setSustainedPerformanceMode(true)

		binding = ActivityStreamBinding.inflate(layoutInflater)
		setContentView(binding.root)
		window.decorView.setOnSystemUiVisibilityChangeListener(this)

		viewModel.onScreenControlsVisible.observe(this, Observer {
			if(binding.onScreenControlsSwitch.isChecked != it)
				binding.onScreenControlsSwitch.isChecked = it
		})
		binding.onScreenControlsSwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setOnScreenControlsEnabled(isChecked)
			showOverlay()
		}
		binding.statsSwitch.isChecked = Preferences(this).streamStatsEnabled
		binding.statsSwitch.setOnCheckedChangeListener { _, isChecked ->
			setStatsVisible(isChecked)
			showOverlay()
		}


		val preferences = viewModel.preferences
		preferences.streamDisplayMode
			?.let { mode -> TransformMode.values().firstOrNull { it.name == mode } }
			?.let { binding.displayModeToggle.check(it.buttonId) }
		binding.displayModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if(isChecked)
				preferences.streamDisplayMode = TransformMode.fromButton(checkedId).name
			adjustStreamViewAspect()
			showOverlay()
		}

		// The display shows whichever of the two views the video goes to
		val frameRateCallback = object: SurfaceHolder.Callback
		{
			override fun surfaceCreated(holder: SurfaceHolder) = matchStreamFrameRate(holder.surface)
			override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
			override fun surfaceDestroyed(holder: SurfaceHolder) {}
		}
		binding.surfaceView.holder.addCallback(frameRateCallback)
		binding.debandSurfaceView.holder.addCallback(frameRateCallback)

// Setup video output based on debanding preference
		setupVideoOutput()
		
		val prefs = Preferences(this)
		if (prefs.touchscreenTouchpadEnabled) {
			binding.streamTouchpadView.visibility = View.VISIBLE
			binding.streamTouchpadView.controllerState
				.subscribe { 
					lastStreamTouchpadControllerState = it
					updateCombinedTouchState()
				}
				.addTo(controlsDisposable)
		}

		viewModel.session.state.observe(this, Observer { this.stateChanged(it) })
		// A double tap on the stream shows or hides the overlay, as on the on-screen controls' background.
		// It only gets taps that no control or touchpad took.
		val overlayTapDetector = GestureDetector(this, object: GestureDetector.SimpleOnGestureListener()
		{
			override fun onDown(e: MotionEvent) = true

			override fun onDoubleTap(e: MotionEvent): Boolean
			{
				toggleOverlay()
				return true
			}
		})
		binding.root.setOnTouchListener { _, event -> overlayTapDetector.onTouchEvent(event) }
		// The small window grows out of the picture where it is
		binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePictureInPictureParams() }
		viewModel.session.cantDisplay.observe(this, Observer {
			if(it)
				showHint(getString(R.string.stream_cant_display), HINT_LONG_MS)
		})
		adjustStreamViewAspect()

		if(Preferences(this).rumbleEnabled)
		{
			val rumble = ControllerRumble(this)
			this.rumble = rumble
			var cannotVibrateShown = false
			viewModel.session.rumbleState.observe(this, Observer {
				val controllerId = viewModel.input.lastControllerDeviceId
				val result = rumble.rumble(controllerId, it.left.toInt(), it.right.toInt())
				// Tell the user once why nothing happens instead of failing silently
				if(result == ControllerRumble.Result.CONTROLLER_CANNOT_VIBRATE && !cannotVibrateShown)
				{
					cannotVibrateShown = true
					showHint(getString(R.string.rumble_controller_cannot_vibrate, rumble.controllerName(controllerId) ?: ""), HINT_LONG_MS)
				}
			})
		}
	}

	private var rumble: ControllerRumble? = null

	private val inputDeviceListener = object: InputManager.InputDeviceListener
	{
		override fun onInputDeviceAdded(deviceId: Int) = inputDevicesChanged()

		override fun onInputDeviceRemoved(deviceId: Int)
		{
			viewModel.input.forgetInputDevice(deviceId)
			inputDevicesChanged()
		}

		override fun onInputDeviceChanged(deviceId: Int)
		{
			viewModel.input.forgetInputDevice(deviceId)
			inputDevicesChanged()
		}

		private fun inputDevicesChanged()
		{
			viewModel.input.onInputDevicesChanged()
			viewModel.session.onInputDevicesChanged()
			viewModel.setControllerConnected(isControllerConnected())
			if(hasWindowFocus())
				updatePointerCapture()
		}
	}

	/** Draws the video through shaders, when debanding or super resolution is on */
	private var videoRenderer: VideoRenderer? = null

	/**
	 * Lets the display run at a rate that fits the stream, for example 60 or 120 Hz instead of 90 Hz,
	 * so that every frame is shown for the same time
	 */
	private fun matchStreamFrameRate(surface: Surface)
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !surface.isValid)
			return
		val fps = viewModel.session.connectInfo.videoProfile.maxFPS.toFloat()
		// Only switches that don't blank the screen, as a new HDMI mode does on TVs for seconds
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
			surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
		else
			surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
	}

	private fun setupVideoOutput() {
		val prefs = Preferences(this)
		viewModel.session.detachSurface()
		val upscale = prefs.upscalingEnabled && VideoRenderer.isUpscalingSupported(this)

		if (prefs.debandingEnabled || upscale) {
			// Use GLSurfaceView to draw the video through shaders
			binding.surfaceView.visibility = View.GONE
			binding.debandSurfaceView.visibility = View.VISIBLE

			val videoProfile = viewModel.session.connectInfo.videoProfile
			val upscaleShader = if (upscale)
				assets.open(VideoRenderer.UPSCALE_SHADER_ASSET).bufferedReader().use { it.readText() }
			else
				null
			videoRenderer = VideoRenderer(videoProfile.width, videoProfile.height, prefs.debandingEnabled,
				prefs.sharpnessIntensity, upscaleShader, { binding.debandSurfaceView.requestRender() }) { surface ->
				viewModel.session.attachToSurface(surface)
			}

			binding.debandSurfaceView.setEGLContextClientVersion(3)
			binding.debandSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 0, 0)
			binding.debandSurfaceView.holder.setFormat(android.graphics.PixelFormat.RGBA_8888)
			// Debanding runs at the stream's resolution, as its cost would grow with the window (a
			// 1440p/4K monitor in DeX is far too slow and stalls the video). Without upscaling, the
			// surface has that size too and the system scales it to the view. Upscaling needs the view's
			// size, but only its last pass, which is cheap, runs at that size.
			if (!upscale)
				binding.debandSurfaceView.holder.setFixedSize(videoProfile.width, videoProfile.height)
			binding.debandSurfaceView.setRenderer(videoRenderer)
			// Draw only when the decoder delivers a frame instead of at the display's refresh rate
			binding.debandSurfaceView.renderMode = android.opengl.GLSurfaceView.RENDERMODE_WHEN_DIRTY
		} else {
			// Use standard SurfaceView (no shader processing)
			binding.surfaceView.visibility = View.VISIBLE
			binding.debandSurfaceView.visibility = View.GONE
			viewModel.session.attachToSurfaceView(binding.surfaceView)
		}
	}

	private var lastFragmentControllerState = com.metallic.chiaki.lib.ControllerState()
	private var lastStreamTouchpadControllerState = com.metallic.chiaki.lib.ControllerState()

	private fun updateCombinedTouchState() {
		viewModel.input.touchControllerState = lastFragmentControllerState or lastStreamTouchpadControllerState
	}

	private val controlsDisposable = CompositeDisposable()

	override fun onAttachFragment(fragment: Fragment)
	{
		super.onAttachFragment(fragment)
		if(fragment is TouchControlsFragment)
		{
			// While the controls are played on, a single tap beside them is usually a missed button
			fragment.onBackgroundDoubleTap = { toggleOverlay() }
			fragment.controllerState
				.subscribe { 
					lastFragmentControllerState = it
					updateCombinedTouchState()
				}
				.addTo(controlsDisposable)
			fragment.onScreenControlsEnabled = viewModel.onScreenControlsVisible
		}
	}

	/**
	 * The stream runs while the activity can be seen: picture-in-picture and split screen pause the
	 * activity, but don't stop it. Android pauses it already while it moves into the small window.
	 */
	override fun onStart()
	{
		super.onStart()
		if (videoRenderer != null) {
			binding.debandSurfaceView.onResume()
		}
		acquireWifiLock()
		viewModel.session.resume()
		if(Preferences(this).streamStatsEnabled)
			setStatsVisible(true)
		statsHandler.post(checkBatteryRunnable)
	}

	override fun onResume()
	{
		super.onResume()
		hideSystemUI()
		(getSystemService(INPUT_SERVICE) as InputManager).registerInputDeviceListener(inputDeviceListener, null)
		viewModel.input.menuComboCallback = { showStreamMenu() }
	}

	private var wifiLock: WifiManager.WifiLock? = null

	/**
	 * Keeps Wi-Fi out of power saving and background scans while streaming,
	 * which otherwise cause latency spikes and lost frames, especially with a weak signal.
	 */
	private fun acquireWifiLock()
	{
		if(wifiLock != null)
			return
		val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
		@Suppress("DEPRECATION")
		val mode = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
		wifiLock = wifiManager.createWifiLock(mode, "Chiaki:stream").also {
			it.setReferenceCounted(false)
			it.acquire()
		}
	}

	override fun onPause()
	{
		super.onPause()
		viewModel.input.menuComboCallback = null
		(getSystemService(INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(inputDeviceListener)
	}

	override fun onStop()
	{
		super.onStop()
		if (videoRenderer != null) {
			binding.debandSurfaceView.onPause()
		}
		wifiLock?.release()
		wifiLock = null
		rumble?.stop()
		statsHandler.removeCallbacks(updateStatsRunnable)
		statsHandler.removeCallbacks(checkBatteryRunnable)
		viewModel.session.pause()
		countPlayTime()
		// Leaving the app ends the stream, as it always has. So does closing the small window.
		if(!isChangingConfigurations)
			finish()
	}

	/**
	 * The stream has a task of its own, and after picture-in-picture or switching apps, the task behind
	 * it can be another app's. Quitting the stream on screen (Back, the menu, a dialog) goes back to the
	 * consoles, while closing the small window or leaving the app doesn't bring Momiji up.
	 */
	override fun finish()
	{
		if(isTaskRoot && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !isInPictureInPictureMode)
			showConsoles()
		super.finish()
	}

	private fun showConsoles()
	{
		val mainTask = getSystemService(ActivityManager::class.java).appTasks.firstOrNull {
			try
			{
				it.taskInfo.baseActivity?.className != StreamActivity::class.java.name
			}
			catch(e: IllegalArgumentException) // The task ended meanwhile
			{
				false
			}
		}
		if(mainTask != null)
			mainTask.moveToFront()
		else
			startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
	}

	/** Picture-in-picture, where the device has it. In desktop modes the stream is a window already. */
	private val pictureInPictureSupported by lazy {
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
			&& packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
			&& !isDesktopMode()
	}

	/** Only a running stream goes into the small window, not connecting or an error */
	private fun pictureInPictureWanted() = pictureInPictureSupported && Preferences(this).pictureInPicture
			&& viewModel.session.state.value == StreamStateConnected

	@RequiresApi(Build.VERSION_CODES.O)
	private fun pictureInPictureParams(): PictureInPictureParams
	{
		val profile = viewModel.session.connectInfo.videoProfile
		val streamView = if(binding.debandSurfaceView.isVisible) binding.debandSurfaceView else binding.surfaceView
		val builder = PictureInPictureParams.Builder()
			.setAspectRatio(Rational(profile.width, profile.height))
		val sourceRect = Rect()
		if(streamView.getGlobalVisibleRect(sourceRect))
			builder.setSourceRectHint(sourceRect)
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
			builder.setAutoEnterEnabled(pictureInPictureWanted())
				// The picture is video, which a crossfade shows better than a stretch
				.setSeamlessResizeEnabled(false)
		return builder.build()
	}

	private fun updatePictureInPictureParams()
	{
		if(pictureInPictureSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			setPictureInPictureParams(pictureInPictureParams())
	}

	/**
	 * Android 12 and later enter picture-in-picture by themselves when leaving, with the auto-enter
	 * of the params. Before, and where that doesn't happen, it's entered here.
	 */
	override fun onUserLeaveHint()
	{
		super.onUserLeaveHint()
		val wanted = pictureInPictureWanted()
		Log.i(TAG, "Leaving the stream, picture-in-picture wanted: $wanted, already in it: $isInPictureInPictureMode")
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && wanted && !isInPictureInPictureMode)
		{
			val entered = try
			{
				enterPictureInPictureMode(pictureInPictureParams())
			}
			catch(e: IllegalStateException)
			{
				Log.w(TAG, "Entering picture-in-picture failed", e)
				false
			}
			Log.i(TAG, "Entering picture-in-picture: $entered")
		}
	}

	override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration)
	{
		super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
		Log.i(TAG, "Picture-in-picture: $isInPictureInPictureMode")
		// Closing the small window stops the activity first
		if(!isInPictureInPictureMode && lifecycle.currentState == Lifecycle.State.CREATED)
		{
			finish()
			return
		}
		if(isInPictureInPictureMode)
		{
			// Only the picture fits in the small window
			if(dialogContents == StreamMenuDialog)
				dialog?.dismiss()
			uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
			binding.overlay.animate().cancel()
			binding.overlay.isGone = true
		}
		binding.statsTextView.isVisible = !isInPictureInPictureMode && Preferences(this).streamStatsEnabled
		supportFragmentManager.findFragmentById(R.id.controlsFragment)?.view?.isVisible = !isInPictureInPictureMode
		binding.streamTouchpadView.isVisible = !isInPictureInPictureMode && Preferences(this).touchscreenTouchpadEnabled
	}

	private val statsHandler = Handler(Looper.getMainLooper())
	private var statsLastFrames = -1L
	private var statsLastTimeMs = 0L

	private val updateStatsRunnable = object: Runnable
	{
		override fun run()
		{
			updateStats()
			statsHandler.postDelayed(this, STATS_INTERVAL_MS)
		}
	}

	/** Shows or hides the statistics, and remembers it for the next streams */
	private fun setStatsVisible(visible: Boolean)
	{
		Preferences(this).streamStatsEnabled = visible
		if(binding.statsSwitch.isChecked != visible)
			binding.statsSwitch.isChecked = visible
		statsHandler.removeCallbacks(updateStatsRunnable)
		statsLastFrames = -1
		binding.statsTextView.isVisible = visible
		if(visible)
			statsHandler.post(updateStatsRunnable)
	}

	/** Turns super resolution off or on right away, and remembers it for the next streams */
	private fun setUpscalingEnabled(enabled: Boolean)
	{
		Preferences(this).upscalingEnabled = enabled
		videoRenderer?.upscalingEnabled = enabled
		binding.debandSurfaceView.requestRender()
	}

	private fun updateStats()
	{
		val stats = viewModel.session.stats() ?: return
		val now = SystemClock.elapsedRealtime()
		// Frames per second from the frames rendered since the last update
		val fps = if(statsLastFrames >= 0 && now > statsLastTimeMs)
			(stats.framesRendered - statsLastFrames) * 1000f / (now - statsLastTimeMs)
		else
			0f
		statsLastFrames = stats.framesRendered
		statsLastTimeMs = now
		val profile = viewModel.session.connectInfo.videoProfile
		// Technical figures, in the same digits in every language
		fun format(id: Int, vararg args: Any) = String.format(Locale.ROOT, getString(id), *args)
		binding.statsTextView.text = listOfNotNull(
			format(R.string.stream_stats_video, profile.width, profile.height, fps),
			upscalingStats(),
			format(R.string.stream_stats_network, stats.bitrateMbps, stats.packetLoss * 100f, stats.pingMs),
			format(R.string.stream_stats_decode, stats.decodeMsAverage, stats.decodeMsMax)
		).joinToString("\n")
	}

	/** What super resolution does with the picture, when it's on for this stream */
	private fun upscalingStats(): String?
	{
		val (state, size) = videoRenderer?.lastUpscaling ?: return null
		return when(state)
		{
			// The size in the same digits as the other figures
			VideoRenderer.Upscaling.ACTIVE -> String.format(Locale.ROOT, getString(R.string.stream_stats_upscaled), size.width, size.height)
			VideoRenderer.Upscaling.NOT_NEEDED -> getString(R.string.stream_stats_upscaling_not_needed)
			VideoRenderer.Upscaling.UNSUPPORTED -> getString(R.string.stream_stats_upscaling_unsupported)
			VideoRenderer.Upscaling.OFF -> null
		}
	}

	override fun onDestroy()
	{
		super.onDestroy()
		rumble?.stop()
		videoRenderer?.release()
		controlsDisposable.dispose()
	}

	private fun reconnect()
	{
		viewModel.session.shutdown()
		viewModel.session.resume()
	}

	// Also hides the overlay directly, because in DeX windows the system UI visibility callback may never fire
	private val hideSystemUIRunnable = Runnable {
		hideSystemUI()
		hideOverlay()
	}

	override fun onSystemUiVisibilityChange(visibility: Int)
	{
		if(visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0)
			showOverlay()
		else
			hideOverlay()
	}

	private fun showOverlay()
	{
		// Mouse cursor is only visible together with the overlay
		binding.root.pointerIcon = null
		binding.overlay.isVisible = true
		binding.overlay.animate()
			.alpha(1.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.alpha = 1.0f
				}
			})
		scheduleHideOverlay()
	}

	/** A small label that fades in and out by itself, over the stream */
	private fun showHint(text: String, durationMs: Long)
	{
		val hint = binding.hintTextView
		hint.animate().cancel()
		hint.text = text
		hint.alpha = 0f
		hint.isVisible = true
		hint.animate()
			.alpha(1f)
			.setStartDelay(0)
			.setDuration(HINT_FADE_MS)
			.withEndAction {
				hint.animate()
					.alpha(0f)
					.setStartDelay(durationMs)
					.setDuration(HINT_FADE_MS * 2)
					.withEndAction { hint.isVisible = false }
			}
	}

	private fun toggleOverlay()
	{
		if(binding.overlay.isVisible && binding.overlay.alpha > 0.5f)
		{
			uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
			hideSystemUIRunnable.run()
		}
		else
			showOverlay()
	}

	private fun scheduleHideOverlay()
	{
		uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
		uiVisibilityHandler.postDelayed(hideSystemUIRunnable, HIDE_UI_TIMEOUT_MS)
	}

	private fun hideOverlay()
	{
		binding.root.pointerIcon = PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL)
		binding.overlay.animate()
			.alpha(0.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.isGone = true
				}
			})
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
		if(hasFocus)
		{
			hideSystemUI()
			updatePointerCapture()
		}
	}

	/**
	 * While captured, Android reports a controller touchpad that it otherwise makes a mouse
	 * pointer with its real finger positions, so they can be forwarded to the console.
	 * Only done while such a controller is connected, as it also captures a real mouse.
	 */
	private fun updatePointerCapture()
	{
		val root = binding.root
		if(viewModel.input.isPointerTouchpadConnected())
		{
			root.setOnCapturedPointerListener { _, event -> viewModel.input.onCapturedPointerEvent(event) }
			// Captured events go to the focused view
			root.isFocusable = true
			root.isFocusableInTouchMode = true
			root.requestFocus()
			if(!root.hasPointerCapture())
				root.requestPointerCapture()
		}
		else if(root.hasPointerCapture())
			root.releasePointerCapture()
	}

	private fun hideSystemUI()
	{
		window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
				or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
				or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
				or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
				or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
				or View.SYSTEM_UI_FLAG_FULLSCREEN)
		// Desktop modes like Samsung DeX also give the window a caption bar, which only a
		// full screen window may hide
		@Suppress("DEPRECATION")
		window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
			window.insetsController?.hide(WindowInsets.Type.systemBars() or WindowInsets.Type.captionBar())
	}

	private var dialogContents: DialogContents? = null
	private var dialog: AlertDialog? = null
		set(value)
		{
			field = value
			if(value == null)
				dialogContents = null
		}

	/**
	 * Lets controller-only setups (TV boxes) leave the stream, opened with L1 + R1 + Options + Share.
	 */
	private fun showStreamMenu()
	{
		if(dialog != null)
			return
		// The dialog takes the input from here on, so the console must not see the combo held forever
		viewModel.input.releaseAll()
		val statsVisible = binding.statsTextView.isVisible
		val title = controllerBattery()?.let { (percent, charging) ->
			getString(if(charging) R.string.stream_menu_title_battery_charging else R.string.stream_menu_title_battery, percent)
		} ?: getString(R.string.stream_menu_title)
		// Super resolution can be turned off and on to compare, when this stream uses it
		val upscalingEnabled = videoRenderer?.takeIf { it.upscalingAvailable }?.upscalingEnabled
		val items = listOfNotNull<Pair<String, () -> Unit>>(
			getString(R.string.action_stream_menu_resume) to {},
			getString(if(statsVisible) R.string.action_hide_stats else R.string.action_show_stats) to { setStatsVisible(!statsVisible) },
			upscalingEnabled?.let { enabled ->
				getString(if(enabled) R.string.action_upscaling_off else R.string.action_upscaling_on) to { setUpscalingEnabled(!enabled) }
			},
			getString(R.string.action_quit_session) to { finish() })
		dialog = MaterialAlertDialogBuilder(this)
			.setTitle(title)
			.setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
			.setOnDismissListener {
				dialog = null
				hideSystemUI()
			}
			.create()
		dialogContents = StreamMenuDialog
		dialog?.show()
	}

	private var streamMenuHintShown = false

	/**
	 * Battery level in percent of the controller in use and whether it's charging, where Android
	 * knows it (Android 12+)
	 */
	private fun controllerBattery(): Pair<Int, Boolean>?
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
			return null
		val device = viewModel.input.lastControllerDeviceId?.let { InputDevice.getDevice(it) }
			?: ControllerProfiles.connectedControllers().firstOrNull()
			?: return null
		val battery = device.batteryState
		// Android reports -1 % when it doesn't know the charge, such as for some controllers on a cable
		if(!battery.isPresent || battery.capacity.isNaN() || battery.capacity !in 0f..1f)
			return null
		val charging = battery.status == android.hardware.BatteryState.STATUS_CHARGING
			|| battery.status == android.hardware.BatteryState.STATUS_FULL
		return Pair((battery.capacity * 100).roundToInt(), charging)
	}

	private var batteryWarned = false

	private val checkBatteryRunnable = object: Runnable
	{
		override fun run()
		{
			controllerBattery()?.let { (percent, charging) ->
				if(charging)
					batteryWarned = false
				else if(percent <= BATTERY_WARNING_LEVEL && !batteryWarned)
				{
					batteryWarned = true
					showHint(getString(R.string.controller_battery_low, percent), HINT_SHORT_MS)
				}
			}
			statsHandler.postDelayed(this, BATTERY_CHECK_INTERVAL_MS)
		}
	}

	private fun isControllerConnected() = InputDevice.getDeviceIds().any { id ->
		InputDevice.getDevice(id)?.let { !it.isVirtual && it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD } ?: false
	}

	/** Since when the stream is playing, for the play time behind the one-time thank-you */
	private var playingSinceMs: Long? = null

	private fun countPlayTime()
	{
		val since = playingSinceMs ?: return
		playingSinceMs = null
		val preferences = Preferences(this)
		preferences.playTimeMs += SystemClock.elapsedRealtime() - since
	}

	private fun stateChanged(state: StreamState)
	{
		if(state == StreamStateConnected && playingSinceMs == null)
			playingSinceMs = SystemClock.elapsedRealtime()
		else if(state is StreamStateQuit || state is StreamStateCreateError || state is StreamStatePsnError)
			countPlayTime()

		binding.progressBar.visibility = if(state == StreamStateConnecting || state is StreamStatePsnConnecting) View.VISIBLE else View.GONE
		updatePictureInPictureParams()
		binding.connectingTextView.isVisible = state is StreamStatePsnConnecting
		if(state is StreamStatePsnConnecting)
			binding.connectingTextView.setText(when(state.step)
			{
				PsnConnectStep.SIGNING_IN, PsnConnectStep.CREATING_SESSION -> R.string.psn_connect_creating_session
				PsnConnectStep.WAKING_CONSOLE -> R.string.psn_connect_waking_console
				PsnConnectStep.CONNECTING_CONSOLE -> R.string.psn_connect_connecting_console
			})

		if(state == StreamStateConnected && !streamMenuHintShown)
		{
			streamMenuHintShown = true
			// Not a toast: Android cuts those off after two lines
			if(isControllerConnected())
				showHint(getString(R.string.stream_menu_hint) + "\n" + getString(R.string.stream_overlay_hint), HINT_LONG_MS)
			else
				showHint(getString(R.string.stream_overlay_hint), HINT_SHORT_MS)
		}

		when(state)
		{
			is StreamStateQuit ->
			{
				if(dialogContents != StreamQuitDialog)
				{
					if(state.reason.isError)
					{
						dialog?.dismiss()
						val explanation = ConnectionHelp.explain(state.reason, state.reasonString,
							viewModel.session.connectInfo.psnConsoleUid != null)
						// What the library said, smaller, for reports
						val details = getString(R.string.help_details, state.reason.toString() + (state.reasonString?.let { " ($it)" } ?: ""))
						val message = SpannableStringBuilder(getString(explanation.message))
							.append("\n\n")
							.append(details, RelativeSizeSpan(0.85f), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
						val dialog = MaterialAlertDialogBuilder(this)
							.setTitle(explanation.title)
							.setMessage(message)
							.setPositiveButton(R.string.action_reconnect) { _, _ ->
								dialog = null
								// So that the dialog shows again if the new connection quits as well
								dialogContents = null
								reconnect()
							}
							.setOnCancelListener {
								dialog = null
								finish()
							}
							.setNegativeButton(R.string.action_quit_session) { _, _ ->
								dialog = null
								finish()
							}
							.create()
						dialogContents = StreamQuitDialog
						dialog.show()
					}
					else
						finish()
				}
			}

			is StreamStateCreateError ->
			{
				if(dialogContents != CreateErrorDialog)
				{
					dialog?.dismiss()
					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(getString(R.string.alert_message_session_create_error, state.error.errorCode.toString()))
						.setOnDismissListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ -> }
						.create()
					dialogContents = CreateErrorDialog
					dialog.show()
				}
			}

			is StreamStateLoginPinRequest ->
			{
				if(dialogContents != PinRequestDialog)
				{
					dialog?.dismiss()

					val view = layoutInflater.inflate(R.layout.dialog_login_pin, null)
					val pinEditText = view.findViewById<EditText>(R.id.pinEditText)

					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(
							if(state.pinIncorrect)
								R.string.alert_message_login_pin_request_incorrect
							else
								R.string.alert_message_login_pin_request)
						.setView(view)
						.setPositiveButton(R.string.action_login_pin_connect) { _, _ ->
							dialog = null
							viewModel.session.setLoginPin(pinEditText.text.toString())
						}
						.setOnCancelListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ ->
							dialog = null
							finish()
						}
						.create()
					dialogContents = PinRequestDialog
					dialog.show()
				}
			}
			is StreamStatePsnError ->
			{
				if(dialogContents != PsnErrorDialog)
				{
					dialog?.dismiss()
					val message = when(state.error)
					{
						PsnConnectError.SIGN_IN -> R.string.psn_connect_error_sign_in
						PsnConnectError.PSN_UNREACHABLE -> R.string.psn_connect_error_psn_unreachable
						PsnConnectError.CONSOLE_NO_ANSWER -> R.string.psn_connect_error_console_no_answer
						PsnConnectError.NETWORK_BLOCKED -> R.string.psn_connect_error_network_blocked
						PsnConnectError.FAILED -> R.string.psn_connect_error_failed
					}
					val builder = MaterialAlertDialogBuilder(this)
						.setTitle(R.string.psn_connect_error_title)
						.setMessage(getString(message) + (state.errorCode?.let { "\n\n(${it})" } ?: ""))
						.setOnCancelListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ ->
							dialog = null
							finish()
						}
					if(state.error == PsnConnectError.SIGN_IN)
						builder.setPositiveButton(R.string.internet_play_sign_in) { _, _ ->
							dialog = null
							startActivity(SettingsActivity.internetPlayIntent(this))
							finish()
						}
					else
						builder.setPositiveButton(R.string.action_try_again) { _, _ ->
							dialog = null
							dialogContents = null
							reconnect()
						}
					val dialog = builder.create()
					dialogContents = PsnErrorDialog
					dialog.show()
					this.dialog = dialog
				}
			}
			// These states don't need special handling
			StreamStateIdle, StreamStateConnecting, is StreamStatePsnConnecting, StreamStateConnected -> { }
		}
	}

	private fun adjustTextureViewAspect(textureView: TextureView)
	{
		val trans = TextureViewTransform(viewModel.session.connectInfo.videoProfile, textureView)
		val resolution = trans.resolutionFor(TransformMode.fromButton(binding.displayModeToggle.checkedButtonId))
		Matrix().also {
			textureView.getTransform(it)
			it.setScale(resolution.width / trans.viewWidth, resolution.height / trans.viewHeight)
			it.postTranslate((trans.viewWidth - resolution.width) * 0.5f, (trans.viewHeight - resolution.height) * 0.5f)
			textureView.setTransform(it)
		}
	}

	private fun adjustSurfaceViewAspect()
	{
		val videoProfile = viewModel.session.connectInfo.videoProfile
		binding.aspectRatioLayout.aspectRatio = videoProfile.width.toFloat() / videoProfile.height.toFloat()
		binding.aspectRatioLayout.mode = TransformMode.fromButton(binding.displayModeToggle.checkedButtonId)
	}

	private fun adjustStreamViewAspect() = adjustSurfaceViewAspect()

	override fun dispatchKeyEvent(event: KeyEvent) = viewModel.input.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
	override fun onGenericMotionEvent(event: MotionEvent) = viewModel.input.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)

	// Clicks of a controller touchpad that Android made a mouse pointer must not reach the views
	override fun dispatchTouchEvent(event: MotionEvent) =
		viewModel.input.onControllerPointerEvent(event) || super.dispatchTouchEvent(event)

	override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean
	{
		if(viewModel.input.onControllerPointerEvent(event))
			return true
		// Moving the mouse (e.g. in DeX) brings up the overlay, since there is no system UI swipe there
		if(event.isFromSource(InputDevice.SOURCE_MOUSE) && event.actionMasked == MotionEvent.ACTION_HOVER_MOVE)
		{
			if(!binding.overlay.isVisible || binding.overlay.alpha < 1.0f)
				showOverlay()
			else
				scheduleHideOverlay()
		}
		return super.dispatchGenericMotionEvent(event)
	}
}

enum class TransformMode
{
	FIT,
	STRETCH,
	ZOOM;

	val buttonId get() = when(this)
	{
		FIT -> R.id.display_mode_normal_button
		STRETCH -> R.id.display_mode_stretch_button
		ZOOM -> R.id.display_mode_zoom_button
	}

	companion object
	{
		fun fromButton(displayModeButtonId: Int)
			= when (displayModeButtonId)
			{
				R.id.display_mode_stretch_button -> STRETCH
				R.id.display_mode_zoom_button -> ZOOM
				else -> FIT
			}
	}
}

class TextureViewTransform(private val videoProfile: ConnectVideoProfile, private val textureView: TextureView)
{
	private val contentWidth : Float get() = videoProfile.width.toFloat()
	private val contentHeight : Float get() = videoProfile.height.toFloat()
	val viewWidth : Float get() = textureView.width.toFloat()
	val viewHeight : Float get() = textureView.height.toFloat()
	private val contentAspect : Float get() =  contentHeight / contentWidth

	fun resolutionFor(mode: TransformMode): Resolution
		= when(mode)
		{
			TransformMode.STRETCH -> strechedResolution
			TransformMode.ZOOM -> zoomedResolution
			TransformMode.FIT -> normalResolution
		}

	private val strechedResolution get() = Resolution(viewWidth, viewHeight)

	private val zoomedResolution get() =
		if(viewHeight > viewWidth * contentAspect)
		{
			val zoomFactor = viewHeight / contentHeight
			Resolution(contentWidth * zoomFactor, viewHeight)
		}
		else
		{
			val zoomFactor = viewWidth / contentWidth
			Resolution(viewWidth, contentHeight * zoomFactor)
		}

	private val normalResolution get() =
		if(viewHeight > viewWidth * contentAspect)
			Resolution(viewWidth, viewWidth * contentAspect)
		else
			Resolution(viewHeight / contentAspect, viewHeight)
}


data class Resolution(val width: Float, val height: Float)
