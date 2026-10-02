// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.content.Context
import android.content.DialogInterface
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R

/**
 * Asks before removing the registration of the console called [name].
 * @param keep called when the registration is kept, also when the dialog is closed
 */
fun showRemoveRegistrationDialog(context: Context, name: String, keep: () -> Unit = {}, remove: () -> Unit)
{
	var removed = false
	MaterialAlertDialogBuilder(context)
		.setTitle(context.getString(R.string.alert_title_remove_registration, name))
		.setMessage(R.string.alert_message_remove_registration)
		.setPositiveButton(R.string.action_remove) { _, _ ->
			removed = true
			remove()
		}
		.setNegativeButton(R.string.action_keep, null)
		.setOnDismissListener { if(!removed) keep() }
		.show()
		// With a remote or controller, the safe choice is the one ready to be picked
		.getButton(DialogInterface.BUTTON_NEGATIVE)
		.requestFocus()
}
