// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.shortcut

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.preference.PreferenceManager
import com.metallic.chiaki.R
import com.metallic.chiaki.common.RegisteredHost
import com.metallic.chiaki.main.MainActivity

/**
 * Ways to play a console from outside of the app: shortcuts on the app's icon and the home screen,
 * the Quick Settings tile and the widget. They all open the main screen with
 * [MainActivity.playIntent], which finds the console and connects to it.
 */
object ConsoleShortcuts
{
	private const val TAG = "ConsoleShortcuts"
	private const val ID_PREFIX = "console-"
	private const val KEY_LAST_MAC = "last_played_mac"
	private const val KEY_LAST_NAME = "last_played_name"
	private const val KEY_LAST_PS5 = "last_played_ps5"
	/** Launchers show four or five, and more would push the app's own actions away */
	private const val MAX_DYNAMIC = 4

	class Console(val mac: Long, val name: String, val ps5: Boolean)

	fun console(registeredHost: RegisteredHost, name: String?) =
		Console(registeredHost.serverMac.value, name ?: registeredHost.serverNickname ?: registeredHost.serverMac.toString(), registeredHost.target.isPS5)

	private fun id(mac: Long) = ID_PREFIX + mac.toString(16)

	private fun shortcut(context: Context, console: Console) = ShortcutInfoCompat.Builder(context, id(console.mac))
		.setShortLabel(console.name)
		.setLongLabel(context.getString(R.string.shortcut_play_console, console.name))
		.setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_console))
		.setIntent(MainActivity.playIntent(context, console.mac, console.name))
		.build()

	/** The console played last, for the tile and the widget */
	fun lastPlayed(context: Context): Console?
	{
		val preferences = PreferenceManager.getDefaultSharedPreferences(context)
		if(!preferences.contains(KEY_LAST_MAC))
			return null
		return Console(preferences.getLong(KEY_LAST_MAC, 0),
			preferences.getString(KEY_LAST_NAME, null) ?: return null,
			preferences.getBoolean(KEY_LAST_PS5, true))
	}

	/** Moves the console to the top of the shortcuts, and into the tile and the widget */
	fun played(context: Context, console: Console)
	{
		PreferenceManager.getDefaultSharedPreferences(context).edit {
			putLong(KEY_LAST_MAC, console.mac)
			putString(KEY_LAST_NAME, console.name)
			putBoolean(KEY_LAST_PS5, console.ps5)
		}
		try
		{
			ShortcutManagerCompat.pushDynamicShortcut(context, shortcut(context, console))
		}
		catch(e: RuntimeException)
		{
			// A disabled or rate limited shortcut
			Log.w(TAG, "Updating the shortcut failed", e)
		}
		PlayWidgetProvider.update(context)
		PlayTileService.requestUpdate(context)
	}

	fun canPin(context: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

	/** Asks the launcher to add the console to the home screen */
	fun pin(context: Context, console: Console) = ShortcutManagerCompat.requestPinShortcut(context, shortcut(context, console), null)

	/**
	 * Keeps the shortcuts in line with the registered consoles: renamed ones get their new name,
	 * and those of removed consoles stop working until they are registered again.
	 */
	fun update(context: Context, registeredHosts: List<RegisteredHost>)
	{
		val consoles = registeredHosts.sortedByDescending { it.id }.distinctBy { it.serverMac }.map { console(it, null) }
		val byId = consoles.associateBy { id(it.mac) }
		val last = lastPlayed(context)
		if(last != null && byId[id(last.mac)] == null)
		{
			PreferenceManager.getDefaultSharedPreferences(context).edit {
				remove(KEY_LAST_MAC)
				remove(KEY_LAST_NAME)
				remove(KEY_LAST_PS5)
			}
			PlayWidgetProvider.update(context)
			PlayTileService.requestUpdate(context)
		}
		try
		{
			val existing = ShortcutManagerCompat.getShortcuts(context,
				ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_PINNED)
				.filter { it.id.startsWith(ID_PREFIX) }
			val removed = existing.map { it.id }.filter { it !in byId }.distinct()
			if(removed.isNotEmpty())
				ShortcutManagerCompat.disableShortcuts(context, removed, context.getString(R.string.shortcut_console_removed))
			val returned = existing.filter { !it.isEnabled }.mapNotNull { byId[it.id] }.distinctBy { it.mac }
			if(returned.isNotEmpty())
				ShortcutManagerCompat.enableShortcuts(context, returned.map { shortcut(context, it) })
			val renamed = existing.filter { it.isEnabled && byId[it.id] != null && it.shortLabel.toString() != byId[it.id]?.name }
				.mapNotNull { byId[it.id] }.distinctBy { it.mac }
			if(renamed.isNotEmpty())
				ShortcutManagerCompat.updateShortcuts(context, renamed.map { shortcut(context, it) })

			// The one played last comes first
			val wanted = consoles.sortedByDescending { it.mac == last?.mac }
				.take(minOf(MAX_DYNAMIC, ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)))
			val dynamic = existing.filter { it.isDynamic }.map { it.id }
			if(dynamic.toSet() != wanted.map { id(it.mac) }.toSet())
				ShortcutManagerCompat.setDynamicShortcuts(context, wanted.map { shortcut(context, it) })
		}
		catch(e: RuntimeException)
		{
			Log.w(TAG, "Updating the shortcuts failed", e)
		}
	}
}
