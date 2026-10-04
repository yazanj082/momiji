// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.lib.QuitReason

/** Plain explanations of why a console can't be found or a stream ended, with what to try */
object ConnectionHelp
{
	// Reasons the console gives for turning a session down, beyond those the library knows
	private const val REASON_REMOTE_PLAY_NOT_ALLOWED = 0x80108b12L
	private const val REASON_INVALID_PSN_ID = 0x80108b02L
	private const val REASON_REGIST_FAILED = 0x80108b09L
	/** What the library says when nothing answered at the console's address */
	private const val REASON_UNREACHABLE = "unreachable"

	class Explanation(@StringRes val title: Int, @StringRes val message: Int)

	/** @param away whether connected through PSN, where the network advice differs */
	fun explain(reason: QuitReason, reasonString: String?, away: Boolean): Explanation = when(reason.value)
	{
		QuitReason.SESSION_REQUEST_RP_IN_USE -> Explanation(R.string.help_title_console_busy, R.string.help_rp_in_use)
		QuitReason.SESSION_REQUEST_RP_CRASH -> Explanation(R.string.help_title_couldnt_connect, R.string.help_rp_crash)
		QuitReason.SESSION_REQUEST_RP_VERSION_MISMATCH -> Explanation(R.string.help_title_couldnt_connect, R.string.help_rp_version)
		QuitReason.SESSION_REQUEST_CONNECTION_REFUSED -> Explanation(R.string.help_title_couldnt_connect, R.string.help_refused)
		QuitReason.SESSION_REQUEST_UNKNOWN -> if(reasonString == REASON_UNREACHABLE)
			Explanation(R.string.help_title_couldnt_connect, R.string.help_unreachable)
		else when(reasonCode(reasonString))
		{
			REASON_REMOTE_PLAY_NOT_ALLOWED -> Explanation(R.string.help_title_couldnt_connect, R.string.help_not_allowed)
			REASON_INVALID_PSN_ID, REASON_REGIST_FAILED -> Explanation(R.string.help_title_couldnt_connect, R.string.help_register_again)
			else -> Explanation(R.string.help_title_couldnt_connect, R.string.help_request_unknown)
		}
		QuitReason.CTRL_UNKNOWN, QuitReason.CTRL_CONNECT_FAILED, QuitReason.CTRL_CONNECTION_REFUSED ->
			Explanation(R.string.help_title_couldnt_connect, if(away) R.string.psn_connect_error_network_blocked else R.string.help_network)
		QuitReason.STREAM_CONNECTION_UNKNOWN -> Explanation(R.string.help_title_stream_ended, R.string.help_weak_network)
		QuitReason.STREAM_CONNECTION_REMOTE_DISCONNECTED -> Explanation(R.string.help_title_stream_ended, R.string.help_console_ended)
		QuitReason.PSN_REGIST_FAILED -> Explanation(R.string.help_title_couldnt_connect, R.string.help_psn_regist)
		else -> Explanation(R.string.help_title_stream_ended, R.string.help_unknown)
	}

	private fun reasonCode(reasonString: String?) =
		reasonString?.trim()?.removePrefix("0x")?.toLongOrNull(16)

	/** What to check when a console doesn't show up */
	fun showConsoleNotFound(context: Context)
	{
		val tips = listOf(
			R.string.help_not_found_on,
			R.string.help_not_found_network,
			R.string.help_not_found_vpn,
			R.string.help_not_found_remote_play,
			R.string.help_not_found_ip,
			R.string.help_not_found_away
		).joinToString("\n\n") { "• " + context.getString(it) }
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.help_not_found_title)
			.setMessage(tips)
			.setPositiveButton(android.R.string.ok, null)
			.show()
	}
}
