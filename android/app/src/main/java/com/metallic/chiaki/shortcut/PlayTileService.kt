// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.shortcut

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.metallic.chiaki.R
import com.metallic.chiaki.main.MainActivity

/** A Quick Settings tile that plays the console played last */
class PlayTileService: TileService()
{
	companion object
	{
		fun requestUpdate(context: Context)
		{
			try
			{
				requestListeningState(context, ComponentName(context, PlayTileService::class.java))
			}
			catch(e: RuntimeException)
			{
				// Not every device has Quick Settings
				Log.w("PlayTileService", "Updating the tile failed", e)
			}
		}
	}

	override fun onStartListening()
	{
		super.onStartListening()
		val tile = qsTile ?: return
		val console = ConsoleShortcuts.lastPlayed(this)
		tile.label = console?.name ?: getString(R.string.app_name)
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
			tile.subtitle = getString(if(console != null) R.string.action_play else R.string.tile_open)
		tile.contentDescription = console?.let { getString(R.string.shortcut_play_console, it.name) } ?: getString(R.string.app_name)
		tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
		// It starts something rather than turning something on
		tile.state = Tile.STATE_INACTIVE
		tile.updateTile()
	}

	@SuppressLint("StartActivityAndCollapseDeprecated")
	override fun onClick()
	{
		super.onClick()
		val console = ConsoleShortcuts.lastPlayed(this)
		val intent = (console?.let { MainActivity.playIntent(this, it.mac, it.name) } ?: Intent(this, MainActivity::class.java))
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		val start = Runnable {
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
				startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
					PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
			else
				@Suppress("DEPRECATION")
				startActivityAndCollapse(intent)
		}
		if(isLocked)
			unlockAndRun(start)
		else
			start.run()
	}
}
