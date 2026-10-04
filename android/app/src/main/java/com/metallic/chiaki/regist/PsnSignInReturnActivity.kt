// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.databinding.ActivityPsnSignInReturnBinding
import com.metallic.chiaki.common.ext.fitSystemBars
import com.metallic.chiaki.settings.SettingsActivity
import kotlin.concurrent.thread

/**
 * Opened from the button in the browser's toolbar with the page shown there,
 * whose address carries the sign-in code once signing in is done.
 */
class PsnSignInReturnActivity: AppCompatActivity()
{
	companion object
	{
		const val EXTRA_PURPOSE = "purpose"
	}

	private val purpose get() = PsnSignIn.purposeOf(intent)

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		val code = intent.data?.let { PsnAuth.codeFromRedirect(it) }
		if(code == null)
		{
			Toast.makeText(this, R.string.psn_sign_in_not_finished, Toast.LENGTH_LONG).show()
			finish()
			return
		}
		val binding = ActivityPsnSignInReturnBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.root.fitSystemBars()
		binding.statusTextView.setText(PsnSignIn.fetchingText(purpose))
		val purpose = purpose
		thread {
			val result = runCatching { PsnSignIn.finish(applicationContext, code, purpose) }
			runOnUiThread {
				if(isDestroyed)
					return@runOnUiThread
				result.fold(
					onSuccess = { returnToApp(it, true) },
					onFailure = { showError(it.message ?: it.toString()) })
			}
		}
	}

	/** Also closes the browser, which is above the screen that started signing in */
	private fun returnToApp(accountId: String?, success: Boolean)
	{
		val intent = when(purpose)
		{
			PsnAuth.Purpose.REGISTRATION -> Intent(this, RegistActivity::class.java)
				.also { if(accountId != null) it.putExtra(RegistActivity.EXTRA_PSN_ACCOUNT_ID, accountId) }
			PsnAuth.Purpose.INTERNET_PLAY ->
			{
				if(success)
					Toast.makeText(this, R.string.internet_play_signed_in_toast, Toast.LENGTH_LONG).show()
				SettingsActivity.internetPlayIntent(this)
			}
		}
		startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
		finish()
	}

	private fun showError(message: String)
	{
		MaterialAlertDialogBuilder(this)
			.setTitle(PsnSignIn.failedText(purpose))
			.setMessage(message)
			.setPositiveButton(R.string.action_try_again) { _, _ ->
				PsnSignIn.openInBrowser(this, purpose)
				finish()
			}
			.setNegativeButton(R.string.action_connect_cancel_connect) { _, _ -> returnToApp(null, false) }
			.setCancelable(false)
			.show()
	}
}
