// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.metallic.chiaki.R
import com.metallic.chiaki.databinding.SecondScreenBinding
import com.metallic.chiaki.lib.ControllerState
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo

/**
 * The second screen of dual-screen handhelds like the AYN Thor, which Android sees as an external
 * display. Emulators for two-screen consoles draw on it the same way, with a [Presentation].
 */
object SecondScreen
{
	// Values of the hidden Display.getType()
	private const val TYPE_WIFI = 3
	private const val TYPE_VIRTUAL = 5

	/** Android's type of the display, null if it doesn't say */
	private fun type(display: Display): Int? = try
	{
		Display::class.java.getMethod("getType").invoke(display) as? Int
	}
	catch(e: Exception)
	{
		null
	}

	/**
	 * A display for presentations that is on, other than the one the stream is on. Wireless and
	 * virtual displays are left out: those mirror the phone, for example to a TV.
	 */
	fun find(context: Context, currentDisplayId: Int): Display? =
		context.getSystemService(DisplayManager::class.java)
			?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
			?.firstOrNull { it.displayId != currentDisplayId && it.state == Display.STATE_ON && type(it) != TYPE_WIFI && type(it) != TYPE_VIRTUAL }

	/** For the log, in case a device's second screen behaves differently */
	fun describe(display: Display) = "display ${display.displayId} \"${display.name}\", type ${type(display)}, flags ${display.flags}"
}

/**
 * The DualSense touchpad and the buttons around it on the second screen, while the game plays on
 * the main one. It takes touches but never the focus, so the controller keeps playing on the main
 * screen; any key that reaches it anyway goes to the stream.
 */
class SecondScreenPresentation(
	context: Context,
	display: Display,
	private val ps5: Boolean,
	private val stateCallback: (ControllerState) -> Unit,
	private val menuCallback: () -> Unit,
	private val keyCallback: (KeyEvent) -> Boolean
): Presentation(context, display, R.style.AppTheme)
{
	private val disposable = CompositeDisposable()
	private var touchpadState = ControllerState()
	private var buttons = 0U

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
		setCancelable(false)
		val binding = SecondScreenBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.touchpad.controllerState
			.subscribe {
				touchpadState = it
				update()
			}
			.addTo(disposable)
		binding.createButton.setText(if(ps5) R.string.second_screen_create else R.string.second_screen_share)
		hold(binding.createButton, ControllerState.BUTTON_SHARE)
		hold(binding.psButton, ControllerState.BUTTON_PS)
		hold(binding.optionsButton, ControllerState.BUTTON_OPTIONS)
		binding.menuButton.setOnClickListener { menuCallback() }
	}

	/** Pressed for as long as the finger is on it, like a controller's button */
	@SuppressLint("ClickableViewAccessibility")
	private fun hold(view: View, button: UInt)
	{
		view.setOnTouchListener { _, event ->
			when(event.actionMasked)
			{
				MotionEvent.ACTION_DOWN -> {
					buttons = buttons or button
					update()
				}
				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					buttons = buttons and button.inv()
					update()
				}
			}
			// The button still shows its ripple
			false
		}
	}

	private fun update() = stateCallback(ControllerState(buttons = buttons) or touchpadState)

	override fun dispatchKeyEvent(event: KeyEvent) = keyCallback(event)

	override fun onStop()
	{
		super.onStop()
		disposable.clear()
		buttons = 0U
		touchpadState = ControllerState()
		update()
	}
}
