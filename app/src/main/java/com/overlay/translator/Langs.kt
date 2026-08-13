package com.overlay.translator

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

data class Lang(val label: String, val mlkit: String, val locale: Locale)

object Langs {
    val all = listOf(
        Lang("English", TranslateLanguage.ENGLISH, Locale.US),
        Lang("Русский", TranslateLanguage.RUSSIAN, Locale("ru", "RU")),
        Lang("Latviešu", TranslateLanguage.LATVIAN, Locale("lv", "LV")),
        Lang("Deutsch", TranslateLanguage.GERMAN, Locale.GERMANY),
        Lang("Français", TranslateLanguage.FRENCH, Locale.FRANCE),
        Lang("Español", TranslateLanguage.SPANISH, Locale("es", "ES")),
        Lang("中文", TranslateLanguage.CHINESE, Locale.CHINA),
        Lang("日本語", TranslateLanguage.JAPANESE, Locale.JAPAN),
        Lang("한국어", TranslateLanguage.KOREAN, Locale.KOREA),
        Lang("Italiano", TranslateLanguage.ITALIAN, Locale.ITALY),
        Lang("Português", TranslateLanguage.PORTUGUESE, Locale("pt", "BR")),
        Lang("Türkçe", TranslateLanguage.TURKISH, Locale("tr", "TR")),
        Lang("Українська", TranslateLanguage.UKRAINIAN, Locale("uk", "UA")),
        Lang("Polski", TranslateLanguage.POLISH, Locale("pl", "PL")),
        Lang("العربية", TranslateLanguage.ARABIC, Locale("ar")),
    )
}
