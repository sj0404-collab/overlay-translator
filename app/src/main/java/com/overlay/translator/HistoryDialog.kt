package com.overlay.translator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView

object HistoryDialog {

    fun show(ctx: Context, wm: WindowManager) {
        val items = ScanHistory.recent(ctx, 30)

        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
            setBackgroundColor(0xFF0B1220.toInt())
        }

        // Title row with ✕
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }
        titleRow.addView(TextView(ctx).apply {
            text = if (items.isEmpty()) "📋 История (пусто)" else "📋 История сканирований"
            setTextColor(0xFFE2E8F0.toInt()); textSize = 18f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        titleRow.addView(Button(ctx).apply {
            text = "✕"; textSize = 20f
            setOnClickListener { DialogOverlay.dismiss() }
            setPadding(24, 8, 24, 8)
        })
        outer.addView(titleRow)

        if (items.isEmpty()) {
            outer.addView(TextView(ctx).apply {
                text = "Сделайте первый скан"
                setTextColor(0xFF94A3B8.toInt()); textSize = 14f
                setPadding(20, 30, 20, 30)
            })
        } else {
            val scroll = ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            for (item in items) {
                val (time, ocr, tr) = item
                list.addView(buildEntry(ctx, time, ocr, tr))
                list.addView(LinearLayout(ctx).apply {
                    setBackgroundColor(0xFF334155.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1)
                })
            }
            scroll.addView(list)
            outer.addView(scroll)
        }

        // Bottom row
        outer.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
            if (items.isNotEmpty()) addView(Button(ctx).apply {
                text = "🗑 Очистить"; layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                setOnClickListener { ScanHistory.clear(ctx); DialogOverlay.dismiss() }
            })
            addView(Button(ctx).apply {
                text = "Закрыть"; layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                setOnClickListener { DialogOverlay.dismiss() }
            })
        })

        DialogOverlay.show(ctx, wm, outer,
            (ctx.resources.displayMetrics.heightPixels * 0.7f).toInt())
    }

    private fun buildEntry(ctx: Context, time: String, ocr: String, tr: String): LinearLayout {
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 12, 20, 12)
            tag = false // expanded = false
        }

        wrap.addView(TextView(ctx).apply {
            text = "⏰ $time"
            setTextColor(0xFF64748B.toInt()); textSize = 11f
        })

        if (ocr.isNotBlank()) {
            val maxLen = 100
            val short = ocr.take(maxLen) + if (ocr.length > maxLen) "…" else ""
            val expandable = TextView(ctx).apply {
                text = "🔍 $short"
                setTextColor(0xFFE2E8F0.toInt()); textSize = 13f
                if (ocr.length > maxLen) {
                    setOnLongClickListener {
                        text = if ((wrap.tag as? Boolean) == true) {
                            wrap.tag = false
                            "🔍 $short"
                        } else {
                            wrap.tag = true
                            "🔍 $ocr"
                        }
                        true
                    }
                }
            }
            wrap.addView(expandable)

            // Also allow single tap for copy to clipboard
            expandable.setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ocr", ocr))
            }
        }

        if (tr.isNotBlank() && tr != ocr) {
            val maxLen = 100
            val short = tr.take(maxLen) + if (tr.length > maxLen) "…" else ""
            val expandable = TextView(ctx).apply {
                text = "📝 $short"
                setTextColor(0xFF5B8DEF.toInt()); textSize = 13f
                if (tr.length > maxLen) {
                    setOnLongClickListener {
                        text = if ((wrap.tag as? Boolean) == true) {
                            wrap.tag = false
                            "📝 $short"
                        } else {
                            wrap.tag = true
                            "📝 $tr"
                        }
                        true
                    }
                }
            }
            wrap.addView(expandable)
            expandable.setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("tr", tr))
            }
        }

        return wrap
    }
}
