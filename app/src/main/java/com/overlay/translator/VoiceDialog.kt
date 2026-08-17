package com.overlay.translator

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

object VoiceDialog {
    private const val TAG = "VoiceDialog"

    fun show(ctx: Context, wm: WindowManager, currentVoice: String?, onSelect: (String, VoiceKind) -> Unit) {
        var tts: TextToSpeech? = null

        tts = TextToSpeech(ctx) { status ->
            if (status != TextToSpeech.SUCCESS) {
                showSimpleDialog(ctx, wm, "TTS не найден", "Установите Google TTS, RHVoice, Sherpa-ONNX") {
                    try { tts?.shutdown() } catch (_: Exception) {}
                }
                return@TextToSpeech
            }
            tts?.language = Locale("ru", "RU")
            val voices = VoiceHelper.russianVoices(tts)
            if (voices.isEmpty()) {
                showSimpleDialog(ctx, wm, "Нет русских голосов", "Установите движок TTS с русскими голосами") {
                    try { tts?.shutdown() } catch (_: Exception) {}
                }
                return@TextToSpeech
            }

            val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            container.addView(DialogOverlay.title(ctx, "🗣 Выбор голоса"))

            var selectedIndex = voices.indexOfFirst { it.name == currentVoice }.coerceAtLeast(0)
            val itemsList = mutableListOf<TextView>()

            for (i in voices.indices) {
                val v = voices[i]
                val kind = VoiceHelper.classify(v)
                val net = if (v.isNetworkConnectionRequired) "☁" else "📱"
                val label = "$net  ${v.name.substringAfterLast(":")} · ${kind.name}"
                val prefix = if (i == selectedIndex) "●" else "○"
                val tv = DialogOverlay.item(ctx, "$prefix  $label")
                tv.setOnClickListener {
                    val newPrefix = if (itemsList.indexOf(tv) == selectedIndex) "●" else "○"
                    tv.text = "$newPrefix  $label"
                    itemsList.forEachIndexed { idx, other ->
                        if (other !== tv) {
                            val otherPrefix = if (idx == selectedIndex) "○" else ""
                            // safe: itemsList indexes match voices indexes
                            val cur = if (idx == itemsList.indexOf(tv)) "○" else otherPrefix
                            val v2 = voices[idx]
                            val net2 = if (v2.isNetworkConnectionRequired) "☁" else "📱"
                            val lbl2 = "$net2  ${v2.name.substringAfterLast(":")} · ${VoiceHelper.classify(v2).name}"
                            other.text = "$cur  $lbl2"
                        }
                    }
                    selectedIndex = itemsList.indexOf(tv)
                }
                container.addView(tv)
                itemsList.add(tv)
            }

            // Buttons
            val btnRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 0)
            }
            val btnTest = Button(ctx).apply {
                text = "▶ Прослушать"
                setOnClickListener {
                    val v = voices.getOrNull(selectedIndex) ?: return@setOnClickListener
                    try {
                        tts?.voice = v
                        tts?.speak("Проверка голоса. Привет.", TextToSpeech.QUEUE_FLUSH, null, "preview")
                    } catch (e: Exception) { Log.w(TAG, "test failed", e) }
                }
            }
            val btnOk = Button(ctx).apply {
                text = "✓ Выбрать"
                setOnClickListener {
                    val v = voices.getOrNull(selectedIndex) ?: return@setOnClickListener
                    try {
                        tts?.voice = v
                        tts?.speak("Голос выбран", TextToSpeech.QUEUE_FLUSH, null, "sel")
                    } catch (_: Exception) {}
                    onSelect(v.name, VoiceHelper.classify(v))
                    DialogOverlay.dismiss()
                    try { tts?.shutdown() } catch (_: Exception) {}
                }
            }
            val btnClose = Button(ctx).apply {
                text = "✕"
                setOnClickListener {
                    DialogOverlay.dismiss()
                    try { tts?.shutdown() } catch (_: Exception) {}
                }
            }
            btnRow.addView(btnTest)
            btnRow.addView(btnOk)
            btnRow.addView(btnClose)
            container.addView(DialogOverlay.divider(ctx))
            container.addView(btnRow)

            DialogOverlay.show(ctx, wm, container)
        }
    }

    private fun showSimpleDialog(ctx: Context, wm: WindowManager, title: String, msg: String, onDismiss: () -> Unit) {
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(DialogOverlay.title(ctx, title))
        container.addView(DialogOverlay.item(ctx, msg))
        val btn = Button(ctx).apply {
            text = "OK"
            setOnClickListener {
                DialogOverlay.dismiss()
                onDismiss()
            }
        }
        container.addView(DialogOverlay.divider(ctx))
        container.addView(btn)
        DialogOverlay.show(ctx, wm, container)
    }
}
