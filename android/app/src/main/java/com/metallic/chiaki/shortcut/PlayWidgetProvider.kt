// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.shortcut

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.metallic.chiaki.R
import com.metallic.chiaki.main.MainActivity

/** A home screen widget that plays the console played last */
class PlayWidgetProvider: AppWidgetProvider()
{
	companion object
	{
		fun update(context: Context)
		{
			val manager = AppWidgetManager.getInstance(context) ?: return
			val ids = manager.getAppWidgetIds(ComponentName(context, PlayWidgetProvider::class.java))
			if(ids.isNotEmpty())
				manager.updateAppWidget(ids, views(context))
		}

		private fun views(context: Context): RemoteViews
		{
			val views = RemoteViews(context.packageName, R.layout.widget_play)
			val console = ConsoleShortcuts.lastPlayed(context)
			val intent = if(console != null)
			{
				views.setTextViewText(R.id.widgetTitle, console.name)
				views.setTextViewText(R.id.widgetSubtitle, context.getString(R.string.widget_play_subtitle, if(console.ps5) "PS5" else "PS4"))
				MainActivity.playIntent(context, console.mac, console.name)
			}
			else
			{
				views.setTextViewText(R.id.widgetTitle, context.getString(R.string.app_name))
				views.setTextViewText(R.id.widgetSubtitle, context.getString(R.string.widget_play_none))
				Intent(context, MainActivity::class.java)
			}
			val pendingIntent = PendingIntent.getActivity(context, 0, intent,
				PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
			views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)
			return views
		}
	}

	override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray)
	{
		appWidgetManager.updateAppWidget(appWidgetIds, views(context))
	}
}
