// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common.ext

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams

/**
 * Keeps an activity's root view clear of the status and navigation bars (and the keyboard),
 * which Android 15+ draws over apps that target it. Shrinking the view instead of padding it
 * lets scrolling views keep the focused field visible when the keyboard opens.
 */
fun View.fitSystemBars(keyboard: Boolean = false)
{
	ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
		var types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
		if(keyboard)
			types = types or WindowInsetsCompat.Type.ime()
		val bars = insets.getInsets(types)
		view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
			leftMargin = bars.left
			topMargin = bars.top
			rightMargin = bars.right
			bottomMargin = bars.bottom
		}
		WindowInsetsCompat.CONSUMED
	}
}
