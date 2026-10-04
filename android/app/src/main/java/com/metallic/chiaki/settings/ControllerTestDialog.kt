// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Window
import android.widget.GridLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.ControllerProfiles
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.databinding.DialogControllerTestBinding
import com.metallic.chiaki.lib.ControllerState
import com.metallic.chiaki.session.StreamInput

/**
 * Shows what the console would receive from a controller, with the same handling as a stream:
 * its mapping, swapped face buttons and dead zone.
 * @param descriptor the controller to show, or null for any controller
 */
fun showControllerTest(context: Context, descriptor: String?, name: String)
{
	val binding = DialogControllerTestBinding.inflate(LayoutInflater.from(context))
	val input = StreamInput(context, Preferences(context))

	val buttons = listOf(
		R.string.button_cross to ControllerState.BUTTON_CROSS,
		R.string.button_circle to ControllerState.BUTTON_MOON,
		R.string.button_square to ControllerState.BUTTON_BOX,
		R.string.button_triangle to ControllerState.BUTTON_PYRAMID,
		R.string.button_l1 to ControllerState.BUTTON_L1,
		R.string.button_r1 to ControllerState.BUTTON_R1,
		R.string.key_dpad_up to ControllerState.BUTTON_DPAD_UP,
		R.string.key_dpad_down to ControllerState.BUTTON_DPAD_DOWN,
		R.string.key_dpad_left to ControllerState.BUTTON_DPAD_LEFT,
		R.string.key_dpad_right to ControllerState.BUTTON_DPAD_RIGHT,
		R.string.button_options to ControllerState.BUTTON_OPTIONS,
		R.string.button_share to ControllerState.BUTTON_SHARE,
		R.string.button_l3 to ControllerState.BUTTON_L3,
		R.string.button_r3 to ControllerState.BUTTON_R3,
		R.string.button_ps to ControllerState.BUTTON_PS,
		R.string.button_touchpad to ControllerState.BUTTON_TOUCHPAD
	)
	val idleBackground = ColorStateList.valueOf(MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceContainerHighest))
	val pressedBackground = ColorStateList.valueOf(MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary))
	val idleText = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
	val pressedText = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimary)
	val margin = (3 * context.resources.displayMetrics.density).toInt()
	val padding = (6 * context.resources.displayMetrics.density).toInt()
	// The D-pad's and long names take two columns
	val chips = buttons.map { (title, mask) ->
		val wide = mask in listOf(ControllerState.BUTTON_L3, ControllerState.BUTTON_R3,
			ControllerState.BUTTON_TOUCHPAD, ControllerState.BUTTON_SHARE) ||
			title in listOf(R.string.key_dpad_up, R.string.key_dpad_down, R.string.key_dpad_left, R.string.key_dpad_right)
		val chip = TextView(context).apply {
			setText(title)
			gravity = Gravity.CENTER
			maxLines = 1
			setPadding(padding, padding, padding, padding)
			setBackgroundResource(R.drawable.controller_test_chip)
			backgroundTintList = idleBackground
			setTextColor(idleText)
			textSize = 12f
		}
		binding.buttonsGrid.addView(chip, GridLayout.LayoutParams(
			GridLayout.spec(GridLayout.UNDEFINED),
			GridLayout.spec(GridLayout.UNDEFINED, if(wide) 2 else 1, 1f)
		).apply {
			width = 0
			setMargins(margin, margin, margin, margin)
		})
		mask to chip
	}

	val deadZone = ControllerProfiles(context).profile(descriptor).stickDeadZone / 100f
	binding.leftStickView.deadZone = deadZone
	binding.rightStickView.deadZone = deadZone

	input.controllerStateChangedCallback = { state ->
		binding.root.post {
			chips.forEach { (mask, chip) ->
				val pressed = state.buttons and mask != 0U
				chip.backgroundTintList = if(pressed) pressedBackground else idleBackground
				chip.setTextColor(if(pressed) pressedText else idleText)
			}
			binding.leftStickView.stickX = state.leftX / Short.MAX_VALUE.toFloat()
			binding.leftStickView.stickY = state.leftY / Short.MAX_VALUE.toFloat()
			binding.rightStickView.stickX = state.rightX / Short.MAX_VALUE.toFloat()
			binding.rightStickView.stickY = state.rightY / Short.MAX_VALUE.toFloat()
			binding.l2Indicator.progress = state.l2State.toInt()
			binding.r2Indicator.progress = state.r2State.toInt()
		}
	}

	val dialog = MaterialAlertDialogBuilder(context)
		.setTitle(name)
		.setView(binding.root)
		.setPositiveButton(R.string.action_done, null)
		.create()
	input.menuComboCallback = { dialog.dismiss() }

	// The controller's input goes to the test instead of the dialog's buttons; Back still closes it
	val window = dialog.window ?: return
	val callback = window.callback
	fun fromController(device: android.view.InputDevice?) = descriptor == null || device?.descriptor == descriptor
	window.callback = object: Window.Callback by callback
	{
		override fun dispatchKeyEvent(event: KeyEvent) =
			(event.keyCode != KeyEvent.KEYCODE_BACK && fromController(event.device) && input.dispatchKeyEvent(event))
				|| callback.dispatchKeyEvent(event)

		override fun dispatchGenericMotionEvent(event: MotionEvent) =
			(fromController(event.device) && input.onGenericMotionEvent(event)) || callback.dispatchGenericMotionEvent(event)
	}
	dialog.show()
}
