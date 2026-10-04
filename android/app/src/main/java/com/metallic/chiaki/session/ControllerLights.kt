// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.graphics.Color
import android.hardware.lights.Light
import android.hardware.lights.LightState
import android.hardware.lights.LightsManager
import android.hardware.lights.LightsRequest
import android.os.Build
import android.view.InputDevice
import androidx.annotation.RequiresApi

/**
 * The lightbar of PlayStation controllers through Android's lights API (Android 12+). It works on
 * any device whose kernel driver exposes the lightbar, unlike [DualSenseFeedback]'s raw reports,
 * which need access to hidraw. Closing gives the lights back to the system.
 */
@RequiresApi(Build.VERSION_CODES.S)
class ControllerLights
{
	companion object
	{
		private const val VENDOR_ID_SONY = 0x054c
	}

	private val sessions = mutableMapOf<Int, LightsManager.LightsSession>()
	private var color: Int? = null

	fun setColor(red: Int, green: Int, blue: Int)
	{
		color = Color.rgb(red, green, blue)
		apply()
	}

	/** For controllers connected since the color was set */
	fun rescan() = apply()

	private fun apply()
	{
		val color = color ?: return
		for(id in InputDevice.getDeviceIds())
		{
			val device = InputDevice.getDevice(id) ?: continue
			if(device.isVirtual || device.vendorId != VENDOR_ID_SONY)
				continue
			val lightbars = device.lightsManager.lights.filter { it.type == Light.LIGHT_TYPE_INPUT && it.hasRgbControl() }
			if(lightbars.isEmpty())
				continue
			val request = LightsRequest.Builder().also { builder ->
				lightbars.forEach { builder.addLight(it, LightState.Builder().setColor(color).build()) }
			}.build()
			try
			{
				sessions.getOrPut(id) { device.lightsManager.openSession() }.requestLights(request)
			}
			catch(e: RuntimeException)
			{
				// The controller is gone meanwhile
				sessions.remove(id)?.let { closeQuietly(it) }
			}
		}
	}

	fun close()
	{
		sessions.values.forEach { closeQuietly(it) }
		sessions.clear()
		color = null
	}

	private fun closeQuietly(session: LightsManager.LightsSession)
	{
		try
		{
			session.close()
		}
		catch(e: RuntimeException) {}
	}
}
