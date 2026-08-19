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
 * Vertical floating menu (SAO-style).
 * Single trigger button expands into vertical list.
 * Draggable by the trigger button.
 */
class VerticalMenuView(context: Context, private val items: List<VerticalItem>) : View(context) {

    data class VerticalItem(val label: String, val icon: String, val onClick: () -> Unit)

    private val triggerH = 64f; private val triggerW = 64f
    private val itemH = 56f; private val itemW = 200f
    private val gap = 8f; private val corner = 20f

    private val triggerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE05B8DEF.toInt(); style = Paint.Style.FILL }
    private val itemBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDD1E293B.toInt(); style = Paint.Style.FILL }
    private val itemStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5B8DEF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f; textAlign = Paint.Align.CENTER }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE2E8F0.toInt(); textSize = 18f; textAlign = Paint.Align.LEFT }

    private var expanded = false; private var animProgress = 0f; private var animator: ValueAnimator? = null

    private var initialX = 0f; private var initialY = 0f
    private var touchStartX = 0f; private var touchStartY = 0f
    private var isDragging = false
    private var wm: WindowManager? = null; private var lp: WindowManager.LayoutParams? = null

    fun attachWindowManager(wm: WindowManager, lp: WindowManager.LayoutParams) { this.wm = wm; this.lp = lp }
    fun expandedHeight(): Float = (triggerH + items.size * (itemH + gap) + gap) * 1.1f
    fun toggle() { if (expanded) collapse() else expand() }
    fun expand() { expanded = true; animator?.cancel(); animator = ValueAnimator.ofFloat(animProgress, 1f).apply { duration = 220; interpolator = AccelerateDecelerateInterpolator(); addUpdateListener { animProgress = it.animatedValue as Float; invalidate() }; start() } }
    fun collapse() { expanded = false; animator?.cancel(); animator = ValueAnimator.ofFloat(animProgress, 0f).apply { duration = 180; interpolator = AccelerateDecelerateInterpolator(); addUpdateListener { animProgress = it.animatedValue as Float; invalidate() }; start() } }
    fun isExpanded() = expanded

    override fun onDraw(c: Canvas) {
        super.onDraw(c); val cx = triggerW / 2f
        if (animProgress > 0.01f) {
            for (i in items.indices) {
                val y = (triggerH + gap + i * (itemH + gap)) * animProgress
                val alpha = (animProgress * 255).toInt().coerceIn(0, 255)
                val rx = cx - itemW / 2f + triggerW / 2f; val ry = y
                itemBg.alpha = alpha; itemStroke.alpha = alpha
                val rect = RectF(rx, ry, rx + itemW, ry + itemH)
                c.drawRoundRect(rect, corner, corner, itemBg); c.drawRoundRect(rect, corner, corner, itemStroke)
                iconPaint.alpha = alpha; c.drawText(items[i].icon, rx + 24f, ry + itemH / 2f + 10f, iconPaint)
                labelPaint.alpha = alpha; c.drawText(items[i].label, rx + 52f, ry + itemH / 2f + 6f, labelPaint)
            }
        }
        val tRect = RectF(0f, 0f, triggerW, triggerH)
        c.drawRoundRect(tRect, corner, corner, triggerPaint)
        c.drawRoundRect(tRect, corner, corner, itemStroke.apply { strokeWidth = 2.5f })
        iconPaint.alpha = 255; iconPaint.textSize = 30f
        c.drawText(if (expanded) "✕" else "☰", triggerW / 2f, triggerH / 2f + 12f, iconPaint)
        iconPaint.textSize = 28f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val triggerRect = RectF(0f, 0f, triggerW, triggerH)
        val dx = event.rawX - touchStartX; val dy = event.rawY - touchStartY

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = lp?.x?.toFloat() ?: 0f; initialY = lp?.y?.toFloat() ?: 0f
                touchStartX = event.rawX; touchStartY = event.rawY; isDragging = false
                // Check view-local coords for trigger hit
                val inTrigger = event.x in 0f..triggerW && event.y in 0f..triggerH
                if (inTrigger) return true // potential drag
                // Expanded items
                if (expanded && animProgress > 0.5f) {
                    val cx = triggerW / 2f
                    for (i in items.indices) {
                        val y = (triggerH + gap + i * (itemH + gap)) * animProgress
                        val rx = cx - itemW / 2f + triggerW / 2f
                        if (event.x in (rx - 10)..(rx + itemW + 10) && event.y in (y - 10)..(y + itemH + 10)) {
                            collapse(); postDelayed({ items[i].onClick() }, 200); return true
                        }
                    }
                }
                if (expanded) { collapse(); return true }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val viewTouchX = event.x; val viewTouchY = event.y
                if (triggerRect.contains(viewTouchX, viewTouchY) || isDragging) {
                    if (!isDragging && (abs(dx) > 8 || abs(dy) > 8)) isDragging = true
                    if (isDragging) {
                        lp?.x = (initialX - dx).toInt()
                        lp?.y = (initialY + dy).toInt()
                        try { wm?.updateViewLayout(this, lp) } catch (_: Exception) {}
                        return true
                    }
                }
                return false
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) { isDragging = false; return true }
                if (triggerRect.contains(event.x, event.y)) { toggle(); return true }
            }
        }
        return super.onTouchEvent(event)
    }
}
