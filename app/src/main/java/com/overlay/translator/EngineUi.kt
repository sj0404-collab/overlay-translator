package com.overlay.translator

/** Human-readable engine labels for the history and diagnostics UI. */
object EngineUi {
    fun ocrLabel(id: String): String = when (id) {
        EnginePrefs.OCR_GLENS -> "Glens"
        EnginePrefs.OCR_ZEN -> "Zen Free"
        EnginePrefs.OCR_GOOGLE_AI -> "Google AI"
        EnginePrefs.OCR_OPENROUTER -> "OpenRouter"
        else -> "PP-OCR"
    }

    fun trLabel(id: String): String = when (id) {
        EnginePrefs.TR_GOOGLE -> "Google"
        EnginePrefs.TR_GOOGLE_AI -> "Gemini"
        EnginePrefs.TR_ZEN -> "Zen"
        EnginePrefs.TR_OPENROUTER -> "OpenRouter"
        EnginePrefs.TR_MYMEMORY -> "MyMemory"
        EnginePrefs.TR_AUTO -> "Авто"
        else -> "Словарь"
    }
}