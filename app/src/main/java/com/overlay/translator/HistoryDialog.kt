package com.overlay.translator

import android.content.Context
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView

object HistoryDialog {

    fun show(ctx: Context, wm: WindowManager) {
        val items = ScanHistory.recent(ctx, 20)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(DialogOverlay.title(ctx,
            if (items.isEmpty()) "📋 История (пусто)" else "📋 История сканирований"))

        if (items.isEmpty()) {
            root.addView(DialogOverlay.item(ctx, "Сделайте первый скан"))
        } else {
            for (i in items.indices) {
                val (time, ocr, tr) = items[i]
                val entry = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(20, 14, 20, 14)
                }
                entry.addView(android.widget.TextView(ctx).apply {
                    text = "⏰ $time"
                    setTextColor(0xFF64748B.toInt())
                    textSize = 11f
                })
                if (ocr.isNotBlank()) {
                    entry.addView(android.widget.TextView(ctx).apply {
                        text = "🔍 ${ocr.take(120)}${if (ocr.length > 120) "..." else ""}"
                        setTextColor(0xFFE8EEF8.toInt())
                        textSize = 13f
                    })
                }
                if (tr.isNotBlank() && tr != ocr) {
                    entry.addView(android.widget.TextView(ctx).apply {
                        text = "📝 ${tr.take(120)}${if (tr.length > 120) "..." else ""}"
                        setTextColor(0xFF5B8DEF.toInt())
                        textSize = 13f
                    })
                }
                root.addView(entry)
                if (i < items.size - 1) root.addView(DialogOverlay.divider(ctx))
            }
        }

        var scroll: ScrollView? = null
        scroll = ScrollView(ctx).apply {
            isFillViewport = false
            addView(root)
        }

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(scroll)
        if (items.isNotEmpty()) {
            val btnRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 0)
            }
            val btnClear = Button(ctx).apply {
                text = "🗑 Очистить"
                setOnClickListener {
                    ScanHistory.clear(ctx)
                    DialogOverlay.dismiss()
                }
            }
            val btnClose = Button(ctx).apply {
                text = "✕"
                setOnClickListener { DialogOverlay.dismiss() }
            }
            btnRow.addView(btnClear)
            btnRow.addView(btnClose)
            col.addView(DialogOverlay.divider(ctx))
            col.addView(btnRow)
        } else {
            val btnClose = Button(ctx).apply {
                text = "Закрыть"
                setOnClickListener { DialogOverlay.dismiss() }
            }
            col.addView(DialogOverlay.divider(ctx))
            col.addView(btnClose)
        }
        DialogOverlay.show(ctx, wm, col)
    }
}
