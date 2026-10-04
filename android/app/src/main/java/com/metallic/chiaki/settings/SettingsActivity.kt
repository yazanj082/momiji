// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.metallic.chiaki.R
import com.metallic.chiaki.databinding.ActivitySettingsBinding
import com.metallic.chiaki.common.ext.fitSystemBars

interface TitleFragment
{
	fun getTitle(resources: Resources): String
}

class SettingsActivity: AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback
{
	companion object
	{
		private const val EXTRA_SCREEN = "screen"
		private const val SCREEN_INTERNET_PLAY = "internet_play"

		/** Opens playing away from home by itself, so that Back leaves the settings */
		fun internetPlayIntent(context: Context) =
			Intent(context, SettingsActivity::class.java).putExtra(EXTRA_SCREEN, SCREEN_INTERNET_PLAY)
	}

	private lateinit var binding: ActivitySettingsBinding

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivitySettingsBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.root.fitSystemBars()
		title = ""
		setSupportActionBar(binding.toolbar)
		binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

		val rootFragment: TitleFragment = if(intent.getStringExtra(EXTRA_SCREEN) == SCREEN_INTERNET_PLAY) SettingsInternetPlayFragment() else SettingsFragment()
		replaceFragment(rootFragment as Fragment, false)
		supportFragmentManager.addOnBackStackChangedListener {
			val titleFragment = supportFragmentManager.findFragmentById(R.id.settingsFragment) as? TitleFragment ?: return@addOnBackStackChangedListener
			binding.titleTextView.text = titleFragment.getTitle(resources)
		}
		binding.titleTextView.text = rootFragment.getTitle(resources)
	}

	override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference) = when(pref.fragment)
	{
		SettingsRegisteredHostsFragment::class.java.canonicalName -> {
			replaceFragment(SettingsRegisteredHostsFragment(), true)
			true
		}
		SettingsControllersFragment::class.java.canonicalName -> {
			replaceFragment(SettingsControllersFragment(), true)
			true
		}
		SettingsInternetPlayFragment::class.java.canonicalName -> {
			replaceFragment(SettingsInternetPlayFragment(), true)
			true
		}
		SettingsControllerFragment::class.java.canonicalName -> {
			replaceFragment(SettingsControllerFragment().also { it.arguments = pref.extras }, true)
			true
		}
		else -> false
	}

	private fun replaceFragment(fragment: Fragment, addToBackStack: Boolean)
	{
		supportFragmentManager.beginTransaction()
			.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
			.replace(R.id.settingsFragment, fragment)
			.also {
				if(addToBackStack)
					it.addToBackStack(null)
			}
			.commit()
	}
}