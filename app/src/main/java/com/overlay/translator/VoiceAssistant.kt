package com.overlay.translator

import android.util.Log
import java.util.EnumMap
import java.util.Locale

/**
 * Local JSON-based voice assistant. Analyzes text for linguistic cues
 * (morphology, orthography, sentence structure) and automatically selects
 * the appropriate TTS voice + pitch for spoken output.
 */
object VoiceAssistant {
    private const val TAG = "VoiceAssistant"

    data class VoiceDecision(
        val kind: VoiceKind,
        val pitch: Float,
        val rate: Float,
        val reason: String,
    )

    fun analyze(text: String, configuredKind: VoiceKind): VoiceDecision {
        val clean = text.trim()
        if (clean.isBlank()) return defaultDecision(configuredKind)
        val features = extractFeatures(clean)
        val scores: MutableMap<VoiceKind, Float> = EnumMap(VoiceKind::class.java)
        scores[VoiceKind.FEMALE] = features.femaleScore
        scores[VoiceKind.MALE] = features.maleScore
        scores[VoiceKind.TEEN] = features.teenScore
        scores[VoiceKind.OTHER] = features.narratorScore
        scores[configuredKind] = (scores[configuredKind] ?: 0f) + 0.3f
        val best = scores.entries.maxByOrNull { it.value }
        val winner = best?.key ?: configuredKind
        val bestScore = best?.value ?: 0f
        val pitch = when (winner) {
            VoiceKind.MALE -> 0.92f
            VoiceKind.FEMALE -> 1.08f
            VoiceKind.TEEN -> 1.20f
            else -> 0.95f
        }
        val rate = when {
            features.hasExclamation -> 1.05f
            features.hasQuestion -> 0.92f
            features.isShortUtterance -> 1.1f
            else -> 0.96f
        }
        val reason = buildReason(winner, features, bestScore)
        Log.i(TAG, "analyze: kind=$winner pitch=$pitch score=$bestScore")
        return VoiceDecision(winner, pitch, rate, reason)
    }

    private data class TextFeatures(
        val femaleScore: Float,
        val maleScore: Float,
        val teenScore: Float,
        val narratorScore: Float,
        val hasExclamation: Boolean,
        val hasQuestion: Boolean,
        val isShortUtterance: Boolean,
    )

    private fun extractFeatures(text: String): TextFeatures {
        val lower = text.lowercase(Locale.getDefault())
        val words = lower.split(Regex("\\s+")).filter { it.isNotEmpty() }

        val feminineVerbs = words.count { it.matches(Regex(".*(ла|лась|илась|ила|юсь|ается)[а-яё]*")) }
        val masculineVerbs = words.count { it.matches(Regex(".*(ло|лся|ился|ил|ает|ел)[а-яё]*")) }

        val sheWords = words.count { it in listOf("она", "ее", "ей", "своя", "свой") }
        val heWords = words.count { it in listOf("он", "его", "ему", "ним", "свой") }

        val feminineNames = listOf("мария", "анна", "елена", "екатерина", "наталья", "ольга", "татьяна", "ирина", "валентина", "карина", "алина", "вероника", "виктория", "дарья", "марина", "светлана")
        val masculineNames = listOf("александр", "сергей", "дмитрий", "иван", "николай", "виктор", "алексей", "павел", "михаил", "андрей", "владимир", "артем", "илья", "кирилл", "роман")
        val teenNames = listOf("алёша", "соня", "маша", "даша", "саша", "паша")

        val femaleNameScore = words.count { w -> feminineNames.any { n -> w.contains(n) } }.toFloat() * 2f
        val maleNameScore = words.count { w -> masculineNames.any { n -> w.contains(n) } }.toFloat() * 2f
        val teenNameScore = words.count { w -> teenNames.any { n -> w.contains(n) } }.toFloat() * 2f

        val exclCount = text.count { it == '!' || it == '！' }
        val quesCount = text.count { it == '?' || it == '？' }
        val hasExclamation = exclCount >= 1
        val hasQuestion = quesCount >= 1
        val capsRatio = if (text.isNotEmpty()) {
            text.count { it.isUpperCase() }.toFloat() / text.length
        } else 0f

        val isShort = words.size <= 3

        val narratorTopics = listOf("панель", "страница", "глава", "menu", "chat", "настройки", "версия", "скачать", "установ")
        val isNarratorTopic = narratorTopics.any { lower.contains(it) }

        val femaleScore = feminineVerbs * 0.8f + sheWords * 1.0f + femaleNameScore + if (isShort) 0.2f else 0f
        val maleScore = masculineVerbs * 0.8f + heWords * 1.0f + maleNameScore
        val teenScore = teenNameScore + if (lower.contains("подросток") || lower.contains("ребёнок")) 1.5f else 0f
        val narratorScore = if (isNarratorTopic) 1.5f else capsRatio * 0.5f

        return TextFeatures(
            femaleScore = femaleScore,
            maleScore = maleScore,
            teenScore = teenScore,
            narratorScore = narratorScore,
            hasExclamation = hasExclamation,
            hasQuestion = hasQuestion,
            isShortUtterance = isShort,
        )
    }

    private fun defaultDecision(kind: VoiceKind): VoiceDecision = VoiceDecision(
        kind = kind,
        pitch = 1.0f,
        rate = 0.96f,
        reason = "текст пустой, голос по умолчанию",
    )

    private fun buildReason(kind: VoiceKind, features: TextFeatures, score: Float): String {
        val kindLabel = when (kind) {
            VoiceKind.FEMALE -> "женский"
            VoiceKind.MALE -> "мужской"
            VoiceKind.TEEN -> "подростковый"
            else -> "рассказчик"
        }
        val mood = when {
            features.hasExclamation && features.hasQuestion -> "вопросительно-восклицательный"
            features.hasExclamation -> "восклицательный"
            features.hasQuestion -> "вопросительный"
            features.isShortUtterance -> "короткая реплика"
            else -> "обычный"
        }
        return "$kindLabel ($mood, score=${"%.1f".format(score)})"
    }
}
