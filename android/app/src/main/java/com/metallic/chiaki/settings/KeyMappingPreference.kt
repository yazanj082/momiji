// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R

/**
 * Which key of a controller a PlayStation button comes from, stored as the key code in a string,
 * 0 for none. Set by pressing the key.
 */
class KeyMappingPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs)
{
	constructor(context: Context) : this(context, null)

	companion object
	{
		/** A name players recognize for a controller's key */
		fun keyName(context: Context, keyCode: Int): String = when(keyCode)
		{
			0 -> context.getString(R.string.key_none)
			KeyEvent.KEYCODE_BUTTON_A -> "A"
			KeyEvent.KEYCODE_BUTTON_B -> "B"
			KeyEvent.KEYCODE_BUTTON_X -> "X"
			KeyEvent.KEYCODE_BUTTON_Y -> "Y"
			KeyEvent.KEYCODE_BUTTON_L1 -> context.getString(R.string.key_l1)
			KeyEvent.KEYCODE_BUTTON_R1 -> context.getString(R.string.key_r1)
			KeyEvent.KEYCODE_BUTTON_L2 -> context.getString(R.string.key_l2)
			KeyEvent.KEYCODE_BUTTON_R2 -> context.getString(R.string.key_r2)
			KeyEvent.KEYCODE_BUTTON_THUMBL -> context.getString(R.string.key_thumbl)
			KeyEvent.KEYCODE_BUTTON_THUMBR -> context.getString(R.string.key_thumbr)
			KeyEvent.KEYCODE_BUTTON_START -> context.getString(R.string.key_start)
			KeyEvent.KEYCODE_BUTTON_SELECT -> context.getString(R.string.key_select)
			KeyEvent.KEYCODE_BUTTON_MODE -> context.getString(R.string.key_mode)
			KeyEvent.KEYCODE_MEDIA_RECORD -> context.getString(R.string.key_record)
			KeyEvent.KEYCODE_DPAD_UP -> context.getString(R.string.key_dpad_up)
			KeyEvent.KEYCODE_DPAD_DOWN -> context.getString(R.string.key_dpad_down)
			KeyEvent.KEYCODE_DPAD_LEFT -> context.getString(R.string.key_dpad_left)
			KeyEvent.KEYCODE_DPAD_RIGHT -> context.getString(R.string.key_dpad_right)
			// For example BUTTON_C or a keyboard's keys: "Button c", "Space"
			else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ')
				.lowercase().replaceFirstChar { it.uppercase() }
		}
	}

	override fun onClick()
	{
		val dialog = MaterialAlertDialogBuilder(context)
			.setTitle(title)
			.setMessage(context.getString(R.string.key_mapping_prompt, title))
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.action_clear) { _, _ -> setKeyCode(0) }
			.create()
		dialog.setOnKeyListener { d, keyCode, event ->
			// Back stays for leaving the dialog
			if(keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)
				return@setOnKeyListener false
			if(event.action == KeyEvent.ACTION_DOWN)
			{
				setKeyCode(keyCode)
				d.dismiss()
			}
			true
		}
		dialog.show()
	}

	private fun setKeyCode(keyCode: Int)
	{
		if(callChangeListener(keyCode.toString()))
		{
			persistString(keyCode.toString())
			summary = keyName(context, keyCode)
		}
	}

	override fun onSetInitialValue(defaultValue: Any?)
	{
		super.onSetInitialValue(defaultValue)
		val value = getPersistedString(defaultValue as? String ?: "0")
		summary = keyName(context, value.toIntOrNull() ?: 0)
	}
}
