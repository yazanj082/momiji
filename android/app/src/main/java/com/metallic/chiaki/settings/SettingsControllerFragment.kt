// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.res.Resources
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.ControllerProfiles

/**
 * Settings of one controller, or the default settings without a descriptor. A controller's settings
 * show the defaults until they are changed, and only the changed ones are stored for it.
 */
class SettingsControllerFragment: PreferenceFragmentCompat(), TitleFragment
{
	companion object
	{
		const val ARG_DESCRIPTOR = "descriptor"
		const val ARG_NAME = "name"
	}

	private val descriptor get() = arguments?.getString(ARG_DESCRIPTOR)
	private val name get() = arguments?.getString(ARG_NAME)

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
	{
		preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
		build()
	}

	private fun build()
	{
		val context = requireContext()
		val screen = preferenceScreen
		screen.removeAll()
		val descriptor = descriptor
		val profiles = ControllerProfiles(context)
		// A controller's settings are the defaults until they're changed
		val defaults = profiles.profile(null)
		val rememberName = Preference.OnPreferenceChangeListener { _, _ ->
			descriptor?.let { profiles.rememberName(it, name ?: it) }
			true
		}

		screen.addPreference(Preference(context).apply {
			title = getString(R.string.controller_test)
			summary = getString(R.string.controller_test_summary)
			setIcon(R.drawable.ic_gamepad)
			setOnPreferenceClickListener {
				showControllerTest(context, descriptor, name ?: getString(R.string.controllers_defaults))
				true
			}
		})

		screen.addPreference(SwitchPreference(context).apply {
			key = ControllerProfiles.swapFaceButtonsKey(descriptor)
			title = getString(R.string.preferences_swap_cross_moon_title)
			summary = getString(R.string.preferences_swap_cross_moon_summary)
			setIcon(R.drawable.ic_gamepad)
			setDefaultValue(if(descriptor == null) false else defaults.swapFaceButtons)
			onPreferenceChangeListener = rememberName
		})

		screen.addPreference(SeekBarPreference(context).apply {
			key = ControllerProfiles.stickDeadZoneKey(descriptor)
			title = getString(R.string.stick_dead_zone_title)
			summary = getString(R.string.stick_dead_zone_summary)
			setIcon(R.drawable.ic_gamepad)
			min = 0
			max = ControllerProfiles.STICK_DEAD_ZONE_MAX
			showSeekBarValue = true
			setDefaultValue(if(descriptor == null) 0 else defaults.stickDeadZone)
			onPreferenceChangeListener = rememberName
		})

		val buttons = PreferenceCategory(context).apply {
			title = getString(R.string.controller_buttons)
		}
		screen.addPreference(buttons)
		ControllerProfiles.Button.values().forEach { button ->
			buttons.addPreference(KeyMappingPreference(context).apply {
				key = ControllerProfiles.mappingKey(descriptor, button)
				title = getString(button.title)
				setDefaultValue((if(descriptor == null) button.defaultKeyCode else defaults.keyCodes.getValue(button)).toString())
				onPreferenceChangeListener = rememberName
				isIconSpaceReserved = true
			})
		}

		if(descriptor != null)
		{
			val reset = PreferenceCategory(context)
			screen.addPreference(reset)
			reset.addPreference(Preference(context).apply {
				title = getString(R.string.controller_reset)
				summary = getString(R.string.controller_reset_summary)
				setIcon(R.drawable.ic_settings)
				setOnPreferenceClickListener {
					MaterialAlertDialogBuilder(context)
						.setMessage(getString(R.string.controller_reset_confirm, name ?: descriptor))
						.setPositiveButton(R.string.controller_reset) { _, _ ->
							profiles.reset(descriptor)
							build()
						}
						.setNegativeButton(R.string.action_keep, null)
						.show()
					true
				}
			})
		}
	}

	override fun getTitle(resources: Resources): String = name ?: resources.getString(R.string.controllers_defaults)
}
