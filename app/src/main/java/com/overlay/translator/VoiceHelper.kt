package com.overlay.translator

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

enum class VoiceKind { FEMALE, MALE, TEEN, OTHER }

object VoiceHelper {
    fun russianVoices(tts: TextToSpeech?): List<Voice> {
        val all = tts?.voices ?: return emptyList()
        return all.filter {
            val t = (it.locale.language + " " + it.locale.toLanguageTag() + " " + it.name).lowercase(Locale.US)
            t.contains("ru") || it.locale.language.equals("ru", true)
        }.sortedBy { it.name }
    }

    fun classify(v: Voice): VoiceKind {
        val n = (v.name + " " + v.locale.toLanguageTag()).lowercase(Locale.US)
        return when {
            n.contains("child") || n.contains("kid") || n.contains("teen") ||
                n.contains("young") || n.contains("дет") || n.contains("подрост") -> VoiceKind.TEEN
            n.contains("male") || n.contains("man") || n.contains("dmitri") ||
                n.contains("ermil") || n.contains("filipp") || n.contains("муж") -> VoiceKind.MALE
            n.contains("female") || n.contains("woman") || n.contains("milena") ||
                n.contains("oksana") || n.contains("jane") || n.contains("жен") -> VoiceKind.FEMALE
            else -> VoiceKind.OTHER
        }
    }

    fun pick(tts: TextToSpeech?, kind: VoiceKind, exactName: String?): Voice? {
        val ru = russianVoices(tts)
        if (exactName != null) ru.find { it.name == exactName }?.let { return it }
        val group = ru.filter { classify(it) == kind }
        return group.firstOrNull() ?: ru.firstOrNull()
    }

    fun apply(tts: TextToSpeech?, kind: VoiceKind, exactName: String?) {
        tts ?: return
        tts.language = Locale("ru", "RU")
        pick(tts, kind, exactName)?.let { tts.voice = it }
    }
}
