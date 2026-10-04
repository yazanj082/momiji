// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.graphics.drawable.Icon
import android.text.InputType
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.BuildConfig
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.PsnAccount
import com.metallic.chiaki.common.exportAndShareAllSettings
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.common.importSettingsFromUri
import com.metallic.chiaki.shortcut.PlayTileService
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
		preferences.pictureInPictureKey -> preferences.pictureInPicture
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
			preferences.pictureInPictureKey -> preferences.pictureInPicture = value
		}
	}

	override fun getInt(key: String?, defValue: Int) = defValue

	override fun putInt(key: String?, value: Int) {}

	override fun getString(key: String, defValue: String?) = when
	{
		key == preferences.qualityKey -> preferences.quality.value
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
			key == preferences.qualityKey -> preferences.quality = Preferences.Quality.fromValue(value) ?: return
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

		val customPreferences = listOf(R.string.preferences_resolution_key, R.string.preferences_fps_key, R.string.preferences_bitrate_key)
			.mapNotNull { preferenceScreen.findPreference<Preference>(getString(it)) }
		preferenceScreen.findPreference<ListPreference>(preferences.qualityKey)?.let {
			it.entryValues = Preferences.Quality.values().map { quality -> quality.value }.toTypedArray()
			it.entries = Preferences.Quality.values().map { quality -> getString(quality.title) }.toTypedArray()
			it.summaryProvider = Preference.SummaryProvider<ListPreference> {
				val quality = preferences.quality
				getString(R.string.quality_summary, getString(quality.title), getString(quality.summary))
			}
			// The details are only for Custom
			customPreferences.forEach { preference -> preference.isVisible = preferences.quality == Preferences.Quality.CUSTOM }
			it.setOnPreferenceChangeListener { _, value ->
				customPreferences.forEach { preference -> preference.isVisible = value == Preferences.Quality.CUSTOM.value }
				true
			}
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

		preferenceScreen.findPreference<Preference>(preferences.pictureInPictureKey)?.isVisible =
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !preferences.isTv)
			preferenceScreen.findPreference<Preference>("quick_settings_tile")?.let {
				it.isVisible = true
				it.setOnPreferenceClickListener { addQuickSettingsTile(); true }
			}
	}

	@RequiresApi(Build.VERSION_CODES.TIRAMISU)
	private fun addQuickSettingsTile()
	{
		val context = context ?: return
		val statusBarManager = context.getSystemService(StatusBarManager::class.java) ?: return
		statusBarManager.requestAddTileService(ComponentName(context, PlayTileService::class.java),
			getString(R.string.app_name), Icon.createWithResource(context, R.drawable.ic_tile), context.mainExecutor) { result ->
			if(result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
				Toast.makeText(context, R.string.preferences_tile_already_added, Toast.LENGTH_SHORT).show()
		}
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

	override fun onResume()
	{
		super.onResume()
		// Signing in or out happens on its own screen
		val account = PsnAccount(requireContext())
		preferenceScreen.findPreference<Preference>("internet_play")?.summary = when
		{
			!account.isSignedIn -> getString(R.string.preferences_internet_play_summary_off)
			account.onlineId != null -> getString(R.string.preferences_internet_play_summary_on, account.onlineId)
			else -> getString(R.string.internet_play_signed_in)
		}
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
			controllers.joinToString("\n\n") { it.describe(context) } +
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