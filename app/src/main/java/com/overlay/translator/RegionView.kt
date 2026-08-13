package com.overlay.translator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RegionView(context: Context) : View(context) {
    private val dim = Paint().apply { color = 0x88000000.toInt() }
    private val box = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val fill = Paint().apply { color = 0x2200FFFF }
    var rect = RectF()
    private var startX = 0f
    private var startY = 0f
    var onPicked: ((RectF) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        if (rect.width() > 8 && rect.height() > 8) {
            canvas.drawRect(rect, fill)
            canvas.drawRect(rect, box)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                rect.set(startX, startY, startX, startY)
            }
            MotionEvent.ACTION_MOVE -> {
                rect.set(min(startX, event.x), min(startY, event.y), max(startX, event.x), max(startY, event.y))
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (abs(rect.width()) > 24 && abs(rect.height()) > 24) {
                    onPicked?.invoke(RectF(rect))
                }
            }
        }
        return true
    }
}
