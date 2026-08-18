package com.overlay.translator

import android.util.Log
import org.json.JSONObject

/**
 * Local JSON-based voice assistant. Analyzes text for linguistic cues
 * (morphology, orthography, sentence structure) and automatically selects
 * the appropriate TTS voice + pitch for spoken output.
 *
 * No external API calls — all rules are local.
 */
object VoiceAssistant {
    private const val TAG = "VoiceAssistant"

    data class VoiceDecision(
        val kind: VoiceKind,
        val pitch: Float,
        val rate: Float,
        val reason: String,
    )

    /**
     * Analyze text and decide which voice/pitch to use.
     * Returns a [VoiceDecision] with the recommended voice kind,
     * pitch factor, speech rate, and a short human-readable reason.
     */
    fun analyze(text: String, configuredKind: VoiceKind): VoiceDecision {
        val clean = text.trim()
        if (clean.isBlank()) return defaultDecision(configuredKind)

        // Gather features
        val features = extractFeatures(clean)

        // Score each voice kind
        val scores = mutableMapOf<VoiceKind, Float>()
        scores[VoiceKind.FEMALE] = features.femaleScore
        scores[VoiceKind.MALE] = features.maleScore
        scores[VoiceKind.TEEN] = features.teenScore
        scores[VoiceKind.OTHER] = features.narratorScore

        // Apply configured preference as slight bias
        scores[configuredKind] = (scores[configuredKind] ?: 0f) + 0.3f

        // Pick winner
        val winner = scores.maxByOrNull { it.value } ?: configuredKind
        val bestScore = scores[winner] ?: 0f

        // Determine pitch and rate based on winner and features
        val pitch = when (winner) {
            VoiceKind.MALE -> 0.92f
            VoiceKind.FEMALE -> 1.08f
            VoiceKind.TEEN -> 1.20f
            else -> 0.95f // narrator — deeper
        }
        val rate = when {
            features.hasExclamation -> 1.05f // excited
            features.hasQuestion -> 0.92f    // questioning, slower
            features.isShortUtterance -> 1.1f // short → faster
            else -> 0.96f
        }

        val reason = buildReason(winner, features, bestScore)

        Log.i(TAG, "analyze: kind=$winner pitch=$pitch rate=$rate score=$bestScore reason=$reason")

        return VoiceDecision(
            kind = winner,
            pitch = pitch,
            rate = rate,
            reason = reason,
        )
    }

    /* ── Feature extraction ── */

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
        val lower = text.lowercase(Locale.US)
        val words = lower.split(Regex("\\s+")).filter { it.isNotEmpty() }

        // ── Morphology: Russian verb endings ──
        val feminineVerbs = words.count { it.matches(Regex(".*ла[сь]?|.*лась|.*ется|.*илась|.*ила|.*лась|.*юсь|.*юсь")) }
        val masculineVerbs = words.count { it.matches(Regex(".*л[сь]?[а-яё]*$|.*лся|.*лся|.*ил|.*ает|.*лся|.*лся")) }

        // ── Pronouns ──
        val sheWords = words.count { it in listOf("она", "её", "ее", "себя", "свой") }
        val heWords = words.count { it in listOf("он", "его", "ему", "себя", "свой") }

        // ── Names (common Russian names pattern — ends with specific endings) ──
        val feminineNames = listOf("мария", "анна", "екатерина", "елизавета", "настасья", "варвара", "дунаев", "звёздная", "сергеевна")
        val masculineNames = listOf("александр", "сергей", "дмитрий", "иван", "николай", "виктор", "алексей", "павел")
        val teenNames = listOf("ализа", "камилла", "алёша", "соня")

        val femaleNameScore = words.count { w -> feminineNames.any { n -> w.contains(n) } }.toFloat() * 2f
        val maleNameScore = words.count { w -> masculineNames.any { n -> w.contains(n) } }.toFloat() * 2f
        val teenNameScore = words.count { w -> teenNames.any { n -> w.contains(n) } }.toFloat() * 2f

        // ── Orthography: exclamation marks, caps (excited) ──
        val exclCount = text.count { it == '!' || it == '！' }
        val quesCount = text.count { it == '?' || it == '？' }
        val hasExclamation = exclCount >= 1
        val hasQuestion = quesCount >= 1
        val capsRatio = if (text.isNotEmpty()) {
            text.count { it.isUpperCase() }.toFloat() / text.length
        } else 0f

        // ── Short utterance detection ──
        val isShort = words.size <= 3

        // ── Topic detection (for narrator) ──
        val narratorTopics = listOf("╀", " панель ", "страница ", "глава", "menu", "chat", "настройки")
        val isNarratorTopic = narratorTopics.any { lower.contains(it) }

        // ── Compute scores ──
        val femaleScore = feminineVerbs * 0.8f + sheWords * 1.0f + femaleNameScore + if (isShort) 0.2f else 0f
        val maleScore = masculineVerbs * 0.8f + heWords * 1.0f + maleNameScore
        val teenScore = teenNameScore + if (lower.contains("подрост") || lower.contains("ребёнок")) 1.5f else 0f
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
            VoiceKind.OTHER -> "рассказчик"
        }
        val mood = when {
            features.hasExclamation && features.hasQuestion -> "вопросительный восклицательный"
            features.hasExclamation -> "восклицательный"
            features.hasQuestion -> "вопросительный"
            features.isShortUtterance -> "короткая реплика"
            else -> "обычный"
        }
        return "$kindLabel ($mood, score=${"%.1f".format(score)})"
    }
}
