package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap

class OcrRouter(private val ctx: Context) {
    fun setLang(@Suppress("UNUSED_PARAMETER") l: ScanLang) {}

    fun read(src: Bitmap, engine: String, lang: ScanLang): String {
        val ruOnly = lang == ScanLang.RU || EnginePrefs.scanLang(ctx) == "RU"
        val mode = EnginePrefs.scanMode(ctx)
        val crop = when (mode) {
            "bubble" -> bubbleCrop(src) ?: src
            else -> src
        }
        val zenModel = EnginePrefs.zenModel(ctx)
        return when (engine) {
            "openrouter" -> LlmClient.visionOpenRouter(crop, EnginePrefs.openrouterKey(ctx), EnginePrefs.orModel(ctx), ruOnly)
                ?: LlmClient.visionOcr(crop, zenModel, ruOnly, mode)
                ?: ""
            else -> LlmClient.visionOcr(crop, zenModel, ruOnly, mode) ?: ""
        }.let { if (ruOnly) RuText.clean(it) else it.trim() }
    }

    private fun bubbleCrop(src: Bitmap): Bitmap? {
        val bubbles = ImagePrep.findBubbles(src)
        val b = bubbles.maxByOrNull { it.rect.width() * it.rect.height() } ?: return null
        return Bitmap.createBitmap(src, b.rect.left, b.rect.top, b.rect.width(), b.rect.height())
    }

    fun close() {}
}
