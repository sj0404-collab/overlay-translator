package com.overlay.translator

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import java.util.Locale

object VoiceDialog {
    private const val TAG = "VoiceDialog"

    fun show(ctx: Context, wm: WindowManager, currentVoice: String?, onSelect: (String, VoiceKind) -> Unit) {
        var tts: TextToSpeech? = null
        var ready = false

        tts = TextToSpeech(ctx) { status ->
            if (status != TextToSpeech.SUCCESS) {
                showSimpleDialog(ctx, wm, "TTS не найден", "Установите Google TTS, RHVoice, Sherpa-ONNX") {
                    try { tts?.shutdown() } catch (_: Exception) {}
                }
                return@TextToSpeech
            }
            ready = true
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
                val tv = DialogOverlay.item(ctx, "○  $label").apply {
                    setOnClickListener {
                        itemsList.forEachIndexed { idx, tt ->
                            tt.text = if (idx == i) "● $label".let { label_text ->
                                label_text.replace("○", "●")
                            } else {
                                tt.text.toString().replace("●", "○")
                            }
                        }
                        selectedIndex = i
                    }
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

            // Highlight default selection
            itemsList.getOrNull(selectedIndex)?.performClick()

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
