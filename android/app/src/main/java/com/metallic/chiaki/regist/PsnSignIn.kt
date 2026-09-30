// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.appcompat.content.res.AppCompatResources
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.metallic.chiaki.R

/**
 * Signs in in the browser, where passkeys and saved passwords work. Sony's page after signing in
 * can't lead back to the app by itself, so a button in the browser's toolbar hands it over.
 */
object PsnSignIn
{
	fun browserAvailable(context: Context) = CustomTabsClient.getPackageName(context, null) != null

	fun openInBrowser(activity: Activity)
	{
		val returnIntent = Intent(activity, PsnSignInReturnActivity::class.java)
		val flags = PendingIntent.FLAG_UPDATE_CURRENT or
			// the browser adds the page's address to it
			(if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
		val pendingIntent = PendingIntent.getActivity(activity, 0, returnIntent, flags)
		val size = (24 * activity.resources.displayMetrics.density).toInt()
		val icon = AppCompatResources.getDrawable(activity, R.drawable.ic_logo)!!.toBitmap(size, size)
		val description = activity.getString(R.string.action_psn_sign_in_finish)
		val colors = CustomTabColorSchemeParams.Builder()
			.setToolbarColor(ContextCompat.getColor(activity, R.color.md_surface_container))
			.build()
		CustomTabsIntent.Builder()
			.setActionButton(icon, description, pendingIntent, false)
			.addMenuItem(description, pendingIntent)
			.setDefaultColorSchemeParams(colors)
			.setShowTitle(true)
			.setShareState(CustomTabsIntent.SHARE_STATE_OFF)
			.build()
			.launchUrl(activity, Uri.parse(PsnAccountId.LOGIN_URL))
	}
}
