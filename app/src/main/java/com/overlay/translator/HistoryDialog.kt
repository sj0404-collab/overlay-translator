package com.overlay.translator

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
        val outer = RelativeLayout(ctx).apply {
            setPadding(40, 80, 40, 40)
        }

        // Title + close ✕ on the same row, anchored to top
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            id = android.R.id.text1
        }
        val title = TextView(ctx).apply {
            text = if (items.isEmpty()) "📋 История (пусто)" else "📋 История сканирований"
            setTextColor(0xFFE2E8F0.toInt())
            textSize = 18f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        }
        titleRow.addView(title)

        // Spacer
        val spacer = ViewGroup.LayoutParams(0, 0); spacer.weight = 0f
        val spacerView = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        titleRow.addView(spacerView)

        // Always-visible close ✕
        val closeBtn = Button(ctx).apply {
            text = "✕"
            textSize = 18f
            setOnClickListener { DialogOverlay.dismiss() }
            setPadding(20, 10, 20, 10)
        }
        titleRow.addView(closeBtn)

        // Body
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (items.isEmpty()) {
            body.addView(TextView(ctx).apply {
                text = "Сделайте первый скан для появления записей."
                setTextColor(0xFF94A3B8.toInt())
                textSize = 14f
                setPadding(20, 30, 20, 30)
            })
        } else {
            val scroll = ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (ctx.resources.displayMetrics.heightPixels / 2).coerceAtMost(800)
                )
            }
            val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            for (i in items.indices) {
                val (time, ocr, tr) = items[i]
                val entry = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(20, 14, 20, 14)
                }
                entry.addView(TextView(ctx).apply {
                    text = "⏰ $time"
                    setTextColor(0xFF64748B.toInt())
                    textSize = 11f
                })
                if (ocr.isNotBlank()) {
                    entry.addView(TextView(ctx).apply {
                        text = "🔍 ${ocr.take(140)}${if (ocr.length > 140) "..." else ""}"
                        setTextColor(0xFFE2E8F0.toInt())
                        textSize = 13f
                    })
                }
                if (tr.isNotBlank() && tr != ocr) {
                    entry.addView(TextView(ctx).apply {
                        text = "📝 ${tr.take(140)}${if (tr.length > 140) "..." else ""}"
                        setTextColor(0xFF5B8DEF.toInt())
                        textSize = 13f
                    })
                }
                list.addView(entry)
                if (i < items.size - 1) {
                    val div = View(ctx).apply {
                        setBackgroundColor(0xFF334155.toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 1
                        )
                    }
                    list.addView(div)
                }
            }
            scroll.addView(list)
            body.addView(scroll)
        }

        // Bottom clear button
        if (items.isNotEmpty()) {
            val clearBtn = Button(ctx).apply {
                text = "🗑 Очистить"
                setOnClickListener {
                    ScanHistory.clear(ctx)
                    DialogOverlay.dismiss()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 20 }
            }
            body.addView(clearBtn)
        }

        outer.addView(titleRow, RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { addRule(RelativeLayout.ALIGN_PARENT_TOP) })

        outer.addView(body, RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.BELOW, titleRow.id)
            topMargin = 20
        })

        DialogOverlay.show(ctx, wm, outer)
    }
}
