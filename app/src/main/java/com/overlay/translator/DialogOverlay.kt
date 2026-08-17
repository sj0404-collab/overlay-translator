package com.overlay.translator

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Custom dialog shown as an overlay window via [WindowManager].
 * Required because AlertDialog cannot be displayed from a Service
 * context (which is what OverlayService is).
 */
object DialogOverlay {

    private var current: View? = null
    private var wmRef: WeakWM? = null

    class WeakWM(val wm: WindowManager)

    fun show(ctx: Context, wm: WindowManager, content: View, heightPx: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
        dismiss()
        val bg = GradientDrawable().apply {
            cornerRadius = 24f
            setColor(0xFF0B1220.toInt())
            setStroke(2, 0xFF5B8DEF.toInt())
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            background = bg
        }
        container.addView(content)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.CENTER
        try {
            wm.addView(container, lp)
            current = container
            wmRef = WeakWM(wm)
        } catch (e: Exception) {
            android.util.Log.e("DialogOverlay", "show failed", e)
        }
    }

    fun dismiss() {
        val view = current
        val ref = wmRef
        current = null; wmRef = null
        if (view != null && ref != null) {
            try { ref.wm.removeView(view) } catch (_: Exception) {}
        }
    }

    /** Build a title TextView with a standard style. */
    fun title(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(0xFFCBD5E1.toInt())
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, 12)
    }

    /** Build a label for list items. */
    fun item(ctx: Context, text: String, padding: Int = 16): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(0xFFE2E8F0.toInt())
        textSize = 15f
        setPadding(padding, padding, padding, padding)
        setBackgroundColor(0xFF1E293B.toInt())
    }

    /** Build a divider. */
    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(0xFF334155.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }
}
