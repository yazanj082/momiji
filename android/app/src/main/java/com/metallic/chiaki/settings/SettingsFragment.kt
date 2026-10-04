// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.BuildConfig
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.exportAndShareAllSettings
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.common.importSettingsFromUri
import com.metallic.chiaki.stream.ControllerRumble
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo

class DataStore(val preferences: Preferences): PreferenceDataStore()
{
	override fun getBoolean(key: String?, defValue: Boolean) = when(key)
	{
		preferences.logVerboseKey -> preferences.logVerbose
		preferences.rumbleEnabledKey -> preferences.rumbleEnabled
		preferences.motionEnabledKey -> preferences.motionEnabled
		preferences.dualSenseEnabledKey -> preferences.dualSenseEnabled
		preferences.homeScreenKey -> preferences.homeScreen
		preferences.buttonHapticEnabledKey -> preferences.buttonHapticEnabled
		preferences.debandingEnabledKey -> preferences.debandingEnabled
		preferences.touchscreenTouchpadEnabledKey -> preferences.touchscreenTouchpadEnabled
		else -> defValue
	}

	override fun putBoolean(key: String?, value: Boolean)
	{
		when(key)
		{
			preferences.logVerboseKey -> preferences.logVerbose = value
			preferences.rumbleEnabledKey -> preferences.rumbleEnabled = value
			preferences.motionEnabledKey -> preferences.motionEnabled = value
			preferences.dualSenseEnabledKey -> preferences.dualSenseEnabled = value
			preferences.homeScreenKey -> preferences.homeScreen = value
			preferences.buttonHapticEnabledKey -> preferences.buttonHapticEnabled = value
			preferences.debandingEnabledKey -> preferences.debandingEnabled = value
			preferences.touchscreenTouchpadEnabledKey -> preferences.touchscreenTouchpadEnabled = value
		}
	}

	override fun getInt(key: String?, defValue: Int) = defValue

	override fun putInt(key: String?, value: Int) {}

	override fun getString(key: String, defValue: String?) = when
	{
		key == preferences.resolutionKey -> preferences.resolution.value
		key == preferences.fpsKey -> preferences.fps.value
		key == preferences.bitrateKey -> preferences.bitrate?.toString() ?: ""
		key == preferences.codecKey -> preferences.codec.value
		else -> defValue
	}

	override fun putString(key: String, value: String?)
	{
		when
		{
			key == preferences.resolutionKey ->
			{
				val resolution = Preferences.Resolution.values().firstOrNull { it.value == value } ?: return
				preferences.resolution = resolution
			}
			key == preferences.fpsKey ->
			{
				val fps = Preferences.FPS.values().firstOrNull { it.value == value } ?: return
				preferences.fps = fps
			}
			key == preferences.bitrateKey -> preferences.bitrate = value?.toIntOrNull()
			key == preferences.codecKey ->
			{
				val codec = Preferences.Codec.values().firstOrNull { it.value == value } ?: return
				preferences.codec = codec
			}
		}
	}
}

class SettingsFragment: PreferenceFragmentCompat(), TitleFragment
{
	companion object
	{
		private const val PICK_SETTINGS_JSON_REQUEST = 1
	}

	private var disposable = CompositeDisposable()
	private var exportDisposable = CompositeDisposable().also { it.addTo(disposable) }

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
	{
		val context = context ?: return

		val viewModel = ViewModelProvider(this, viewModelFactory { SettingsViewModel(getDatabase(context), Preferences(context)) })
			.get(SettingsViewModel::class.java)

		val preferences = viewModel.preferences
		preferenceManager.preferenceDataStore = DataStore(preferences)
		setPreferencesFromResource(R.xml.preferences, rootKey)

		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_home_screen_key))?.setOnPreferenceChangeListener { _, enabled ->
			// Let the user pick Chiaki as the home screen, now that it is offered as one
			if(enabled == true)
				view?.post { showHomeScreenChooser() }
			true
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_resolution_key))?.let {
			it.entryValues = Preferences.resolutionAll.map { res -> res.value }.toTypedArray()
			it.entries = Preferences.resolutionAll.map { res -> getString(res.title) }.toTypedArray()
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_fps_key))?.let {
			it.entryValues = Preferences.fpsAll.map { fps -> fps.value }.toTypedArray()
			it.entries = Preferences.fpsAll.map { fps -> getString(fps.title) }.toTypedArray()
		}

		val bitratePreference = preferenceScreen.findPreference<EditTextPreference>(getString(R.string.preferences_bitrate_key))
		val bitrateSummaryProvider = Preference.SummaryProvider<EditTextPreference> {
			preferences.bitrate?.toString() ?: getString(R.string.preferences_bitrate_auto, preferences.bitrateAuto)
		}
		bitratePreference?.let {
			it.summaryProvider = bitrateSummaryProvider
			it.setOnBindEditTextListener { editText ->
				editText.hint = getString(R.string.preferences_bitrate_auto, preferences.bitrateAuto)
				editText.inputType = InputType.TYPE_CLASS_NUMBER
				editText.setText(preferences.bitrate?.toString() ?: "")
			}
		}
		viewModel.bitrateAuto.observe(this, Observer {
			bitratePreference?.summaryProvider = bitrateSummaryProvider
		})

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_codec_key))?.let {
			it.entryValues = Preferences.codecAll.map { codec -> codec.value }.toTypedArray()
			it.entries = Preferences.codecAll.map { codec -> getString(codec.title) }.toTypedArray()
		}

		val registeredHostsPreference = preferenceScreen.findPreference<Preference>("registered_hosts")
		viewModel.registeredHostsCount.observe(this, Observer {
			registeredHostsPreference?.summary = getString(R.string.preferences_registered_hosts_summary, it)
		})

		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_export_settings_key))?.setOnPreferenceClickListener { exportSettings(); true }
		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_import_settings_key))?.setOnPreferenceClickListener { importSettings(); true }
		preferenceScreen.findPreference<Preference>("test_controller_rumble")?.setOnPreferenceClickListener { testControllerRumble(); true }

		preferenceScreen.findPreference<Preference>("about_version")?.summary = getString(R.string.preferences_about_version, BuildConfig.VERSION_NAME)
		val supportUrl = getString(R.string.support_url)
		preferenceScreen.findPreference<Preference>("about_support")?.let {
			it.isVisible = supportUrl.isNotEmpty()
			it.intent = Intent(Intent.ACTION_VIEW, Uri.parse(supportUrl))
		}
		preferenceScreen.findPreference<Preference>("about_feedback")?.setOnPreferenceClickListener { sendFeedback(); true }
	}

	/** An email with the app version and the device filled in, as those matter for most problems */
	private fun sendFeedback()
	{
		val email = getString(R.string.feedback_email)
		val subject = getString(R.string.feedback_subject, BuildConfig.VERSION_NAME)
		val body = getString(R.string.feedback_body, BuildConfig.VERSION_NAME, BuildConfig.FLAVOR,
			"${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
		// Some mail apps only read the mailto address, others only the extras
		val uri = Uri.parse("mailto:$email?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")
		val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
			putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
			putExtra(Intent.EXTRA_SUBJECT, subject)
			putExtra(Intent.EXTRA_TEXT, body)
		}
		// TVs rarely have a usable email app, and Android TV's placeholder for one does nothing
		if(!Preferences(requireContext()).isTv)
		{
			try
			{
				startActivity(intent)
				return
			}
			catch(e: ActivityNotFoundException) {}
		}
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.preferences_about_feedback_title)
			.setMessage(getString(R.string.feedback_no_email_app, email, getString(R.string.issues_url)))
			.setPositiveButton(android.R.string.ok, null)
			.show()
	}

	override fun onDestroy()
	{
		super.onDestroy()
		disposable.dispose()
	}

	override fun getTitle(resources: Resources): String = resources.getString(R.string.title_settings)

	private fun showHomeScreenChooser()
	{
		try
		{
			startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
		}
		catch(e: ActivityNotFoundException)
		{
			// Without a home app setting, Android asks which one to use
			startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
		}
	}

	private fun testControllerRumble()
	{
		val context = context ?: return
		val controllers = ControllerRumble(context).testControllers(1500)
		val message = if(controllers.isEmpty())
			getString(R.string.test_rumble_no_controllers)
		else
			controllers.joinToString("\n\n") +
					(if(controllers.any { it.motors == 0 }) "\n\n" + getString(R.string.test_rumble_unsupported_note) else "")
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.preferences_test_rumble_title)
			.setMessage(message)
			.setPositiveButton(android.R.string.ok, null)
			.show()
	}

	private fun exportSettings()
	{
		val activity = activity ?: return
		exportDisposable.clear()
		exportAndShareAllSettings(activity).addTo(exportDisposable)
	}

	private fun importSettings()
	{
		val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
			addCategory(Intent.CATEGORY_OPENABLE)
			type = "application/json"
		}
		startActivityForResult(intent, PICK_SETTINGS_JSON_REQUEST)
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
	{
		if(requestCode == PICK_SETTINGS_JSON_REQUEST && resultCode == Activity.RESULT_OK)
		{
			val activity = activity ?: return
			data?.data?.also {
				importSettingsFromUri(activity, it, disposable)
			}
		}
	}
}