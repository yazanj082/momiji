// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Signs in on Sony's own page, with the sign-in of the official Remote Play app for Windows:
 * for the account id that registration needs, or for the tokens that playing away from home needs.
 */
object PsnAuth
{
	private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
	private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
	const val REDIRECT_URI = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
	private const val AUTHORIZE_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize"
	private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
	private const val SCOPE_ACCOUNT_ID = "psn:clientapp"
	/** Connecting through PSN also creates a session there and gets its notifications */
	private const val SCOPE_INTERNET_PLAY = "psn:clientapp referenceDataService:countryConfig.read " +
		"pushNotification:webSocket.desktop.connect sessionManager:remotePlaySession.system.update"
	/** Device ids of Remote Play clients start with this */
	private const val DUID_PREFIX = "0000000700410080"

	enum class Purpose
	{
		/** Only the account id, which isn't kept */
		REGISTRATION,
		/** Tokens that stay on the device, see [com.metallic.chiaki.common.PsnAccount] */
		INTERNET_PLAY
	}

	class Tokens(
		val accessToken: String,
		val refreshToken: String,
		/** System.currentTimeMillis() when the access token expires */
		val expiresAtMs: Long
	)

	class Account(
		/** The account id in base64, as registration takes it */
		val accountId: String,
		val onlineId: String?
	)

	/** PSN rejected the sign-in or the refresh token, so signing in again is needed */
	class SignInRejectedException(status: Int): IOException("HTTP $status")

	fun loginUrl(purpose: Purpose): String
	{
		val scope = if(purpose == Purpose.INTERNET_PLAY) SCOPE_INTERNET_PLAY else SCOPE_ACCOUNT_ID
		val url = AUTHORIZE_URL +
			"?service_entity=urn:service-entity:psn&response_type=code&client_id=$CLIENT_ID&redirect_uri=$REDIRECT_URI" +
			"&scope=${Uri.encode(scope, ":")}&request_locale=en_US&ui=pr&service_logo=ps&layout_type=popup&smcid=remoteplay" +
			"&prompt=always&PlatformPrivacyWs1=minimal&"
		// The tokens are made for a device id, which connecting through PSN needs
		return if(purpose == Purpose.INTERNET_PLAY) "${url}duid=${newDeviceId()}&" else url
	}

	private fun newDeviceId(): String
	{
		val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
		return DUID_PREFIX + bytes.joinToString("") { "%02x".format(it) }
	}

	/** @return the sign-in code if [url] is Sony's page after signing in */
	fun codeFromRedirect(url: Uri): String? =
		if(url.toString().startsWith(REDIRECT_URI)) url.getQueryParameter("code")?.takeIf { it.isNotEmpty() } else null

	/** For registration. Blocks for the network requests. */
	fun fetchAccountId(code: String): String = fetchAccount(fetchTokens(code, Purpose.REGISTRATION).accessToken).accountId

	/** Blocks for the network request */
	fun fetchTokens(code: String, purpose: Purpose) = when(purpose)
	{
		// As registration has always asked
		Purpose.REGISTRATION -> requestTokens("grant_type" to "authorization_code", "code" to code, "redirect_uri" to REDIRECT_URI)
		Purpose.INTERNET_PLAY -> requestTokens("grant_type" to "authorization_code", "code" to code, "scope" to SCOPE_INTERNET_PLAY, "redirect_uri" to REDIRECT_URI)
	}

	/** Blocks for the network request */
	fun refreshTokens(refreshToken: String) =
		requestTokens("grant_type" to "refresh_token", "refresh_token" to refreshToken, "scope" to SCOPE_INTERNET_PLAY, "redirect_uri" to REDIRECT_URI)

	private fun requestTokens(vararg params: Pair<String, String>): Tokens
	{
		val request = open(TOKEN_URL)
		request.requestMethod = "POST"
		request.doOutput = true
		request.setRequestProperty("Authorization", basicAuth())
		request.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
		val body = params.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
		val requestTime = System.currentTimeMillis()
		request.outputStream.use { it.write(body.toByteArray()) }
		val response = JSONObject(readResponse(request))
		return Tokens(
			accessToken = response.getString("access_token"),
			// A refresh doesn't always come with a new refresh token
			refreshToken = response.optString("refresh_token").ifEmpty { params.toMap()["refresh_token"] ?: "" },
			expiresAtMs = requestTime + response.optLong("expires_in", 0) * 1000)
	}

	/** Blocks for the network request */
	fun fetchAccount(accessToken: String): Account
	{
		val request = open("$TOKEN_URL/${URLEncoder.encode(accessToken, "UTF-8")}")
		request.setRequestProperty("Authorization", basicAuth())
		val info = JSONObject(readResponse(request))
		return Account(fromUserId(info.get("user_id").toString()), info.optString("online_id").ifEmpty { null })
	}

	/** The account id is the user id as 8 little-endian bytes, in base64 */
	fun fromUserId(userId: String): String
	{
		val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(BigInteger(userId).toLong()).array()
		return Base64.encodeToString(bytes, Base64.NO_WRAP)
	}

	private fun basicAuth() = "Basic " + Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), Base64.NO_WRAP)

	private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
		connectTimeout = TIMEOUT_MS
		readTimeout = TIMEOUT_MS
	}

	private fun readResponse(connection: HttpURLConnection): String
	{
		try
		{
			val status = connection.responseCode
			if(status == 400 || status == 401)
				throw SignInRejectedException(status)
			if(status !in 200..299)
				throw IOException("HTTP $status")
			return connection.inputStream.bufferedReader().use { it.readText() }
		}
		finally
		{
			connection.disconnect()
		}
	}

	private const val TIMEOUT_MS = 15000
}
