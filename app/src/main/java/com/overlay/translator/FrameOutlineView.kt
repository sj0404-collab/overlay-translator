package com.overlay.translator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Persistent thin outline of the active OCR frame. Shown as its own overlay
 * window so it stays visible above any app (including web pages) until the
 * region is re-picked. Touch-through, never focused.
 */
class FrameOutlineView(context: Context, private val label: String) : View(context) {
    var rect = RectF()

    private val border = Paint().apply {
        color = 0xFF5B8DEF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }
    private val ticks = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 26f
        isAntiAlias = true
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(rect, 12f, 12f, border)
        drawTicks(canvas, rect)
        if (label.isNotBlank()) {
            canvas.drawText(label, rect.left + 10, (rect.top - 12).coerceAtLeast(28f), textPaint)
        }
    }

    private fun drawTicks(canvas: Canvas, r: RectF) {
        val c = 20f
        canvas.drawLine(r.left, r.top + c, r.left, r.top, ticks)
        canvas.drawLine(r.left, r.top, r.left + c, r.top, ticks)
        canvas.drawLine(r.right - c, r.top, r.right, r.top, ticks)
        canvas.drawLine(r.right, r.top, r.right, r.top + c, ticks)
        canvas.drawLine(r.left, r.bottom - c, r.left, r.bottom, ticks)
        canvas.drawLine(r.left, r.bottom, r.left + c, r.bottom, ticks)
        canvas.drawLine(r.right - c, r.bottom, r.right, r.bottom, ticks)
        canvas.drawLine(r.right, r.bottom - c, r.right, r.bottom, ticks)
    }
}