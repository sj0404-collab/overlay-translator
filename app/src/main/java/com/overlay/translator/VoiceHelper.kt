package com.overlay.translator

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

enum class VoiceKind { FEMALE, MALE, TEEN, OTHER }

object VoiceHelper {
    /** Russian voice name hints lifted from Yomihon's reader (`Svetlana /
     *  Dmitriy`) plus extra names that ship with RHVoice, Google TTS and
     *  Sherpa-ONNX. Used purely for UI labels. */
    private val femaleHints = listOf(
        "female", "woman", "svetlana", "milena", "oksana", "irina", "jane", "ksenia",
        "alena", "yelena", "elena", "anna", "maria", "natalia", "natalya", "tatyana",
        "жен", "женск", "девуш",
    )
    private val maleHints = listOf(
        "male", "man", "dmitry", "dmitri", "ermil", "filipp", "zahar", "pavel",
        "alexander", "maxim", "andrey", "ivan", "sergey", "муж",
    )
    private val teenHints = listOf("child", "kid", "teen", "young", "дет", "подрост")
    /** System TTS voices we should ignore even if locale matches. */
    private val blacklist = listOf("locale", "default", "test")

    fun russianVoices(tts: TextToSpeech?): List<Voice> {
        val all = tts?.voices ?: return emptyList()
        return all.filter {
            val t = (it.locale.language + " " + it.locale.toLanguageTag() + " " + it.name).lowercase(Locale.US)
            (t.contains("ru") || it.locale.language.equals("ru", true)) &&
                blacklist.none { b -> t.contains(b) }
        }.sortedBy { it.name }
    }

    fun classify(v: Voice): VoiceKind {
        val n = (v.name + " " + v.locale.toLanguageTag()).lowercase(Locale.US)
        return when {
            teenHints.any { n.contains(it) } -> VoiceKind.TEEN
            maleHints.any { n.contains(it) } -> VoiceKind.MALE
            femaleHints.any { n.contains(it) } -> VoiceKind.FEMALE
            else -> VoiceKind.OTHER
        }
    }

    fun pick(tts: TextToSpeech?, kind: VoiceKind, exactName: String?): Voice? {
        val ru = russianVoices(tts)
        if (exactName != null) ru.find { it.name == exactName }?.let { return it }
        val group = ru.filter { classify(it) == kind }
        val preferred = when (kind) {
            VoiceKind.FEMALE -> group.firstOrNull { it.name.contains("svetlana", true) } ?: group.firstOrNull()
            VoiceKind.MALE -> group.firstOrNull { it.name.contains("dmitr", true) } ?: group.firstOrNull()
            else -> group.firstOrNull()
        }
        return preferred ?: ru.firstOrNull()
    }

    fun apply(tts: TextToSpeech?, kind: VoiceKind, exactName: String?) {
        tts ?: return
        tts.language = Locale("ru", "RU")
        tts.setSpeechRate(0.96f)
        pick(tts, kind, exactName)?.let { tts.voice = it }
    }

    /** Light wrapper to keep the public selection used by Yomihon's reader
     *  (e.g. `ru-ru-x-dfa-network`) compatible. If the given key is a real
     *  installed voice name, it is honoured; otherwise we fall back to the
     *  kind-based picker. */
    fun applyCompat(tts: TextToSpeech?, kind: VoiceKind, keyOrName: String?) {
        if (keyOrName.isNullOrBlank()) {
            apply(tts, kind, null); return
        }
        val exact = russianVoices(tts).firstOrNull { it.name == keyOrName }
        if (exact != null) {
            apply(tts, kind, exact.name)
        } else {
            apply(tts, kind, null)
        }
    }
}
