package com.overlay.translator

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Quick preset picker shown as an overlay dialog.
 * Passes the selected [FramePresets.Preset] or null for free-form manual pick.
 */
object FrameDialog {

    fun show(
        ctx: Context,
        wm: WindowManager,
        currentPresetId: String,
        onSelect: (FramePresets.Preset?) -> Unit,
    ) {
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 12)
            setBackgroundColor(0xFF0B1220.toInt())
        }

        // Title row with ✕
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 36, 40, 12)
        }
        titleRow.addView(TextView(ctx).apply {
            text = "📐 Выбор рамки"
            setTextColor(0xFFE2E8F0.toInt()); textSize = 18f
            setTypeface(Typeface.DEFAULT_BOLD)
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        titleRow.addView(Button(ctx).apply {
            text = "✕"; textSize = 20f
            setOnClickListener { DialogOverlay.dismiss() }
            setPadding(24, 8, 24, 8)
        })
        outer.addView(titleRow)

        // Preset buttons
        for (p in FramePresets.ALL) {
            outer.addView(Button(ctx).apply {
                text = "${p.icon}  ${p.label}"
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                textSize = 15f
                setPadding(36, 18, 36, 18)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 6 }
                if (p.id == currentPresetId) setTextColor(0xFF5B8DEF.toInt())
                setOnClickListener { DialogOverlay.dismiss(); onSelect(p) }
            })
        }

        // Manual pick button
        outer.addView(Button(ctx).apply {
            text = "✍️  Вручную — обвести пальцем"
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textSize = 15f
            setPadding(36, 18, 36, 18)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6 }
            if (currentPresetId == "rect") setTextColor(0xFF5B8DEF.toInt())
            setOnClickListener { DialogOverlay.dismiss(); onSelect(null) }
        })

        outer.addView(TextView(ctx).apply {
            text = "Рамка автоматически отступает от системных кнопок и жестов."
            setTextColor(0xFF94A3B8.toInt()); textSize = 12f; setPadding(44, 2, 40, 0)
        })

        DialogOverlay.show(ctx, wm, outer, (ctx.resources.displayMetrics.heightPixels * 0.55f).toInt())
    }
}