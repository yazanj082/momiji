// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.os.Handler
import android.os.Looper
import com.metallic.chiaki.lib.*

/**
 * Puts a console in rest mode without streaming. Consoles only take the command in a
 * Remote Play session, so this connects one in the background, sends it and disconnects.
 * @param done called on the main thread with whether the console got the command, unless canceled
 */
class RestModeRequest(private val connectInfo: ConnectInfo, private val done: (success: Boolean) -> Unit)
{
	companion object
	{
		// Time for the command to reach the console before disconnecting
		private const val DISCONNECT_DELAY_MS = 1000L
		// For some seconds after streaming, a console turns down new sessions as in use
		private const val IN_USE_RETRY_DELAY_MS = 3000L
		private const val IN_USE_RETRIES = 5
	}

	private val handler = Handler(Looper.getMainLooper())
	private var session: Session? = null
	private var retries = 0
	private var sent = false
	private var finished = false

	init
	{
		connect()
	}

	private fun connect()
	{
		try
		{
			val session = Session(connectInfo, null, false)
			this.session = session
			// Events of a session given up on can still come in
			session.eventCallback = { event -> handler.post { if(this.session === session) sessionEvent(event) } }
			if(!session.start().isSuccess)
				handler.post { finish(false) }
		}
		catch(e: CreateError)
		{
			handler.post { finish(false) }
		}
	}

	private fun sessionEvent(event: Event)
	{
		when(event)
		{
			is ConnectedEvent ->
			{
				sent = session?.gotoBed()?.isSuccess ?: false
				if(sent)
					handler.postDelayed({ finish(true) }, DISCONNECT_DELAY_MS)
				else
					finish(false)
			}
			// A profile with a login passcode needs it, which only streaming asks for
			is LoginPinRequestEvent -> finish(false)
			is QuitEvent ->
				if(!sent && event.reason.isRpInUse && retries < IN_USE_RETRIES)
				{
					retries++
					disposeSession()
					handler.postDelayed({ connect() }, IN_USE_RETRY_DELAY_MS)
				}
				else // The console ends the session itself when it goes to rest mode
					finish(sent)
			// Rumble, lights and the like
			else -> {}
		}
	}

	fun cancel() = finish(null)

	private fun finish(success: Boolean?)
	{
		if(finished)
			return
		finished = true
		handler.removeCallbacksAndMessages(null)
		disposeSession()
		if(success != null)
			done(success)
	}

	private fun disposeSession()
	{
		val session = session ?: return
		this.session = null
		// dispose waits for the session's thread to end
		Thread {
			session.stop()
			session.dispose()
		}.start()
	}
}
