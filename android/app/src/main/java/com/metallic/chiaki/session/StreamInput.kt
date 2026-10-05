package com.metallic.chiaki.session

import android.content.Context
import android.hardware.*
import android.os.Build
import android.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.metallic.chiaki.common.ControllerProfiles
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.ControllerState
import kotlin.math.hypot

class StreamInput(val context: Context, val preferences: Preferences)
{
	var controllerStateChangedCallback: ((ControllerState) -> Unit)? = null

	/**
	 * Id of the physical controller that most recently sent input, so rumble can be sent back to it.
	 */
	var lastControllerDeviceId: Int? = null
		private set

	val controllerState: ControllerState get()
	{
		val controllerState = sensorControllerState or keyControllerState or motionControllerState or touchpadControllerState

		val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
		@Suppress("DEPRECATION")
		when(windowManager.defaultDisplay.rotation)
		{
			Surface.ROTATION_90 -> {
				controllerState.accelX *= -1.0f
				controllerState.accelZ *= -1.0f
				controllerState.gyroX *= -1.0f
				controllerState.gyroZ *= -1.0f
				controllerState.orientX *= -1.0f
				controllerState.orientZ *= -1.0f
			}
			else -> {}
		}

		// prioritize motion controller's l2 and r2 over key
		// (some controllers send only key, others both but key earlier than full press)
		if(motionControllerState.l2State > 0U)
			controllerState.l2State = motionControllerState.l2State
		if(motionControllerState.r2State > 0U)
			controllerState.r2State = motionControllerState.r2State

		return controllerState or touchControllerState
	}

	private val sensorControllerState = ControllerState() // from Motion Sensors
	private val keyControllerState = ControllerState() // from KeyEvents
	private val motionControllerState = ControllerState() // from MotionEvents
	private val touchpadControllerState = ControllerState() // from the controller's touchpad
	var touchControllerState = ControllerState()
		set(value)
		{
			field = value
			controllerStateUpdated()
		}

	private val controllerProfiles = ControllerProfiles(context)

	/** A controller's settings, as the input handling needs them */
	private class InputProfile(
		val buttonMappings: Map<Int, UInt>,
		val keyL2: Int,
		val keyR2: Int,
		/** 0 to 1 */
		val stickDeadZone: Float
	)

	// Each controller's settings are read once, instead of from SharedPreferences on every event
	private val inputProfiles = mutableMapOf<Int, InputProfile>()

	private fun inputProfile(deviceId: Int) = inputProfiles.getOrPut(deviceId) {
		val profile = controllerProfiles.profile(InputDevice.getDevice(deviceId)?.descriptor)
		val keyCodes = profile.keyCodes
		// Swapping the face buttons per controller, for example for Nintendo layouts
		fun face(button: ControllerProfiles.Button, normal: UInt, swapped: UInt) =
			keyCodes.getValue(button) to (if(profile.swapFaceButtons) swapped else normal)
		val mappings = linkedMapOf(
			face(ControllerProfiles.Button.CROSS, ControllerState.BUTTON_CROSS, ControllerState.BUTTON_MOON),
			face(ControllerProfiles.Button.CIRCLE, ControllerState.BUTTON_MOON, ControllerState.BUTTON_CROSS),
			face(ControllerProfiles.Button.SQUARE, ControllerState.BUTTON_BOX, ControllerState.BUTTON_PYRAMID),
			face(ControllerProfiles.Button.TRIANGLE, ControllerState.BUTTON_PYRAMID, ControllerState.BUTTON_BOX),
			keyCodes.getValue(ControllerProfiles.Button.L1) to ControllerState.BUTTON_L1,
			keyCodes.getValue(ControllerProfiles.Button.R1) to ControllerState.BUTTON_R1,
			keyCodes.getValue(ControllerProfiles.Button.L3) to ControllerState.BUTTON_L3,
			keyCodes.getValue(ControllerProfiles.Button.R3) to ControllerState.BUTTON_R3,
			keyCodes.getValue(ControllerProfiles.Button.SHARE) to ControllerState.BUTTON_SHARE,
			keyCodes.getValue(ControllerProfiles.Button.OPTIONS) to ControllerState.BUTTON_OPTIONS,
			keyCodes.getValue(ControllerProfiles.Button.PS) to ControllerState.BUTTON_PS,
			keyCodes.getValue(ControllerProfiles.Button.TOUCHPAD) to ControllerState.BUTTON_TOUCHPAD
		).filterKeys { it != 0 }
		InputProfile(
			// Controllers that report the D-pad as keys instead of a hat axis
			mapOf(
				KeyEvent.KEYCODE_DPAD_UP to ControllerState.BUTTON_DPAD_UP,
				KeyEvent.KEYCODE_DPAD_DOWN to ControllerState.BUTTON_DPAD_DOWN,
				KeyEvent.KEYCODE_DPAD_LEFT to ControllerState.BUTTON_DPAD_LEFT,
				KeyEvent.KEYCODE_DPAD_RIGHT to ControllerState.BUTTON_DPAD_RIGHT
			) + mappings,
			keyCodes.getValue(ControllerProfiles.Button.L2),
			keyCodes.getValue(ControllerProfiles.Button.R2),
			profile.stickDeadZone / 100f
		)
	}

	private val sensorEventListener = object: SensorEventListener {
		override fun onSensorChanged(event: SensorEvent)
		{
			when(event.sensor.type)
			{
				Sensor.TYPE_ACCELEROMETER -> {
					sensorControllerState.accelX = event.values[1] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelY = event.values[2] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelZ = event.values[0] / SensorManager.GRAVITY_EARTH
				}
				Sensor.TYPE_GYROSCOPE -> {
					sensorControllerState.gyroX = event.values[1]
					sensorControllerState.gyroY = event.values[2]
					sensorControllerState.gyroZ = event.values[0]
				}
				Sensor.TYPE_ROTATION_VECTOR -> {
					val q = floatArrayOf(0f, 0f, 0f, 0f)
					SensorManager.getQuaternionFromVector(q, event.values)
					sensorControllerState.orientX = q[2]
					sensorControllerState.orientY = q[3]
					sensorControllerState.orientZ = q[1]
					sensorControllerState.orientW = q[0]
				}
				else -> return
			}
			controllerStateUpdated()
		}

		override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
	}

	/**
	 * Receives the motion of controllers that have their own sensors (DualSense, DualShock 4
	 * on Android 12+) in rad/s and g. The phone's sensors are not used while one is connected.
	 */
	var controllerMotionCallback: ((gyroX: Float, gyroY: Float, gyroZ: Float, accelX: Float, accelY: Float, accelZ: Float, timestampUs: Int) -> Unit)? = null

	private var controllerSensorManager: SensorManager? = null
	private val controllerAccel = floatArrayOf(0.0f, 1.0f, 0.0f)

	private val controllerSensorEventListener = object: SensorEventListener {
		override fun onSensorChanged(event: SensorEvent)
		{
			when(event.sensor.type)
			{
				Sensor.TYPE_ACCELEROMETER ->
					for(i in 0 until 3)
						controllerAccel[i] = event.values[i] / SensorManager.GRAVITY_EARTH
				// The gyroscope drives the updates, with the latest acceleration
				Sensor.TYPE_GYROSCOPE -> synchronized(sendLock) {
					controllerMotionCallback?.invoke(event.values[0], event.values[1], event.values[2],
						controllerAccel[0], controllerAccel[1], controllerAccel[2], (event.timestamp / 1000).toInt())
					sendControllerState()
				}
			}
		}

		override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
	}

	private var motionActive = false

	private fun startMotion()
	{
		motionActive = true
		if(startControllerMotion())
			return
		val samplingPeriodUs = 4000
		val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
		listOfNotNull(
			sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
			sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
			sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
		).forEach {
			sensorManager.registerListener(sensorEventListener, it, samplingPeriodUs)
		}
	}

	private fun stopMotion()
	{
		motionActive = false
		val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
		sensorManager.unregisterListener(sensorEventListener)
		controllerSensorManager?.unregisterListener(controllerSensorEventListener)
		controllerSensorManager = null
	}

	/**
	 * Picks the motion sensors of the controller in use, or of any connected controller.
	 * @return whether a controller with motion sensors was found
	 */
	private fun startControllerMotion(): Boolean
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
			return false
		val devices = (listOfNotNull(lastControllerDeviceId) + InputDevice.getDeviceIds().toList())
			.mapNotNull { InputDevice.getDevice(it) }
			.filter { !it.isVirtual }
		for(device in devices)
		{
			val sensorManager = device.sensorManager
			val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: continue
			val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: continue
			sensorManager.registerListener(controllerSensorEventListener, gyro, SensorManager.SENSOR_DELAY_FASTEST)
			sensorManager.registerListener(controllerSensorEventListener, accel, SensorManager.SENSOR_DELAY_FASTEST)
			controllerSensorManager = sensorManager
			return true
		}
		return false
	}

	/**
	 * Controllers were connected or disconnected, which may change the motion source.
	 */
	fun onInputDevicesChanged()
	{
		// A controller's id can belong to another controller after reconnecting
		inputProfiles.clear()
		if(!motionActive)
			return
		stopMotion()
		startMotion()
	}

	private val motionLifecycleObserver = object: LifecycleObserver {
		@OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
		fun onResume() = startMotion()

		@OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
		fun onPause() = stopMotion()
	}

	fun observe(lifecycleOwner: LifecycleOwner)
	{
		if(preferences.motionEnabled)
			lifecycleOwner.lifecycle.addObserver(motionLifecycleObserver)
	}

	/**
	 * Called when L1 + R1 + Options + Share are held together, so the stream can be left
	 * with nothing but a controller, e.g. on a TV box.
	 */
	var menuComboCallback: (() -> Unit)? = null
	private var menuComboActive = false

	/** Called on the main thread */
	private fun controllerStateUpdated()
	{
		val state = sendControllerState()
		val comboHeld = state.buttons and MENU_COMBO == MENU_COMBO
		val comboPressed = comboHeld && !menuComboActive
		menuComboActive = comboHeld
		// After sending, so the released buttons of the menu are the last state the console gets
		if(comboPressed)
			menuComboCallback?.invoke()
	}

	/**
	 * A controller's motion sensors report on a thread of their own, so the states are put
	 * together and sent one at a time: an older state arriving after a newer one would make
	 * the console see a button released and pressed again.
	 */
	private val sendLock = Any()

	private fun sendControllerState(): ControllerState = synchronized(sendLock) {
		controllerState.also { state -> controllerStateChangedCallback?.invoke(state) }
	}

	/**
	 * Releases all buttons and centers the sticks, for when the stream loses the input,
	 * so the console doesn't see buttons stuck in the pressed state.
	 */
	fun releaseAll()
	{
		keyControllerState.buttons = 0U
		keyControllerState.l2State = 0U
		keyControllerState.r2State = 0U
		motionControllerState.buttons = 0U
		motionControllerState.l2State = 0U
		motionControllerState.r2State = 0U
		motionControllerState.leftX = 0
		motionControllerState.leftY = 0
		motionControllerState.rightX = 0
		motionControllerState.rightY = 0
		touchpadTouches.values.forEach { touchpadControllerState.stopTouch(it) }
		touchpadTouches.clear()
		touchpadControllerState.buttons = 0U
		controllerStateUpdated()
	}

	private fun rememberController(event: InputEvent)
	{
		val source = event.source
		if(source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
			|| source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
			lastControllerDeviceId = event.deviceId
	}

	fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		//Log.i("StreamSession", "key event $event")
		if(event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)
			return false

		val keyCode = event.keyCode
		val action = event.action == KeyEvent.ACTION_DOWN
		val profile = inputProfile(event.deviceId)

		// Check for L2/R2 (can be digital or analog, here we handle digital key event)
		if (keyCode != 0 && keyCode == profile.keyL2) {
			keyControllerState.l2State = if(action) UByte.MAX_VALUE else 0U
			rememberController(event)
			controllerStateUpdated()
			return true
		}
		if (keyCode != 0 && keyCode == profile.keyR2) {
			keyControllerState.r2State = if(action) UByte.MAX_VALUE else 0U
			rememberController(event)
			controllerStateUpdated()
			return true
		}

		val buttonMask = profile.buttonMappings[keyCode] ?: return false

		keyControllerState.buttons = keyControllerState.buttons.run {
			if(action) this or buttonMask else this and buttonMask.inv()
		}

		rememberController(event)
		controllerStateUpdated()
		return true
	}

	/**
	 * Which axes a controller uses for the right stick and the triggers.
	 * Android does not report these consistently, e.g. Xbox controllers over Bluetooth
	 * send their triggers as AXIS_BRAKE/AXIS_GAS instead of AXIS_LTRIGGER/AXIS_RTRIGGER.
	 */
	private class AxisLayout(
		val rightX: Int,
		val rightY: Int,
		val leftTriggers: IntArray,
		val rightTriggers: IntArray
	)

	private val axisLayouts = mutableMapOf<Int, AxisLayout>()

	private fun axisLayout(device: InputDevice?): AxisLayout
	{
		if(device == null)
			return defaultAxisLayout
		return axisLayouts.getOrPut(device.id) {
			fun has(axis: Int) = device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null
			val hasZ = has(MotionEvent.AXIS_Z) && has(MotionEvent.AXIS_RZ)
			val hasRxRy = has(MotionEvent.AXIS_RX) && has(MotionEvent.AXIS_RY)
			val hasTriggerAxes = (has(MotionEvent.AXIS_LTRIGGER) && has(MotionEvent.AXIS_RTRIGGER))
					|| (has(MotionEvent.AXIS_BRAKE) && has(MotionEvent.AXIS_GAS))
			when
			{
				// No dedicated trigger axes: one of Z/RZ or RX/RY is the right stick, the other the triggers
				!hasTriggerAxes && hasZ && hasRxRy ->
					if(device.vendorId == VENDOR_ID_SONY)
						AxisLayout(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, intArrayOf(MotionEvent.AXIS_RX), intArrayOf(MotionEvent.AXIS_RY))
					else
						AxisLayout(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, intArrayOf(MotionEvent.AXIS_Z), intArrayOf(MotionEvent.AXIS_RZ))
				!hasZ && hasRxRy ->
					AxisLayout(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, defaultAxisLayout.leftTriggers, defaultAxisLayout.rightTriggers)
				else -> defaultAxisLayout
			}
		}
	}

	/**
	 * Drops cached information about a controller that was disconnected or reconfigured.
	 */
	fun forgetInputDevice(deviceId: Int)
	{
		axisLayouts.remove(deviceId)
		if(lastControllerDeviceId == deviceId)
			lastControllerDeviceId = null
	}

	// Android pointer id -> touch id in touchpadControllerState
	private val touchpadTouches = mutableMapOf<Int, UByte>()

	/**
	 * Touches and clicks on the controller's touchpad, which the PS Remote Play Orange Pi
	 * image reports as a touch navigation device (a mouse pointer by default).
	 */
	private fun onTouchpadEvent(event: MotionEvent): Boolean
	{
		val device = event.device
		fun scale(value: Float, axis: Int, size: UShort): UShort
		{
			val range = device?.getMotionRange(axis, event.source)
			val normalized = if(range != null && range.range > 0.0f) (value - range.min) / range.range else value / size.toFloat()
			return (normalized * (size.toFloat() - 1.0f)).coerceIn(0.0f, size.toFloat() - 1.0f).toUInt().toUShort()
		}
		fun x(index: Int) = scale(event.getX(index), MotionEvent.AXIS_X, ControllerState.TOUCHPAD_WIDTH)
		fun y(index: Int) = scale(event.getY(index), MotionEvent.AXIS_Y, ControllerState.TOUCHPAD_HEIGHT)

		val state = touchpadControllerState
		when(event.actionMasked)
		{
			MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
				val index = event.actionIndex
				state.startTouch(x(index), y(index))?.let { touchpadTouches[event.getPointerId(index)] = it }
			}
			MotionEvent.ACTION_MOVE ->
				for(index in 0 until event.pointerCount)
					touchpadTouches[event.getPointerId(index)]?.let { state.setTouchPos(it, x(index), y(index)) }
			MotionEvent.ACTION_POINTER_UP ->
				touchpadTouches.remove(event.getPointerId(event.actionIndex))?.let { state.stopTouch(it) }
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				touchpadTouches.values.forEach { state.stopTouch(it) }
				touchpadTouches.clear()
			}
		}
		val clicked = event.buttonState and MotionEvent.BUTTON_PRIMARY != 0
			&& event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
		state.buttons = if(clicked) ControllerState.BUTTON_TOUCHPAD else 0U
		controllerStateUpdated()
		return true
	}

	/**
	 * Without an input config for it, Android makes the touchpad of a DualSense/DualShock 4 a
	 * mouse pointer. Its clicks are then the touchpad button, and its pointer is ignored.
	 * @return whether the event came from such a touchpad
	 */
	/**
	 * A DualSense/DualShock 4 whose touchpad Android made a mouse pointer.
	 */
	fun isPointerTouchpadConnected() = InputDevice.getDeviceIds().any { id ->
		InputDevice.getDevice(id)?.let {
			it.vendorId == VENDOR_ID_SONY && it.sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE
		} ?: false
	}

	/**
	 * Events while the pointer is captured: a touchpad then reports absolute finger positions.
	 */
	fun onCapturedPointerEvent(event: MotionEvent): Boolean
	{
		if(!capturedEventLogged)
		{
			capturedEventLogged = true
			android.util.Log.i("StreamInput", "Captured pointer: source=0x${Integer.toHexString(event.source)} device=${event.device?.name}")
		}
		return if(event.isFromSource(InputDevice.SOURCE_TOUCHPAD) && event.device?.vendorId == VENDOR_ID_SONY)
			onTouchpadEvent(event)
		else
			true // a real mouse, which does nothing in the stream
	}

	private var capturedEventLogged = false

	fun onControllerPointerEvent(event: MotionEvent): Boolean
	{
		if(!event.isFromSource(InputDevice.SOURCE_MOUSE) || event.device?.vendorId != VENDOR_ID_SONY)
			return false
		val clicked = event.buttonState and MotionEvent.BUTTON_PRIMARY != 0
			&& event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
		val buttons = if(clicked) ControllerState.BUTTON_TOUCHPAD else 0U
		if(buttons != touchpadControllerState.buttons)
		{
			touchpadControllerState.buttons = buttons
			controllerStateUpdated()
		}
		return true
	}

	fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		if(event.isFromSource(InputDevice.SOURCE_TOUCH_NAVIGATION))
			return onTouchpadEvent(event)
		if(event.source and InputDevice.SOURCE_CLASS_JOYSTICK != InputDevice.SOURCE_CLASS_JOYSTICK)
			return false
		fun Float.signedAxis() = (this.coerceIn(-1.0f, 1.0f) * Short.MAX_VALUE).toInt().toShort()
		fun Float.unsignedAxis() = (this.coerceIn(0.0f, 1.0f) * UByte.MAX_VALUE.toFloat()).toUInt().toUByte()

		val device = event.device
		val layout = axisLayout(device)
		// Normalizes to 0..1 using the axis' reported range, since some triggers rest at -1 instead of 0
		fun triggerValue(axes: IntArray) = axes.maxOf { axis ->
			val value = event.getAxisValue(axis)
			val range = device?.getMotionRange(axis, event.source)
			if(range != null && range.min < 0.0f && range.range > 0.0f)
				(value - range.min) / range.range
			else
				value
		}

		// Ignores small movements around the center, scaling the rest to the whole range,
		// for sticks that drift
		val deadZone = inputProfile(event.deviceId).stickDeadZone
		fun stick(x: Float, y: Float): Pair<Float, Float>
		{
			if(deadZone <= 0f)
				return Pair(x, y)
			val magnitude = hypot(x, y)
			if(magnitude <= deadZone)
				return Pair(0f, 0f)
			val scale = ((magnitude - deadZone) / (1f - deadZone)).coerceAtMost(1f) / magnitude
			return Pair(x * scale, y * scale)
		}
		val (leftX, leftY) = stick(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))
		val (rightX, rightY) = stick(event.getAxisValue(layout.rightX), event.getAxisValue(layout.rightY))
		motionControllerState.leftX = leftX.signedAxis()
		motionControllerState.leftY = leftY.signedAxis()
		motionControllerState.rightX = rightX.signedAxis()
		motionControllerState.rightY = rightY.signedAxis()
		motionControllerState.l2State = triggerValue(layout.leftTriggers).unsignedAxis()
		motionControllerState.r2State = triggerValue(layout.rightTriggers).unsignedAxis()
		motionControllerState.buttons = motionControllerState.buttons.let {
			val dpadX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
			val dpadY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
			val dpadButtons =
				(if(dpadX > 0.5f) ControllerState.BUTTON_DPAD_RIGHT else 0U) or
						(if(dpadX < -0.5f) ControllerState.BUTTON_DPAD_LEFT else 0U) or
						(if(dpadY > 0.5f) ControllerState.BUTTON_DPAD_DOWN else 0U) or
						(if(dpadY < -0.5f) ControllerState.BUTTON_DPAD_UP else 0U)
			it and (ControllerState.BUTTON_DPAD_RIGHT or
					ControllerState.BUTTON_DPAD_LEFT or
					ControllerState.BUTTON_DPAD_DOWN or
					ControllerState.BUTTON_DPAD_UP).inv() or
					dpadButtons
		}
		//Log.i("StreamSession", "motionEvent => $motionControllerState")
		rememberController(event)
		controllerStateUpdated()
		return true
	}

	companion object
	{
		private const val VENDOR_ID_SONY = 0x054c

		private val MENU_COMBO = ControllerState.BUTTON_L1 or ControllerState.BUTTON_R1 or
				ControllerState.BUTTON_OPTIONS or ControllerState.BUTTON_SHARE

		private val defaultAxisLayout = AxisLayout(
			MotionEvent.AXIS_Z,
			MotionEvent.AXIS_RZ,
			intArrayOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE),
			intArrayOf(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
		)
	}
}
