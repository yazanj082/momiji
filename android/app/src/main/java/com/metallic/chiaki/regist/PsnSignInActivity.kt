// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.databinding.ActivityPsnSignInBinding
import kotlin.concurrent.thread

/**
 * Sign-in for devices without a browser, like TV boxes. Nothing of the session is kept.
 */
class PsnSignInActivity: AppCompatActivity()
{
	companion object
	{
		const val EXTRA_ACCOUNT_ID = "account_id"
	}

	private lateinit var binding: ActivityPsnSignInBinding
	private var codeReceived = false

	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityPsnSignInBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.toolbar.setNavigationOnClickListener { finish() }

		clearSession()
		binding.webView.settings.javaScriptEnabled = true
		binding.webView.settings.domStorageEnabled = true
		binding.webView.webViewClient = object: WebViewClient()
		{
			override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = handleUrl(request.url)

			override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?)
			{
				if(handleUrl(Uri.parse(url)))
					view.stopLoading()
				else
					binding.progressIndicator.visibility = View.VISIBLE
			}

			override fun onPageFinished(view: WebView, url: String)
			{
				if(!codeReceived)
					binding.progressIndicator.visibility = View.GONE
			}
		}
		startSignIn()
	}

	override fun onDestroy()
	{
		binding.webView.destroy()
		clearSession()
		super.onDestroy()
	}

	private fun startSignIn()
	{
		codeReceived = false
		binding.statusTextView.visibility = View.GONE
		binding.webView.visibility = View.VISIBLE
		binding.webView.loadUrl(PsnAccountId.LOGIN_URL)
	}

	private fun clearSession()
	{
		CookieManager.getInstance().removeAllCookies(null)
		WebStorage.getInstance().deleteAllData()
	}

	/**
	 * @return whether the url is Sony's redirect after signing in, which carries the code for the account id
	 */
	private fun handleUrl(url: Uri): Boolean
	{
		if(!url.toString().startsWith(PsnAccountId.REDIRECT_URI))
			return false
		if(codeReceived)
			return true
		codeReceived = true
		binding.webView.visibility = View.INVISIBLE
		binding.statusTextView.visibility = View.VISIBLE
		binding.progressIndicator.visibility = View.VISIBLE
		val code = PsnAccountId.codeFromRedirect(url)
		if(code == null)
		{
			showError(getString(R.string.psn_sign_in_no_code))
			return true
		}
		thread {
			val result = runCatching { PsnAccountId.fetch(code) }
			runOnUiThread {
				if(isDestroyed)
					return@runOnUiThread
				result.fold(
					onSuccess = { accountId ->
						setResult(RESULT_OK, Intent().putExtra(EXTRA_ACCOUNT_ID, accountId))
						finish()
					},
					onFailure = { showError(it.message ?: it.toString()) })
			}
		}
		return true
	}

	private fun showError(message: String)
	{
		binding.progressIndicator.visibility = View.GONE
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.psn_sign_in_failed)
			.setMessage(message)
			.setPositiveButton(R.string.action_try_again) { _, _ -> startSignIn() }
			.setNegativeButton(R.string.action_connect_cancel_connect) { _, _ -> finish() }
			.setCancelable(false)
			.show()
	}
}
