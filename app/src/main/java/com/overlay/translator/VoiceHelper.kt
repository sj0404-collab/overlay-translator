package com.overlay.translator

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

enum class VoiceKind { FEMALE, MALE, TEEN, OTHER }

object VoiceHelper {
    private const val TAG = "VoiceHelper"
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
    private val blacklist = listOf("locale", "default", "test")

    fun russianVoices(tts: TextToSpeech?): List<Voice> {
        val all = try { tts?.voices } catch (e: Exception) { Log.w(TAG, "voices() failed", e); null }
            ?: return emptyList()
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
        if (ru.isEmpty()) return null
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
        if (tts == null) return
        try {
            tts.language = Locale("ru", "RU")
        } catch (e: Exception) {
            Log.w(TAG, "language() failed", e)
        }
        try {
            tts.setSpeechRate(0.96f)
        } catch (e: Exception) {
            Log.w(TAG, "setSpeechRate() failed", e)
        }
        try {
            pick(tts, kind, exactName)?.let { tts.voice = it }
        } catch (e: Exception) {
            Log.w(TAG, "set voice failed", e)
        }
    }

    fun applyCompat(tts: TextToSpeech?, kind: VoiceKind, keyOrName: String?) {
        if (keyOrName.isNullOrBlank()) { apply(tts, kind, null); return }
        val exact = russianVoices(tts).firstOrNull { it.name == keyOrName }
        apply(tts, kind, exact?.name)
    }
}
