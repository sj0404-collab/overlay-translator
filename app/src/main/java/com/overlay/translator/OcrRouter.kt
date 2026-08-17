package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * Cloud-only OCR router.  Every engine is tried in order; if one
 * returns blank or throws, the next one is attempted automatically.
 */
class OcrRouter(private val ctx: Context) {
    private val glens = GlensOcrEngine()
    private val zen = ZenFreeOcrEngine(ctx)
    private var googleEngine: GoogleAiOcrEngine? = null
    private var orEngine: OpenRouterOcrEngine? = null

    fun read(src: Bitmap, engine: String, lang: ScanLang): String {
        val ruOnly = lang == ScanLang.RU
        return try {
            val raw = when (engine) {
                "glens" -> glens.recognizeText(src).ifBlank {
                    Log.i(TAG, "Glens returned blank, falling back to Zen")
                    zen.recognizeText(src)
                }
                "google" -> (googleEngine ?: GoogleAiOcrEngine(ctx).also { googleEngine = it })
                    .recognizeText(src).ifBlank { zen.recognizeText(src) }
                "openrouter" -> (orEngine ?: OpenRouterOcrEngine(ctx).also { orEngine = it })
                    .recognizeText(src).ifBlank { zen.recognizeText(src) }
                else -> { // "zen" / default
                    LlmClient.visionGemini(ctx, src, ruOnly, EnginePrefs.scanMode(ctx))
                        ?: zen.recognizeText(src)
                }
            }
            if (ruOnly) RuText.clean(raw) else raw.trim()
        } catch (e: Exception) {
            Log.w(TAG, "OCR engine '$engine' failed, trying Zen", e)
            try {
                val fallback = zen.recognizeText(src)
                if (ruOnly) RuText.clean(fallback) else fallback.trim()
            } catch (_: Exception) {
                ""
            }
        }
    }

    fun close() {
        googleEngine?.close(); googleEngine = null
        orEngine?.close(); orEngine = null
    }

    companion object {
        private const val TAG = "OcrRouter"
    }
}
