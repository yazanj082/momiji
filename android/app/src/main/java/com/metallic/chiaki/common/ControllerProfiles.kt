// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.metallic.chiaki.R

/**
 * Settings that can differ per controller: the button mapping, swapping the face buttons and the
 * sticks' dead zone. A controller is known by its InputDevice descriptor, which stays the same when
 * it's connected again. What a controller doesn't set itself comes from the default settings, which
 * use the keys that the mapping had before there were per-controller settings.
 */
class ControllerProfiles(context: Context)
{
	enum class Button(val key: String, @StringRes val title: Int, val defaultKeyCode: Int)
	{
		CROSS("cross", R.string.button_cross, KeyEvent.KEYCODE_BUTTON_A),
		CIRCLE("circle", R.string.button_circle, KeyEvent.KEYCODE_BUTTON_B),
		SQUARE("square", R.string.button_square, KeyEvent.KEYCODE_BUTTON_X),
		TRIANGLE("triangle", R.string.button_triangle, KeyEvent.KEYCODE_BUTTON_Y),
		L1("l1", R.string.button_l1, KeyEvent.KEYCODE_BUTTON_L1),
		R1("r1", R.string.button_r1, KeyEvent.KEYCODE_BUTTON_R1),
		L2("l2", R.string.button_l2, KeyEvent.KEYCODE_BUTTON_L2),
		R2("r2", R.string.button_r2, KeyEvent.KEYCODE_BUTTON_R2),
		L3("l3", R.string.button_l3, KeyEvent.KEYCODE_BUTTON_THUMBL),
		R3("r3", R.string.button_r3, KeyEvent.KEYCODE_BUTTON_THUMBR),
		OPTIONS("options", R.string.button_options, KeyEvent.KEYCODE_BUTTON_START),
		SHARE("share", R.string.button_share, KeyEvent.KEYCODE_BUTTON_SELECT),
		PS("ps", R.string.button_ps, KeyEvent.KEYCODE_BUTTON_MODE),
		// The Share button of Xbox Series controllers
		TOUCHPAD("touchpad", R.string.button_touchpad, KeyEvent.KEYCODE_MEDIA_RECORD)
	}

	class Profile(
		/** Key code for each button, 0 for none */
		val keyCodes: Map<Button, Int>,
		val swapFaceButtons: Boolean,
		/** Percent of the stick's range in which movement is ignored */
		val stickDeadZone: Int
	)

	companion object
	{
		private const val CONTROLLER_PREFIX = "controller/"
		private const val NAME_KEY = "name"
		private const val SWAP_FACE_BUTTONS_KEY = "swap_cross_moon"
		private const val STICK_DEAD_ZONE_KEY = "stick_dead_zone"
		const val STICK_DEAD_ZONE_MAX = 30

		/** Key of a setting for the controller, or for the defaults if descriptor is null */
		fun key(descriptor: String?, setting: String) =
			if(descriptor == null) setting else "$CONTROLLER_PREFIX$descriptor/$setting"

		fun mappingKey(descriptor: String?, button: Button) = key(descriptor, "mapping_${button.key}")
		fun swapFaceButtonsKey(descriptor: String?) = key(descriptor, SWAP_FACE_BUTTONS_KEY)
		fun stickDeadZoneKey(descriptor: String?) = key(descriptor, STICK_DEAD_ZONE_KEY)

		/**
		 * Whether the device is a game controller: one with sticks, or an external one with game
		 * buttons. TVs have built-in key devices that also claim game buttons, such as for search.
		 */
		fun isGameController(device: InputDevice): Boolean
		{
			if(device.isVirtual)
				return false
			if(device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
				return true
			return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && device.isExternal
					&& device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
		}

		/** Game controllers that are connected now */
		fun connectedControllers(): List<InputDevice> = InputDevice.getDeviceIds().toList()
			.mapNotNull { InputDevice.getDevice(it) }
			.filter { isGameController(it) }
			// A controller can show up as several devices, for example its touchpad as another one
			.distinctBy { it.descriptor }
	}

	private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

	private fun mapping(descriptor: String?, button: Button): Int? =
		sharedPreferences.getString(mappingKey(descriptor, button), null)?.toIntOrNull()

	fun profile(descriptor: String?): Profile
	{
		val keyCodes = Button.values().associateWith { button ->
			descriptor?.let { mapping(it, button) } ?: mapping(null, button) ?: button.defaultKeyCode
		}
		val swapKey = swapFaceButtonsKey(descriptor)
		val swap = if(descriptor != null && sharedPreferences.contains(swapKey))
			sharedPreferences.getBoolean(swapKey, false)
		else
			sharedPreferences.getBoolean(swapFaceButtonsKey(null), false)
		val deadZoneKey = stickDeadZoneKey(descriptor)
		val deadZone = if(descriptor != null && sharedPreferences.contains(deadZoneKey))
			sharedPreferences.getInt(deadZoneKey, 0)
		else
			sharedPreferences.getInt(stickDeadZoneKey(null), 0)
		return Profile(keyCodes, swap, deadZone.coerceIn(0, STICK_DEAD_ZONE_MAX))
	}

	fun hasOwnSettings(descriptor: String) = sharedPreferences.all.keys.any {
		it.startsWith(key(descriptor, "")) && it != key(descriptor, NAME_KEY)
	}

	/** So that controllers with their own settings can be listed while they aren't connected */
	fun rememberName(descriptor: String, name: String) = sharedPreferences.edit {
		putString(key(descriptor, NAME_KEY), name)
	}

	/** Controllers that have their own settings, by descriptor, with their names */
	fun controllersWithOwnSettings(): Map<String, String> = sharedPreferences.all.keys
		.filter { it.startsWith(CONTROLLER_PREFIX) }
		.map { it.removePrefix(CONTROLLER_PREFIX).substringBefore('/') }
		.distinct()
		.filter { hasOwnSettings(it) }
		.associateWith { sharedPreferences.getString(key(it, NAME_KEY), null) ?: it.take(8) }

	/** The controller goes back to the default settings */
	fun reset(descriptor: String) = sharedPreferences.edit {
		sharedPreferences.all.keys.filter { it.startsWith(key(descriptor, "")) }.forEach { remove(it) }
	}
}
