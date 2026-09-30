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

/**
 * Gets the account id that registration needs by signing in on Sony's own page,
 * with the sign-in of the official Remote Play app for Windows.
 */
object PsnAccountId
{
	private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
	private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
	const val REDIRECT_URI = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
	const val LOGIN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize" +
		"?service_entity=urn:service-entity:psn&response_type=code&client_id=$CLIENT_ID&redirect_uri=$REDIRECT_URI" +
		"&scope=psn:clientapp&request_locale=en_US&ui=pr&service_logo=ps&layout_type=popup&smcid=remoteplay" +
		"&prompt=always&PlatformPrivacyWs1=minimal&"
	private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"

	/** @return the sign-in code if [url] is Sony's page after signing in */
	fun codeFromRedirect(url: Uri): String? =
		if(url.toString().startsWith(REDIRECT_URI)) url.getQueryParameter("code")?.takeIf { it.isNotEmpty() } else null

	/** Blocks for the network requests */
	fun fetch(code: String): String
	{
		val auth = "Basic " + Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), Base64.NO_WRAP)

		val tokenRequest = URL(TOKEN_URL).openConnection() as HttpURLConnection
		tokenRequest.requestMethod = "POST"
		tokenRequest.doOutput = true
		tokenRequest.setRequestProperty("Authorization", auth)
		tokenRequest.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
		val body = "grant_type=authorization_code&code=${URLEncoder.encode(code, "UTF-8")}&redirect_uri=$REDIRECT_URI&"
		tokenRequest.outputStream.use { it.write(body.toByteArray()) }
		val token = JSONObject(readResponse(tokenRequest)).getString("access_token")

		val infoRequest = URL("$TOKEN_URL/${URLEncoder.encode(token, "UTF-8")}").openConnection() as HttpURLConnection
		infoRequest.setRequestProperty("Authorization", auth)
		val userId = JSONObject(readResponse(infoRequest)).get("user_id").toString()
		return fromUserId(userId)
	}

	/** The account id is the user id as 8 little-endian bytes, in base64 */
	fun fromUserId(userId: String): String
	{
		val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(BigInteger(userId).toLong()).array()
		return Base64.encodeToString(bytes, Base64.NO_WRAP)
	}

	private fun readResponse(connection: HttpURLConnection): String
	{
		try
		{
			val status = connection.responseCode
			if(status !in 200..299)
				throw IOException("HTTP $status")
			return connection.inputStream.bufferedReader().use { it.readText() }
		}
		finally
		{
			connection.disconnect()
		}
	}
}
