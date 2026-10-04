// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import com.metallic.chiaki.regist.PsnAuth

/**
 * The PSN sign-in for playing away from home. Its tokens stay on this device, in their own file,
 * which exporting the settings leaves out and backups exclude.
 */
class PsnAccount(context: Context)
{
	companion object
	{
		private const val FILE = "psn_account"
		private const val KEY_ACCESS_TOKEN = "access_token"
		private const val KEY_REFRESH_TOKEN = "refresh_token"
		private const val KEY_EXPIRES_AT = "expires_at"
		private const val KEY_ACCOUNT_ID = "account_id"
		private const val KEY_ONLINE_ID = "online_id"
		/** Refreshes a token that would expire sooner, so it lasts through connecting */
		private const val REFRESH_MARGIN_MS = 5 * 60 * 1000L
		private val lock = Any()
	}

	private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

	val isSignedIn get() = !preferences.getString(KEY_REFRESH_TOKEN, null).isNullOrEmpty() && accountId != null

	val onlineId: String? get() = preferences.getString(KEY_ONLINE_ID, null)

	/** 8 bytes, as connecting through PSN takes it */
	val accountId: ByteArray? get() = preferences.getString(KEY_ACCOUNT_ID, null)
		?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
		?.takeIf { it.size == 8 }

	/** Blocks for the network requests */
	fun signIn(code: String)
	{
		val tokens = PsnAuth.fetchTokens(code, PsnAuth.Purpose.INTERNET_PLAY)
		val account = PsnAuth.fetchAccount(tokens.accessToken)
		synchronized(lock) {
			preferences.edit(commit = true) {
				putTokens(tokens)
				putString(KEY_ACCOUNT_ID, account.accountId)
				putString(KEY_ONLINE_ID, account.onlineId)
			}
		}
	}

	fun signOut() = synchronized(lock) {
		preferences.edit(commit = true) { clear() }
	}

	/**
	 * A token that is valid for a while, refreshed if needed. Blocks for the network then.
	 * @throws PsnAuth.SignInRejectedException if PSN wants a new sign-in, which also signs out
	 * @throws java.io.IOException
	 */
	fun accessToken(): String = synchronized(lock) {
		val accessToken = preferences.getString(KEY_ACCESS_TOKEN, null)
		val refreshToken = preferences.getString(KEY_REFRESH_TOKEN, null)
		if(refreshToken.isNullOrEmpty())
			throw PsnAuth.SignInRejectedException(401)
		if(accessToken != null && preferences.getLong(KEY_EXPIRES_AT, 0) - REFRESH_MARGIN_MS > System.currentTimeMillis())
			return accessToken
		val tokens = try
		{
			PsnAuth.refreshTokens(refreshToken)
		}
		catch(e: PsnAuth.SignInRejectedException)
		{
			preferences.edit(commit = true) { clear() }
			throw e
		}
		preferences.edit(commit = true) { putTokens(tokens) }
		tokens.accessToken
	}

	private fun android.content.SharedPreferences.Editor.putTokens(tokens: PsnAuth.Tokens)
	{
		putString(KEY_ACCESS_TOKEN, tokens.accessToken)
		putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
		putLong(KEY_EXPIRES_AT, tokens.expiresAtMs)
	}
}
