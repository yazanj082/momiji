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
import kotlin.concurrent.thread

/**
 * Opened from the button in the browser's toolbar with the page shown there,
 * whose address carries the sign-in code once signing in is done.
 */
class PsnSignInReturnActivity: AppCompatActivity()
{
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		val code = intent.data?.let { PsnAccountId.codeFromRedirect(it) }
		if(code == null)
		{
			Toast.makeText(this, R.string.psn_sign_in_not_finished, Toast.LENGTH_LONG).show()
			finish()
			return
		}
		val binding = ActivityPsnSignInReturnBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.root.fitSystemBars()
		thread {
			val result = runCatching { PsnAccountId.fetch(code) }
			runOnUiThread {
				if(isDestroyed)
					return@runOnUiThread
				result.fold(
					onSuccess = { returnToRegist(it) },
					onFailure = { showError(it.message ?: it.toString()) })
			}
		}
	}

	/** Also closes the browser, which is above registration */
	private fun returnToRegist(accountId: String?)
	{
		startActivity(Intent(this, RegistActivity::class.java)
			.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
			.also { if(accountId != null) it.putExtra(RegistActivity.EXTRA_PSN_ACCOUNT_ID, accountId) })
		finish()
	}

	private fun showError(message: String)
	{
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.psn_sign_in_failed)
			.setMessage(message)
			.setPositiveButton(R.string.action_try_again) { _, _ ->
				PsnSignIn.openInBrowser(this)
				finish()
			}
			.setNegativeButton(R.string.action_connect_cancel_connect) { _, _ -> returnToRegist(null) }
			.setCancelable(false)
			.show()
	}
}
