// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.shortcut

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.preference.PreferenceManager
import com.metallic.chiaki.R
import com.metallic.chiaki.main.MainActivity
import java.util.concurrent.Executors

/**
 * A row of the registered consoles on the Android TV home screen, where picking one plays it.
 * The classic Android TV home screen shows such rows from Android 8 on; Google TV's doesn't.
 */
object TvHomeChannel
{
	private const val TAG = "TvHomeChannel"
	private const val KEY_CHANNEL_ID = "tv_home_channel_id"
	private const val LOGO_SIZE = 160

	/** Keeps the updates in order, off the main thread */
	private val executor = Executors.newSingleThreadExecutor()

	fun isSupported(context: Context) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
		&& context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

	/** @param consoles the registered consoles, the one played last first */
	fun update(context: Context, consoles: List<ConsoleShortcuts.Console>)
	{
		if(!isSupported(context))
			return
		val appContext = context.applicationContext
		executor.execute {
			try
			{
				val channelId = channelId(appContext) ?: return@execute
				syncPrograms(appContext, channelId, consoles)
			}
			catch(e: RuntimeException)
			{
				// No TV provider, or one that doesn't take this app's channels
				Log.w(TAG, "Updating the channel failed", e)
			}
		}
	}

	@RequiresApi(Build.VERSION_CODES.O)
	private fun channelId(context: Context): Long?
	{
		val preferences = PreferenceManager.getDefaultSharedPreferences(context)
		val stored = preferences.getLong(KEY_CHANNEL_ID, -1)
		if(stored >= 0)
		{
			// The TV's data may have been cleared since. A channel the user hid stays, hidden.
			val exists = context.contentResolver.query(TvContract.buildChannelUri(stored), arrayOf(TvContract.Channels._ID),
				null, null, null)?.use { it.moveToFirst() } ?: false
			if(exists)
				return stored
		}
		val values = ContentValues().apply {
			put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW)
			put(TvContract.Channels.COLUMN_DISPLAY_NAME, context.getString(R.string.tv_channel_name))
			put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, Intent(context, MainActivity::class.java).toUri(Intent.URI_INTENT_SCHEME))
		}
		val uri = context.contentResolver.insert(TvContract.Channels.CONTENT_URI, values) ?: return null
		val id = ContentUris.parseId(uri)
		AppCompatResources.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap(LOGO_SIZE, LOGO_SIZE)?.let { logo ->
			context.contentResolver.openOutputStream(TvContract.buildChannelLogoUri(id))?.use { logo.compress(Bitmap.CompressFormat.PNG, 100, it) }
		}
		// The first channel of an app is shown without asking
		TvContract.requestChannelBrowsable(context, id)
		preferences.edit { putLong(KEY_CHANNEL_ID, id) }
		return id
	}

	@RequiresApi(Build.VERSION_CODES.O)
	private fun syncPrograms(context: Context, channelId: Long, consoles: List<ConsoleShortcuts.Console>)
	{
		val resolver = context.contentResolver
		// Console MAC to program id
		val existing = mutableMapOf<String, Long>()
		resolver.query(TvContract.buildPreviewProgramsUriForChannel(channelId),
			arrayOf(TvContract.PreviewPrograms._ID, TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID), null, null, null)?.use {
			while(it.moveToNext())
				existing[it.getString(1) ?: ""] = it.getLong(0)
		}
		consoles.forEachIndexed { index, console ->
			val values = ContentValues().apply {
				put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, channelId)
				// The platform has no type for games, and a channel shows no dates or durations
				put(TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_CHANNEL)
				put(TvContract.PreviewPrograms.COLUMN_TITLE, console.name)
				put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
					context.getString(R.string.tv_program_description, if(console.ps5) "PS5" else "PS4"))
				put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
					resourceUri(context, if(console.ps5) R.drawable.tv_card_ps5 else R.drawable.tv_card_ps4).toString())
				put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.PreviewPrograms.ASPECT_RATIO_16_9)
				put(TvContract.PreviewPrograms.COLUMN_INTENT_URI,
					MainActivity.playIntent(context, console.mac, console.name).toUri(Intent.URI_INTENT_SCHEME))
				put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, console.mac.toString())
				// Higher comes first
				put(TvContract.PreviewPrograms.COLUMN_WEIGHT, consoles.size - index)
			}
			val programId = existing.remove(console.mac.toString())
			if(programId != null)
				resolver.update(TvContract.buildPreviewProgramUri(programId), values, null, null)
			else
				resolver.insert(TvContract.PreviewPrograms.CONTENT_URI, values)
		}
		existing.values.forEach { resolver.delete(TvContract.buildPreviewProgramUri(it), null, null) }
	}

	/** By id rather than by name, which shrinking may change */
	private fun resourceUri(context: Context, @DrawableRes id: Int) = Uri.Builder()
		.scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
		.authority(context.packageName)
		.appendPath(id.toString())
		.build()
}
