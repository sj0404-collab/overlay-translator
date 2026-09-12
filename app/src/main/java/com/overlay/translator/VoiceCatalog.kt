package com.overlay.translator

import android.speech.tts.TextToSpeech
import java.util.Locale

enum class VoiceSource { SYSTEM, EDGE }

/**
 * Единый каталог голосов для выбора: системные русские TTS-голоса
 * и сетевые голоса Edge TTS (русские + мультиязычные).
 */
object VoiceCatalog {

    data class CatalogVoice(
        val key: String,               // "sys:<имя>" | "edge:<ShortName>"
        val source: VoiceSource,
        val label: String,
        val kind: VoiceKind,
        val edgeName: String? = null,  // ShortName для Edge
        val systemName: String? = null, // name для системного голоса
    )

    val AUTO = CatalogVoice("", VoiceSource.SYSTEM, "Авто (подбор по тексту)", VoiceKind.OTHER)

    fun systemVoices(tts: TextToSpeech?): List<CatalogVoice> =
        VoiceHelper.russianVoices(tts).map { v ->
            CatalogVoice(
                key = "sys:${v.name}",
                source = VoiceSource.SYSTEM,
                label = "${v.name} · ${v.locale.toLanguageTag()}",
                kind = VoiceHelper.classify(v),
                systemName = v.name,
            )
        }

    /** Русские + мультиязычные голоса Edge (понимают русский текст). */
    fun edgeVoices(voices: List<EdgeTts.EdgeVoice>): List<CatalogVoice> =
        voices.filter { v ->
            v.locale.startsWith("ru-", ignoreCase = true) || v.multilingual
        }.map { v ->
            CatalogVoice(
                key = "edge:${v.shortName}",
                source = VoiceSource.EDGE,
                label = v.label,
                kind = when {
                    v.gender.contains("Female", true) -> VoiceKind.FEMALE
                    v.gender.contains("Male", true) -> VoiceKind.MALE
                    else -> VoiceKind.OTHER
                },
                edgeName = v.shortName,
            )
        }.sortedWith(compareBy({ it.label.contains("Multilingual", true) }, { it.label }))

    /** Все варианты: авто, системные, Edge. Системные выше, Edge ниже. */
    fun all(tts: TextToSpeech?, edge: List<EdgeTts.EdgeVoice>): List<CatalogVoice> =
        listOf(AUTO) + systemVoices(tts) + edgeVoices(edge)

    /** Голос по ключу, если он есть в каталоге; null → авто. */
    fun find(tts: TextToSpeech?, edge: List<EdgeTts.EdgeVoice>, key: String?): CatalogVoice? {
        if (key.isNullOrBlank()) return null
        return all(tts, edge).firstOrNull { it.key == key }
    }

    /** Прямой подбор Edge-голоса по полу без обращения к списку. */
    fun edgeDefault(kind: VoiceKind): EdgeTts.EdgeVoice = when (kind) {
        VoiceKind.MALE -> EdgeTts.EdgeVoice("ru-RU-DmitryNeural", "Male", "ru-RU", "Дмитрий")
        VoiceKind.TEEN -> EdgeTts.EdgeVoice("ru-RU-SvetlanaNeural", "Female", "ru-RU", "Светлана")
        VoiceKind.FEMALE -> EdgeTts.EdgeVoice("ru-RU-SvetlanaNeural", "Female", "ru-RU", "Светлана")
        else -> EdgeTts.EdgeVoice("ru-RU-DmitryNeural", "Male", "ru-RU", "Дмитрий")
    }

    @Suppress("unused")
    fun labelFor(key: String): String = when {
        key.startsWith("edge:") -> "Edge: ${key.removePrefix("edge:")}"
        key.startsWith("sys:") -> key.removePrefix("sys:")
        else -> "Авто (подбор по тексту)"
    }
}