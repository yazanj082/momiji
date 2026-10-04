// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.app.Activity
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.PsnAccount
import com.metallic.chiaki.regist.PsnAuth
import com.metallic.chiaki.regist.PsnSignIn
import com.metallic.chiaki.regist.PsnSignInActivity

/** Signing in to PSN to play away from home, and what that needs */
class SettingsInternetPlayFragment: PreferenceFragmentCompat(), TitleFragment
{
	companion object
	{
		private const val REQUEST_SIGN_IN = 1
	}

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
	{
		preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
	}

	override fun onResume()
	{
		super.onResume()
		// Also after signing in in the browser
		update()
	}

	private fun update()
	{
		val context = context ?: return
		val screen = preferenceScreen ?: return
		screen.removeAll()
		val account = PsnAccount(context)

		val accountCategory = PreferenceCategory(context).apply {
			title = getString(R.string.internet_play_account)
		}
		screen.addPreference(accountCategory)
		if(account.isSignedIn)
		{
			accountCategory.addPreference(Preference(context).apply {
				title = account.onlineId ?: getString(R.string.internet_play_signed_in)
				summary = getString(R.string.internet_play_signed_in_summary)
				setIcon(R.drawable.ic_account)
				isSelectable = false
			})
			accountCategory.addPreference(Preference(context).apply {
				title = getString(R.string.internet_play_sign_out)
				summary = getString(R.string.internet_play_sign_out_summary)
				setIcon(R.drawable.ic_logout)
				setOnPreferenceClickListener { confirmSignOut(); true }
			})
			val preferences = Preferences(context)
			accountCategory.addPreference(Preference(context).apply {
				title = getString(R.string.quality_away_title)
				summary = getString(R.string.quality_summary, getString(preferences.qualityAway.title), getString(preferences.qualityAway.summary))
				setIcon(R.drawable.ic_resolution)
				setOnPreferenceClickListener { chooseQualityAway(); true }
			})
		}
		else
		{
			accountCategory.addPreference(Preference(context).apply {
				title = getString(R.string.internet_play_sign_in)
				summary = getString(R.string.internet_play_sign_in_summary)
				setIcon(R.drawable.ic_login)
				setOnPreferenceClickListener { signIn(); true }
			})
		}

		val aboutCategory = PreferenceCategory(context).apply {
			title = getString(R.string.internet_play_about)
		}
		screen.addPreference(aboutCategory)
		listOf(
			Triple(R.string.internet_play_how_title, R.string.internet_play_how, R.drawable.ic_web),
			Triple(R.string.internet_play_console_title, R.string.internet_play_console, R.drawable.ic_console_simple),
			Triple(R.string.internet_play_privacy_title, R.string.internet_play_privacy, R.drawable.ic_lock)
		).forEach { (title, text, icon) ->
			aboutCategory.addPreference(Preference(context).apply {
				this.title = getString(title)
				summary = getString(text)
				setIcon(icon)
				isSelectable = false
			})
		}
	}

	private fun signIn()
	{
		val activity = activity ?: return
		if(PsnSignIn.browserAvailable(activity))
		{
			PsnSignIn.openInBrowser(activity, PsnAuth.Purpose.INTERNET_PLAY)
			Toast.makeText(activity, R.string.psn_sign_in_reminder, Toast.LENGTH_LONG).show()
		}
		else
			startActivityForResult(Intent(activity, PsnSignInActivity::class.java)
				.putExtra(PsnSignInActivity.EXTRA_PURPOSE, PsnAuth.Purpose.INTERNET_PLAY.name), REQUEST_SIGN_IN)
	}

	@Deprecated("Deprecated in Java")
	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
	{
		super.onActivityResult(requestCode, resultCode, data)
		if(requestCode == REQUEST_SIGN_IN && resultCode == Activity.RESULT_OK)
			Toast.makeText(context ?: return, R.string.internet_play_signed_in_toast, Toast.LENGTH_LONG).show()
	}

	/** The presets, as Custom is for the settings of every connection */
	private fun chooseQualityAway()
	{
		val context = context ?: return
		val preferences = Preferences(context)
		val choices = Preferences.Quality.values().filter { it != Preferences.Quality.CUSTOM }
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.quality_away_title)
			.setSingleChoiceItems(choices.map { getString(it.title) }.toTypedArray(), choices.indexOf(preferences.qualityAway)) { dialog, which ->
				preferences.qualityAway = choices[which]
				dialog.dismiss()
				update()
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun confirmSignOut()
	{
		val context = context ?: return
		MaterialAlertDialogBuilder(context)
			.setMessage(R.string.internet_play_sign_out_confirm)
			.setPositiveButton(R.string.internet_play_sign_out) { _, _ ->
				PsnAccount(context).signOut()
				update()
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	override fun getTitle(resources: Resources): String = resources.getString(R.string.preferences_internet_play_title)
}
