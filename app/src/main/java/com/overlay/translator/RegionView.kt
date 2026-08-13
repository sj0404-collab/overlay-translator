package com.overlay.translator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RegionView(context: Context) : View(context) {
    private val dim = Paint().apply { color = 0x66000000.toInt() }
    private val clear = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }
    private val box = Paint().apply {
        color = 0xFF5B8DEF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }
    private val hint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
    }
    var rect = RectF()
    private var startX = 0f
    private var startY = 0f
    var onPicked: ((RectF) -> Unit)? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        if (rect.width() > 8 && rect.height() > 8) {
            canvas.drawRoundRect(rect, 10f, 10f, clear)
            canvas.drawRoundRect(rect, 10f, 10f, box)
        } else {
            canvas.drawText("Обведите область — внутри будет прозрачно", 40f, height / 2f, hint)
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
