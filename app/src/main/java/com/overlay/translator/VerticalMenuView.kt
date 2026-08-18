package com.overlay.translator

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.abs

/**
 * Vertical floating menu (like SAO side menu).
 * Single trigger button → expands downward as a vertical list of items.
 * Can be dragged by the trigger button.
 */
class VerticalMenuView(context: Context, private val items: List<VerticalItem>) : View(context) {

    data class VerticalItem(val label: String, val icon: String, val onClick: () -> Unit)

    private val triggerH = 56f
    private val triggerW = 56f
    private val itemH = 52f
    private val itemW = 180f
    private val gap = 6f
    private val corner = 16f

    private val triggerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE05B8DEF.toInt(); style = Paint.Style.FILL
    }
    private val itemBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDD1E293B.toInt(); style = Paint.Style.FILL
    }
    private val itemStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5B8DEF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 24f; textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE2E8F0.toInt(); textSize = 16f; textAlign = Paint.Align.LEFT
    }

    private var expanded = false
    private var animProgress = 0f
    private var animator: ValueAnimator? = null

    // Dragging
    private var initialX = 0f; private var initialY = 0f
    private var initialTouchX = 0f; private var initialTouchY = 0f
    private var isDragging = false
    private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null

    fun attachWindowManager(wm: WindowManager, lp: WindowManager.LayoutParams) {
        this.wm = wm
        this.lp = lp
    }

    fun expandedHeight(): Float = (triggerH + items.size * (itemH + gap) + gap) * 1.1f

    fun toggle() {
        if (expanded) collapse() else expand()
    }

    fun expand() {
        expanded = true
        animator?.cancel()
        animator = ValueAnimator.ofFloat(animProgress, 1f).apply {
            duration = 220; interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun collapse() {
        expanded = false
        animator?.cancel()
        animator = ValueAnimator.ofFloat(animProgress, 0f).apply {
            duration = 180; interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun isExpanded() = expanded

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cx = triggerW / 2f

        // Draw expanded items
        if (animProgress > 0.01f) {
            for (i in items.indices) {
                val y = (triggerH + gap + i * (itemH + gap)) * animProgress
                val alpha = (animProgress * 255).toInt().coerceIn(0, 255)
                val rx = cx - itemW / 2f + triggerW / 2f
                val ry = y

                itemBg.alpha = alpha; itemStroke.alpha = alpha
                val rect = RectF(rx, ry, rx + itemW, ry + itemH)
                c.drawRoundRect(rect, corner, corner, itemBg)
                c.drawRoundRect(rect, corner, corner, itemStroke)

                iconPaint.alpha = alpha
                c.drawText(items[i].icon, rx + 24f, ry + itemH / 2f + 8f, iconPaint)

                labelPaint.alpha = alpha
                c.drawText(items[i].label, rx + 50f, ry + itemH / 2f + 6f, labelPaint)
            }
        }

        // Trigger button
        val tx = 0f
        val ty = 0f
        val tRect = RectF(tx, ty, tx + triggerW, ty + triggerH)
        c.drawRoundRect(tRect, corner, corner, triggerPaint)
        c.drawRoundRect(tRect, corner, corner, itemStroke.apply { strokeWidth = 2f })
        iconPaint.alpha = 255; iconPaint.textSize = 28f
        c.drawText(if (expanded) "✕" else "☰", tx + triggerW / 2f, ty + triggerH / 2f + 10f, iconPaint)
        iconPaint.textSize = 24f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tx = 0f; val ty = 0f
        val triggerRect = RectF(tx, ty, tx + triggerW, ty + triggerH)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = lp?.x?.toFloat() ?: 0f
                initialY = lp?.y?.toFloat() ?: 0f
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false

                // Tap on trigger
                if (triggerRect.contains(event.x, event.y)) {
                    isDragging = false // Assume not dragging initially
                    return true
                }

                // Tap on item
                if (expanded && animProgress > 0.5f) {
                    val cx = triggerW / 2f
                    for (i in items.indices) {
                        val y = (triggerH + gap + i * (itemH + gap)) * animProgress
                        val rx = cx - itemW / 2f + triggerW / 2f
                        if (event.x in (rx - 10)..(rx + itemW + 10) &&
                            event.y in (y - 10)..(y + itemH + 10)) {
                            collapse()
                            postDelayed({ items[i].onClick() }, 250)
                            return true
                        }
                    }
                }

                // Tap outside → collapse
                if (expanded) { collapse(); return true }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (abs(dx) > 10 || abs(dy) > 10) {
                    isDragging = true
                    lp?.x = (initialX - dx).toInt() // Inverted X for Gravity.END
                    lp?.y = (initialY + dy).toInt()
                    wm?.updateViewLayout(this, lp)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    isDragging = false
                    return true // Consume event if it was a drag
                } else {
                    // This is a tap on the trigger after all (not a drag)
                    if (triggerRect.contains(event.x, event.y)) {
                        toggle()
                        return true
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
