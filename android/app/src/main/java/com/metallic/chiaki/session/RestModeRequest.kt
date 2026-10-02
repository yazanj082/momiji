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
class RestModeRequest(connectInfo: ConnectInfo, private val done: (success: Boolean) -> Unit)
{
	companion object
	{
		// Time for the command to reach the console before disconnecting
		private const val DISCONNECT_DELAY_MS = 1000L
	}

	private val handler = Handler(Looper.getMainLooper())
	private var session: Session? = null
	private var sent = false
	private var finished = false

	init
	{
		try
		{
			val session = Session(connectInfo, null, false)
			this.session = session
			session.eventCallback = { event -> handler.post { sessionEvent(event) } }
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
			// The console ends the session itself when it goes to rest mode
			is QuitEvent -> finish(sent)
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
		session?.let {
			session = null
			// dispose waits for the session's thread to end
			Thread {
				it.stop()
				it.dispose()
			}.start()
		}
		if(success != null)
			done(success)
	}
}
