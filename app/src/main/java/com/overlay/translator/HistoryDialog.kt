package com.overlay.translator

import android.content.Context
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object HistoryDialog {

    fun show(ctx: Context, wm: WindowManager) {
        val items = ScanHistory.recent(ctx, 30)
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(DialogOverlay.title(ctx,
            if (items.isEmpty()) "📋 История (пусто)" else "📋 История сканирований"))

        if (items.isEmpty()) {
            container.addView(DialogOverlay.item(ctx, "Сделайте первый скан"))
        } else {
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
                        setTextColor(0xFFE8EEF8.toInt())
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
                container.addView(entry)
                if (i < items.size - 1) container.addView(DialogOverlay.divider(ctx))
            }
        }

        val scroll = ScrollWrap(ctx).apply {
            addView(container)
        }

        // Action row with Clear + Close
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }
        if (items.isNotEmpty()) {
            actionRow.addView(Button(ctx).apply {
                text = "🗑 Очистить"
                setOnClickListener {
                    ScanHistory.clear(ctx)
                    DialogOverlay.dismiss()
                }
            })
        }
        actionRow.addView(Button(ctx).apply {
            text = "✕ Закрыть"
            setOnClickListener { DialogOverlay.dismiss() }
        })

        val outerContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            setBackgroundColor(0xFF0B1220.toInt())
        }
        outerContainer.addView(DialogOverlay.title(ctx,
            if (items.isEmpty()) "📋 История (пусто)" else "📋 История сканирований"))
        outerContainer.addView(scroll)
        outerContainer.addView(DialogOverlay.divider(ctx))
        outerContainer.addView(actionRow)

        DialogOverlay.show(ctx, wm, outerContainer)
    }
}

/** Simple wrapper that lets us add views and overflow. */
private class ScrollWrap(ctx: Context) : android.widget.ScrollView(ctx) {
    init {
        isFillViewport = true
        setBackgroundColor(0xFF0B1220.toInt())
    }
}
