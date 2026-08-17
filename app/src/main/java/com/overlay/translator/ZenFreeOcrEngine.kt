package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap

/**
 * Zero-config "free" engine — actually a wrapper around Google Lens (which
 * uses a hardcoded API key and works without any user input). This mirrors
 * the behaviour of Yomihon's "Zen Free" reader mode so users coming from
 * the reader don't have to configure a key.
 */
class ZenFreeOcrEngine(private val ctx: Context) : OcrEngine {

    private val fallbackGlens = GlensOcrEngine()

    override fun recognizeText(image: Bitmap): String {
        val text = fallbackGlens.recognizeText(image)
        // Approximate token usage indicator (Yomihon's reader also uses this
        // when accounting free-tier AI endpoints).
        val estimated = (text.length * 1.5f).toLong().coerceAtLeast(15L)
        EnginePrefs.incrementTokens(ctx, estimated)
        return text
    }

    override fun close() {
        fallbackGlens.close()
    }
}
