package com.overlay.translator

import android.app.AlertDialog
import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

object VoiceDialog {
    private const val TAG = "VoiceDialog"

    fun show(ctx: Context, currentVoice: String?, onSelect: (String, VoiceKind) -> Unit) {
        var tts: TextToSpeech? = null
        var ready = false

        tts = TextToSpeech(ctx) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS init failed: $status")
                try {
                    AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog)
                        .setTitle("TTS не найден")
                        .setMessage("Установите Google TTS, RHVoice или Sherpa-ONNX из Play Store")
                        .setPositiveButton("OK", null).show()
                } catch (_: Exception) {}
                return@TextToSpeech
            }
            ready = true
            tts?.language = Locale("ru", "RU")
            val voices = VoiceHelper.russianVoices(tts)
            if (voices.isEmpty()) {
                try {
                    AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog)
                        .setTitle("Нет русских голосов")
                        .setMessage("Установите движок TTS с русскими голосами")
                        .setPositiveButton("OK", null).show()
                } catch (_: Exception) {}
                tts?.shutdown()
                return@TextToSpeech
            }

            val labels = voices.map { v ->
                val kind = VoiceHelper.classify(v)
                val net = if (v.isNetworkConnectionRequired) "☁" else "📱"
                "$net ${v.name.substringAfterLast(":")} [${kind.name}]"
            }.toTypedArray()

            val checked = voices.indexOfFirst { it.name == currentVoice }.coerceAtLeast(0)

            try {
                AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog)
                    .setTitle("🗣 Выбор голоса")
                    .setSingleChoiceItems(labels, checked) { _, _ -> }
                    .setPositiveButton("Выбрать") { d, _ ->
                        val pos = (d as AlertDialog).listView?.checkedItemPosition ?: 0
                        if (pos >= 0 && pos < voices.size) {
                            val v = voices[pos]
                            try {
                                tts?.voice = v
                                tts?.speak("Голос выбран", TextToSpeech.QUEUE_FLUSH, null, "sel")
                            } catch (e: Exception) { Log.w(TAG, "test speak failed", e) }
                            onSelect(v.name, VoiceHelper.classify(v))
                        }
                        try { tts?.shutdown() } catch (_: Exception) {}
                    }
                    .setNegativeButton("Отмена") { _, _ -> try { tts?.shutdown() } catch (_: Exception) {} }
                    .setNeutralButton("Прослушать") { d, _ ->
                        val pos = (d as AlertDialog).listView?.checkedItemPosition ?: 0
                        if (pos >= 0 && pos < voices.size) {
                            try {
                                tts?.voice = voices[pos]
                                tts?.speak("Проверка голоса. Привет, это тест.", TextToSpeech.QUEUE_FLUSH, null, "preview")
                            } catch (e: Exception) { Log.w(TAG, "preview failed", e) }
                        }
                    }
                    .setOnDismissListener { try { tts?.shutdown() } catch (_: Exception) {} }
                    .show()
            } catch (e: Exception) {
                Log.e(TAG, "dialog failed", e)
                try { tts?.shutdown() } catch (_: Exception) {}
            }
        }
    }
}
