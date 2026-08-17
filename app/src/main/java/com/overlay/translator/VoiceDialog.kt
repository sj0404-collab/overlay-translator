package com.overlay.translator

import android.app.AlertDialog
import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Quick voice picker shown from the overlay menu.
 * Lists Russian system voices, lets user pick one and test it.
 */
object VoiceDialog {

    fun show(ctx: Context, currentVoice: String?, onSelect: (String, VoiceKind) -> Unit) {
        var tts: TextToSpeech? = null
        var voices: List<android.speech.tts.Voice> = emptyList()

        tts = TextToSpeech(ctx) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            tts?.language = Locale("ru", "RU")
            voices = VoiceHelper.russianVoices(tts)
            if (voices.isEmpty()) {
                AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog)
                    .setTitle("Нет голосов")
                    .setMessage("Установите Google TTS, RHVoice или Sherpa-ONNX TTS")
                    .setPositiveButton("OK") { _, _ -> tts?.shutdown() }
                    .show()
                return@TextToSpeech
            }

            val labels = voices.map { v ->
                val kind = VoiceHelper.classify(v)
                val network = if (v.isNetworkConnectionRequired) "☁️" else "📱"
                "$network ${v.locale.displayLanguage} — ${v.name.substringAfterLast(":")} [${kind.name}]"
            }.toTypedArray()

            val checked = voices.indexOfFirst { it.name == currentVoice }.coerceAtLeast(0)

            AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("🗣 Выбор голоса")
                .setSingleChoiceItems(labels, checked) { _, _ -> }
                .setPositiveButton("Выбрать") { d, _ ->
                    val idx = d as AlertDialog
                    val pos = (idx.listView?.checkedItemPosition ?: 0)
                    if (pos >= 0 && pos < voices.size) {
                        val v = voices[pos]
                        val kind = VoiceHelper.classify(v)
                        tts?.voice = v
                        tts?.speak("Голос выбран", TextToSpeech.QUEUE_FLUSH, null, "test")
                        onSelect(v.name, kind)
                    }
                    tts?.shutdown()
                }
                .setNegativeButton("Отмена") { _, _ -> tts?.shutdown() }
                .setNeutralButton("Прослушать") { d, _ ->
                    val pos = (d as AlertDialog).listView?.checkedItemPosition ?: 0
                    if (pos >= 0 && pos < voices.size) {
                        tts?.voice = voices[pos]
                        tts?.speak("Проверка голоса. Привет, это тест озвучки.", TextToSpeech.QUEUE_FLUSH, null, "preview")
                    }
                }
                .setOnDismissListener { tts?.shutdown() }
                .show()
        }
    }
}
