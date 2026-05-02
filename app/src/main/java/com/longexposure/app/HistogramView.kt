package com.longexposure.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Semi-transparent overlay that displays a real-time luminance histogram.
 * Updated at most once per second by the [CameraController.HistogramAnalyzer].
 * Call [updateHistogram] from the main thread to refresh the display.
 */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var histogram: IntArray = IntArray(256)
    private var maxCount: Int = 1

    private val bgPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val barPaint = Paint().apply {
        color = Color.argb(210, 255, 111, 0) // accent orange
        style = Paint.Style.FILL
    }

    /**
     * Updates the histogram data and requests a redraw.
     * Must be called on the main thread.
     */
    fun updateHistogram(newHistogram: IntArray) {
        histogram = newHistogram.copyOf()
        maxCount = histogram.maxOrNull()?.coerceAtLeast(1) ?: 1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val barW = w / 256f
        for (i in 0 until 256) {
            val barH = histogram[i].toFloat() / maxCount * h
            canvas.drawRect(i * barW, h - barH, (i + 1) * barW, h, barPaint)
        }
    }
}
