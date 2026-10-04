// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.Context
import android.content.res.Resources
import android.hardware.input.InputManager
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.metallic.chiaki.R
import com.metallic.chiaki.common.ControllerProfiles

/** The connected controllers, controllers with their own settings, and the default settings */
class SettingsControllersFragment: PreferenceFragmentCompat(), TitleFragment, InputManager.InputDeviceListener
{
	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
	{
		preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
	}

	override fun onResume()
	{
		super.onResume()
		// Also after coming back from a controller's settings, which may have changed
		update()
		inputManager().registerInputDeviceListener(this, null)
	}

	override fun onPause()
	{
		super.onPause()
		inputManager().unregisterInputDeviceListener(this)
	}

	private fun inputManager() = requireContext().getSystemService(Context.INPUT_SERVICE) as InputManager

	override fun onInputDeviceAdded(deviceId: Int) = update()
	override fun onInputDeviceRemoved(deviceId: Int) = update()
	override fun onInputDeviceChanged(deviceId: Int) = update()

	private fun update()
	{
		val context = context ?: return
		val screen = preferenceScreen ?: return
		screen.removeAll()
		val profiles = ControllerProfiles(context)

		val connectedCategory = PreferenceCategory(context).apply {
			title = getString(R.string.controllers_connected)
		}
		screen.addPreference(connectedCategory)
		val connected = ControllerProfiles.connectedControllers()
		if(connected.isEmpty())
			connectedCategory.addPreference(Preference(context).apply {
				title = getString(R.string.controllers_none)
				isSelectable = false
				setIcon(R.drawable.ic_gamepad)
			})
		connected.forEach {
			connectedCategory.addPreference(controllerPreference(context, it.descriptor, it.name, profiles.hasOwnSettings(it.descriptor)))
		}

		val others = profiles.controllersWithOwnSettings() - connected.map { it.descriptor }.toSet()
		if(others.isNotEmpty())
		{
			val othersCategory = PreferenceCategory(context).apply {
				title = getString(R.string.controllers_other)
			}
			screen.addPreference(othersCategory)
			others.forEach { (descriptor, name) -> othersCategory.addPreference(controllerPreference(context, descriptor, name, true)) }
		}

		val defaultsCategory = PreferenceCategory(context)
		screen.addPreference(defaultsCategory)
		defaultsCategory.addPreference(Preference(context).apply {
			title = getString(R.string.controllers_defaults)
			summary = getString(R.string.controllers_defaults_summary)
			fragment = SettingsControllerFragment::class.java.canonicalName
			setIcon(R.drawable.ic_settings)
		})
	}

	private fun controllerPreference(context: Context, descriptor: String, name: String, ownSettings: Boolean) = Preference(context).apply {
		title = name
		summary = getString(if(ownSettings) R.string.controller_own_settings else R.string.controller_default_settings)
		fragment = SettingsControllerFragment::class.java.canonicalName
		setIcon(R.drawable.ic_gamepad)
		extras.putString(SettingsControllerFragment.ARG_DESCRIPTOR, descriptor)
		extras.putString(SettingsControllerFragment.ARG_NAME, name)
	}

	override fun getTitle(resources: Resources): String = resources.getString(R.string.preferences_controllers_title)
}
