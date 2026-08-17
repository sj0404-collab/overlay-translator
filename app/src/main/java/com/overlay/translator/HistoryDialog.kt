package com.overlay.translator

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Shows the last scan results in a scrollable overlay dialog.
 */
object HistoryDialog {

    fun show(ctx: Context) {
        val items = ScanHistory.recent(ctx, 20)
        if (items.isEmpty()) {
            AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("История")
                .setMessage("Пока пусто — сделайте первый скан")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(0xFF0B1220.toInt())
        }

        for ((time, ocr, tr) in items) {
            // Timestamp
            layout.addView(TextView(ctx).apply {
                text = "⏰ $time"
                setTextColor(0xFF64748B.toInt())
                textSize = 12f
                setPadding(0, 16, 0, 4)
            })
            // OCR text
            if (ocr.isNotBlank()) {
                layout.addView(TextView(ctx).apply {
                    text = "🔍 ${ocr.take(200)}"
                    setTextColor(0xFFE8EEF8.toInt())
                    textSize = 14f
                    setPadding(0, 2, 0, 2)
                })
            }
            // Translation
            if (tr.isNotBlank() && tr != ocr) {
                layout.addView(TextView(ctx).apply {
                    text = "📝 ${tr.take(200)}"
                    setTextColor(0xFF5B8DEF.toInt())
                    textSize = 14f
                    setPadding(0, 2, 0, 8)
                })
            }
            // Divider
            layout.addView(TextView(ctx).apply {
                setPadding(0, 0, 0, 8)
            })
        }

        val scroll = ScrollView(ctx).apply {
            addView(layout, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("📋 История сканирований")
            .setView(scroll)
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("Очистить") { _, _ -> ScanHistory.clear(ctx) }
            .show()
    }
}
