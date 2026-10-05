// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

/** Takes the touches beside the controls, so they don't reach the stream */
class ControlsBackgroundView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
{
	var onDoubleTap: (() -> Unit)? = null

	private val gestureDetector = GestureDetector(context, object: GestureDetector.SimpleOnGestureListener()
	{
		override fun onDown(e: MotionEvent) = true

		override fun onDoubleTap(e: MotionEvent): Boolean
		{
			onDoubleTap?.invoke()
			return true
		}
	})

	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		gestureDetector.onTouchEvent(event)
		return true
	}
}