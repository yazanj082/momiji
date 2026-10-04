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
import com.metallic.chiaki.common.ext.fitSystemBars
import kotlin.concurrent.thread

/**
 * Sign-in for devices without a browser, like TV boxes. Nothing of the browser session is kept.
 * For registration, the result has the account id. For playing away from home, the sign-in
 * is kept in [com.metallic.chiaki.common.PsnAccount] when the result is RESULT_OK.
 */
class PsnSignInActivity: AppCompatActivity()
{
	companion object
	{
		const val EXTRA_ACCOUNT_ID = "account_id"
		/** A [PsnAuth.Purpose] name, registration if missing */
		const val EXTRA_PURPOSE = PsnSignInReturnActivity.EXTRA_PURPOSE
	}

	private val purpose get() = PsnSignIn.purposeOf(intent)

	private lateinit var binding: ActivityPsnSignInBinding
	private var codeReceived = false

	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityPsnSignInBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.root.fitSystemBars(keyboard = true)
		binding.toolbar.setNavigationOnClickListener { finish() }
		binding.statusTextView.setText(PsnSignIn.fetchingText(purpose))

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
		binding.webView.loadUrl(PsnAuth.loginUrl(purpose))
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
		if(!url.toString().startsWith(PsnAuth.REDIRECT_URI))
			return false
		if(codeReceived)
			return true
		codeReceived = true
		binding.webView.visibility = View.INVISIBLE
		binding.statusTextView.visibility = View.VISIBLE
		binding.progressIndicator.visibility = View.VISIBLE
		val code = PsnAuth.codeFromRedirect(url)
		if(code == null)
		{
			showError(getString(R.string.psn_sign_in_no_code))
			return true
		}
		val purpose = purpose
		thread {
			val result = runCatching { PsnSignIn.finish(applicationContext, code, purpose) }
			runOnUiThread {
				if(isDestroyed)
					return@runOnUiThread
				result.fold(
					onSuccess = { accountId ->
						setResult(RESULT_OK, Intent().also { if(accountId != null) it.putExtra(EXTRA_ACCOUNT_ID, accountId) })
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
			.setTitle(PsnSignIn.failedText(purpose))
			.setMessage(message)
			.setPositiveButton(R.string.action_try_again) { _, _ -> startSignIn() }
			.setNegativeButton(R.string.action_connect_cancel_connect) { _, _ -> finish() }
			.setCancelable(false)
			.show()
	}
}
