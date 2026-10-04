// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.min

/** A stick's position for the controller test, with its dead zone */
class StickView(context: Context, attrs: AttributeSet?) : View(context, attrs)
{
	/** -1 to 1 */
	var stickX = 0f
		set(value) { field = value; invalidate() }
	var stickY = 0f
		set(value) { field = value; invalidate() }
	/** 0 to 1 */
	var deadZone = 0f
		set(value) { field = value; invalidate() }

	private val density = resources.displayMetrics.density

	private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 2 * density
		color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline, 0)
	}

	private val deadZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
		color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerHighest, 0)
	}

	private val positionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
		color = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0)
	}

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		val dot = 8 * density
		val radius = min(width, height) / 2f - dot
		val cx = width / 2f
		val cy = height / 2f
		if(deadZone > 0f)
			canvas.drawCircle(cx, cy, radius * deadZone, deadZonePaint)
		canvas.drawCircle(cx, cy, radius, rangePaint)
		canvas.drawCircle(cx + stickX.coerceIn(-1f, 1f) * radius, cy + stickY.coerceIn(-1f, 1f) * radius, dot, positionPaint)
	}
}
