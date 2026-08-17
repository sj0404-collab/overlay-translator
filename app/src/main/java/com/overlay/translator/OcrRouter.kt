package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap

/**
 * Routes bitmap OCR through cloud-only engines:
 *   - `glens` (Google Lens, hardcoded API key)
 *   - `google` (Gemini Vision, user API key from EnginePrefs)
 *   - `openrouter` (OpenRouter vision, user API key)
 *   - `zen` / default (Zen Vision free gateway, no key)
 *
 * No local Tesseract/ML Kit/YOLO/bubble fallback — kept intentionally
 * lightweight as APK drops from ~80 MB to ~10 MB.
 */
class OcrRouter(private val ctx: Context) {
    private val glens = GlensOcrEngine()
    private val zen = ZenFreeOcrEngine(ctx)
    private var googleEngine: GoogleAiOcrEngine? = null
    private var orEngine: OpenRouterOcrEngine? = null

    fun read(src: Bitmap, engine: String, lang: ScanLang): String {
        val ruOnly = lang == ScanLang.RU
        val raw = when (engine) {
            "glens" -> glens.recognizeText(src)
            "google" -> (googleEngine ?: GoogleAiOcrEngine(ctx).also { googleEngine = it })
                .recognizeText(src)
            "openrouter" -> (orEngine ?: OpenRouterOcrEngine(ctx).also { orEngine = it })
                .recognizeText(src)
            else -> { // "zen" / default — no key required
                LlmClient.visionGemini(ctx, src, ruOnly, EnginePrefs.scanMode(ctx))
                    ?: zen.recognizeText(src)
            }
        }
        return if (ruOnly) RuText.clean(raw) else raw.trim()
    }

    fun close() {
        googleEngine?.close(); googleEngine = null
        orEngine?.close(); orEngine = null
    }
}