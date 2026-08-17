package com.overlay.translator

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * SAO-inspired radial floating menu.
 * Central trigger button expands items in a circular arc.
 */
class RadialMenuView(context: Context, private val items: List<RadialItem>) : View(context) {

    data class RadialItem(val label: String, val icon: String, val onClick: () -> Unit)

    private val triggerRadius = 36f
    private val itemRadius = 28f
    private val orbitRadius = 110f

    private val triggerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE05B8DEF.toInt(); style = Paint.Style.FILL
    }
    private val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC1E293B.toInt(); style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5B8DEF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 22f; textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCBD5E1.toInt(); textSize = 18f; textAlign = Paint.Align.CENTER
    }

    private var expanded = false
    private var animProgress = 0f // 0 = collapsed, 1 = expanded
    private var animator: ValueAnimator? = null

    var onClose: (() -> Unit)? = null

    fun toggle() {
        if (expanded) collapse() else expand()
    }

    fun expand() {
        expanded = true
        animator?.cancel()
        animator = ValueAnimator.ofFloat(animProgress, 1f).apply {
            duration = 250; interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun collapse() {
        expanded = false
        animator?.cancel()
        animator = ValueAnimator.ofFloat(animProgress, 0f).apply {
            duration = 200; interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                animProgress = it.animatedValue as Float
                invalidate()
                if (animProgress <= 0.01f) onClose?.invoke()
            }
            start()
        }
    }

    fun isExpanded() = expanded

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cx = width / 2f
        val cy = height / 2f

        if (animProgress > 0.01f) {
            val n = items.size
            val startAngle = -Math.PI / 2.0 // top
            val sweep = Math.PI * 1.4 // ~252 degrees arc

            for (i in items.indices) {
                val angle = startAngle + sweep * i / (n - 1).coerceAtLeast(1)
                val r = orbitRadius * animProgress
                val ix = cx + (r * cos(angle)).toFloat()
                val iy = cy + (r * sin(angle)).toFloat()
                val alpha = (animProgress * 255).toInt().coerceIn(0, 255)

                // item circle
                itemPaint.alpha = alpha
                strokePaint.alpha = alpha
                c.drawCircle(ix, iy, itemRadius * animProgress, itemPaint)
                c.drawCircle(ix, iy, itemRadius * animProgress, strokePaint)

                // icon
                textPaint.alpha = alpha
                c.drawText(items[i].icon, ix, iy + 8f * animProgress, textPaint)

                // label
                labelPaint.alpha = (alpha * 0.8f).toInt()
                c.drawText(items[i].label, ix, iy + itemRadius + 18f * animProgress, labelPaint)
            }
        }

        // trigger button (always on top)
        c.drawCircle(cx, cy, triggerRadius, triggerPaint)
        c.drawCircle(cx, cy, triggerRadius, strokePaint.apply { strokeWidth = 2.5f })
        textPaint.alpha = 255; textPaint.textSize = 26f
        c.drawText(if (expanded) "✕" else "☰", cx, cy + 9f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val cx = width / 2f; val cy = height / 2f
                val dx = event.x - cx; val dy = event.y - cy
                val distTrigger = Math.sqrt((dx * dx + dy * dy).toDouble())

                // Tap on trigger
                if (distTrigger <= triggerRadius + 10) {
                    toggle()
                    return true
                }

                // Tap on item
                if (expanded && animProgress > 0.5f) {
                    val n = items.size
                    val startAngle = -Math.PI / 2.0
                    val sweep = Math.PI * 1.4
                    for (i in items.indices) {
                        val angle = startAngle + sweep * i / (n - 1).coerceAtLeast(1)
                        val r = orbitRadius * animProgress
                        val ix = cx + (r * cos(angle)).toFloat()
                        val iy = cy + (r * sin(angle)).toFloat()
                        val dxi = event.x - ix; val dyi = event.y - iy
                        if (Math.sqrt((dxi * dxi + dyi * dyi).toDouble()) <= itemRadius + 15) {
                            collapse()
                            postDelayed({ items[i].onClick() }, 250)
                            return true
                        }
                    }
                }

                // Tap outside → collapse
                if (expanded) { collapse(); return true }
            }
        }
        return super.onTouchEvent(event)
    }
}
