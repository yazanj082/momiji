// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.CombinedVibration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import androidx.annotation.RequiresApi
import kotlin.math.min
import com.metallic.chiaki.R
import java.util.Locale

/**
 * Plays the console's rumble on the controller that is being used.
 * Falls back to the phone's vibration motor only when no physical controller is in use.
 */
class ControllerRumble(private val context: Context)
{
	companion object
	{
		// Effects are held until the console sends the next rumble state
		private const val HOLD_DURATION_MS = 60000L
		private const val PWM_PERIOD_MS = 20L

		// Android 12's input system can corrupt its memory, restarting all of Android, when a
		// controller's vibration changes while it reads the controller's input, so change it rarely.
		private const val MIN_UPDATE_INTERVAL_MS = 100L
		private const val LEVELS = 8

		private const val PHONE_DEVICE_ID = -1

		private fun quantize(amplitude: Int) = (amplitude.coerceIn(0, 255) * LEVELS + 127) / 255 * 255 / LEVELS
	}

	enum class Result
	{
		OFF,
		CONTROLLER,
		PHONE,
		/** A controller is in use, but Android does not expose vibration motors for it */
		CONTROLLER_CANNOT_VIBRATE
	}

	private data class Vibration(val deviceId: Int, val left: Int, val right: Int)

	private val stopActions = mutableListOf<() -> Unit>()
	private val handler = Handler(Looper.getMainLooper())
	private var current: Vibration? = null
	private var pending: Vibration? = null
	private var pendingPosted = false
	private var lastUpdateMs = 0L
	private val applyPendingRunnable = Runnable { applyPending() }

	/**
	 * @param controllerDeviceId the controller that last sent input, or null if only touch controls are used
	 * @param left strong/low frequency motor, 0-255
	 * @param right weak/high frequency motor, 0-255
	 */
	fun rumble(controllerDeviceId: Int?, left: Int, right: Int): Result
	{
		val quantizedLeft = quantize(left)
		val quantizedRight = quantize(right)
		if(quantizedLeft == 0 && quantizedRight == 0)
		{
			request(null)
			return Result.OFF
		}

		val controller = controllerDeviceId?.let { InputDevice.getDevice(it) }
		if(controller != null || anyControllerConnected())
		{
			val target = controller?.takeIf { hasVibrator(it) } ?: findControllerWithVibrator()
			if(target == null)
			{
				request(null)
				return Result.CONTROLLER_CANNOT_VIBRATE
			}
			request(Vibration(target.id, quantizedLeft, quantizedRight))
			return Result.CONTROLLER
		}
		request(Vibration(PHONE_DEVICE_ID, quantizedLeft, quantizedRight))
		return Result.PHONE
	}

	private fun request(vibration: Vibration?)
	{
		pending = vibration
		if(pendingPosted)
			return
		val waitMs = lastUpdateMs + MIN_UPDATE_INTERVAL_MS - SystemClock.uptimeMillis()
		if(waitMs <= 0)
			applyPending()
		else
		{
			pendingPosted = true
			handler.postDelayed(applyPendingRunnable, waitMs)
		}
	}

	private fun applyPending()
	{
		pendingPosted = false
		val vibration = pending
		if(vibration == current)
			return
		lastUpdateMs = SystemClock.uptimeMillis()
		// A new vibration replaces the running one of the same device, without cancelling it first
		if(vibration?.deviceId != current?.deviceId)
			cancelVibration()
		else
			stopActions.clear()
		current = vibration
		if(vibration == null)
			return
		if(vibration.deviceId == PHONE_DEVICE_ID)
			rumblePhone(vibration.left, vibration.right)
		else
			InputDevice.getDevice(vibration.deviceId)?.let { rumbleController(it, vibration.left, vibration.right) }
	}

	fun controllerName(deviceId: Int?) =
		deviceId?.let { InputDevice.getDevice(it)?.name } ?: connectedControllers().firstOrNull()?.name

	class ControllerInfo(val name: String, val vendorId: Int, val productId: Int, val motors: Int)
	{
		fun describe(context: Context): String
		{
			val support = if(motors == 0)
				context.getString(R.string.test_rumble_no_motors)
			else
				context.resources.getQuantityString(R.plurals.test_rumble_motors, motors, motors)
			return "$name (${String.format(Locale.ROOT, "%04x:%04x", vendorId, productId)}): $support"
		}
	}

	/**
	 * Vibrates every connected controller that supports it at full strength for [durationMs].
	 * @return the connected controllers and their vibration support
	 */
	fun testControllers(durationMs: Long): List<ControllerInfo>
	{
		stop()
		val controllers = connectedControllers().toList()
		controllers.filter { hasVibrator(it) }.forEach { rumbleController(it, 255, 255) }
		handler.postDelayed({ stop() }, durationMs)
		return controllers.map { ControllerInfo(it.name, it.vendorId, it.productId, motorCount(it)) }
	}

	fun stop()
	{
		handler.removeCallbacks(applyPendingRunnable)
		pendingPosted = false
		pending = null
		current = null
		cancelVibration()
	}

	private fun cancelVibration()
	{
		stopActions.forEach { it() }
		stopActions.clear()
	}

	private fun isController(device: InputDevice) =
		!device.isVirtual && (device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
			|| device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)

	private fun connectedControllers() =
		InputDevice.getDeviceIds().asSequence().mapNotNull { InputDevice.getDevice(it) }.filter { isController(it) }

	private fun anyControllerConnected() = connectedControllers().any()

	private fun findControllerWithVibrator() = connectedControllers().firstOrNull { hasVibrator(it) }

	private fun motorCount(device: InputDevice) =
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
			device.vibratorManager.vibratorIds.size
		else
			@Suppress("DEPRECATION")
			if(device.vibrator.hasVibrator()) 1 else 0

	private fun hasVibrator(device: InputDevice) = motorCount(device) > 0

	private fun rumbleController(device: InputDevice, left: Int, right: Int)
	{
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
		{
			val manager = device.vibratorManager
			val ids = manager.vibratorIds
			if(ids.size >= 2 && ids.take(2).all { manager.getVibrator(it).hasAmplitudeControl() })
			{
				rumbleDualMotor(manager, left, right)
				return
			}
			val vibrator = manager.defaultVibrator
			rumbleSingleMotor(vibrator, left, right)
			return
		}
		@Suppress("DEPRECATION")
		rumbleSingleMotor(device.vibrator, left, right)
	}

	private fun rumblePhone(left: Int, right: Int)
	{
		val vibrator = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
			(context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
		else
			@Suppress("DEPRECATION")
			context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
		if(vibrator.hasVibrator())
			rumbleSingleMotor(vibrator, left, right)
	}

	@RequiresApi(Build.VERSION_CODES.S)
	private fun rumbleDualMotor(manager: VibratorManager, left: Int, right: Int)
	{
		// Rumble controllers list the weak (high frequency) motor first, then the strong one
		val ids = manager.vibratorIds
		val amplitudes = intArrayOf(right, left)
		val combination = CombinedVibration.startParallel()
		for(i in 0 until 2)
		{
			// Amplitude 0 is not allowed; leaving a motor out turns it off
			if(amplitudes[i] > 0)
				combination.addVibrator(ids[i], VibrationEffect.createOneShot(HOLD_DURATION_MS, min(255, amplitudes[i])))
		}
		val attributes = VibrationAttributes.Builder()
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
			attributes.setUsage(VibrationAttributes.USAGE_MEDIA)
		manager.vibrate(combination.combine(), attributes.build())
		stopActions.add { manager.cancel() }
	}

	private fun rumbleSingleMotor(vibrator: Vibrator, left: Int, right: Int)
	{
		// One motor has to represent both: weigh the strong motor more than the weak one
		val amplitude = min(255, (left * 0.8 + right * 0.33).toInt())
		if(amplitude == 0)
			return
		stopActions.add { vibrator.cancel() }

		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl())
		{
			vibrate(vibrator, VibrationEffect.createOneShot(HOLD_DURATION_MS, amplitude))
			return
		}

		// No amplitude control: approximate the strength by pulsing the motor
		val onTime = (amplitude / 255.0 * PWM_PERIOD_MS).toLong().coerceAtLeast(1)
		val offTime = PWM_PERIOD_MS - onTime
		val pattern = longArrayOf(0, onTime, offTime)
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			vibrate(vibrator, VibrationEffect.createWaveform(pattern, 0))
		else
			@Suppress("DEPRECATION")
			vibrator.vibrate(pattern, 0)
	}

	@RequiresApi(Build.VERSION_CODES.O)
	private fun vibrate(vibrator: Vibrator, effect: VibrationEffect)
	{
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
			vibrator.vibrate(effect, VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_MEDIA).build())
		else
			@Suppress("DEPRECATION")
			vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
	}
}
