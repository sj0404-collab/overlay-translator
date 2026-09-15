package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * Routes OCR through the engine and connectivity mode chosen in settings.
 *
 * Mode semantics:
 *  - offline: only the bundled Cyrillic PP-OCR model.
 *  - online:  only the selected engine (online engines need network).
 *  - mixed (default): selected engine, with a local fallback when an online
 *    engine returns nothing.
 *  - combo:   local PP-OCR first, then a free online pass.
 *
 * The screenshot is never sent to a network provider unless the user picked
 * an online engine and a mode that permits it.
 */
class OcrRouter(context: Context) {
    private val ctx = context.applicationContext
    private val local by lazy { CyrillicOcrEngine(ctx) }
    private val glens = GlensOcrEngine()
    private val zen by lazy { ZenFreeOcrEngine(ctx) }
    private val google by lazy { GoogleAiOcrEngine(ctx) }
    private val openrouter by lazy { OpenRouterOcrEngine(ctx) }

    fun engineFor(id: String): OcrEngine = when (id) {
        EnginePrefs.OCR_GLENS -> glens
        EnginePrefs.OCR_ZEN -> zen
        EnginePrefs.OCR_GOOGLE_AI -> google
        EnginePrefs.OCR_OPENROUTER -> openrouter
        else -> local
    }

    fun read(src: Bitmap, engine: String, lang: ScanLang, mode: String): String {
        val primary = engineFor(engine)
        val chain = when (mode) {
            EnginePrefs.MODE_OFFLINE -> listOf(local)
            EnginePrefs.MODE_ONLINE -> listOf(primary)
            EnginePrefs.MODE_COMBO -> if (primary === local) listOf(local, glens) else listOf(local, primary)
            else -> if (primary === local) listOf(local) else listOf(primary, local)
        }
        for (part in chain) {
            val text = runCatching { part.recognizeText(src).trim() }.getOrNull() ?: ""
            if (text.isNotBlank()) return text
        }
        return ""
    }

    fun close() {
        runCatching { local.close() }
        runCatching { glens.close() }
        runCatching { zen.close() }
        runCatching { google.close() }
        runCatching { openrouter.close() }
        Log.i(TAG, "OcrRouter closed")
    }

    companion object {
        private const val TAG = "OcrRouter"
    }
}