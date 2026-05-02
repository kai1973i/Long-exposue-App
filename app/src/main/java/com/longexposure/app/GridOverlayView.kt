package com.longexposure.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay that draws a rule-of-thirds grid (2×2 lines) over the camera preview.
 * Visibility is controlled by [MainActivity] via the grid toggle button.
 */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.argb(130, 255, 255, 255)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawLine(w / 3f,       0f, w / 3f,       h, paint)
        canvas.drawLine(2f * w / 3f,  0f, 2f * w / 3f,  h, paint)
        canvas.drawLine(0f, h / 3f,   w,  h / 3f,        paint)
        canvas.drawLine(0f, 2f * h / 3f, w, 2f * h / 3f, paint)
    }
}
