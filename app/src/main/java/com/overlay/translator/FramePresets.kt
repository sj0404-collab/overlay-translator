package com.overlay.translator

import android.graphics.RectF

/**
 * Ready-made frame shapes per use case. Each preset is a band expressed as
 * fractions of the safe area (screen minus system bars and gesture insets).
 */
object FramePresets {
    data class Preset(
        val id: String,
        val label: String,
        val icon: String,
        val top: Float, val bottom: Float, val left: Float, val right: Float,
    )

    val PAGE = Preset("page", "Страница", "📖", 0.05f, 0.97f, 0.04f, 0.96f)
    val CHAT = Preset("chat", "Чат", "💬", 0.08f, 0.60f, 0.02f, 0.98f)
    val SUBS = Preset("subs", "Субтитры / видео", "🎬", 0.60f, 0.92f, 0.02f, 0.98f)
    val ARTICLE = Preset("article", "Статья (средняя колонка)", "🗒", 0.10f, 0.90f, 0.30f, 0.70f)

    val ALL = listOf(PAGE, CHAT, SUBS, ARTICLE)

    fun byId(id: String): Preset? = ALL.firstOrNull { it.id == id }

    /** Map a preset to an actual screen rect, keeping clear of safe-area edges. */
    fun compute(p: Preset, w: Int, h: Int, safeTop: Int, safeBottom: Int): RectF {
        val x0 = w * p.left
        val x1 = w * p.right
        val sy = safeTop.toFloat()
        val ey = (h - safeBottom).coerceAtLeast(safeTop + 1).toFloat()
        val y0 = sy + (ey - sy) * p.top
        val y1 = sy + (ey - sy) * p.bottom
        return RectF(x0, y0, x1, y1)
    }
}