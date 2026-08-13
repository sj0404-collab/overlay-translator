package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap

class OcrRouter(private val ctx: Context) {
    private val tess = TessOcr(ctx)
    private val yolo = YoloDetector(ctx)

    fun setLang(l: ScanLang) { tess.reopen(l) }

    fun read(src: Bitmap, engine: String, lang: ScanLang): String {
        tess.reopen(lang)
        val ruOnly = lang == ScanLang.RU
        val prep = ImagePrep.prepareForOcr(src)
        val raw = when (engine) {
            "mlkit" -> MlKitOcr.read(src).ifBlank { tess.read(prep) }
            "tess" -> tess.read(prep).ifBlank { MlKitOcr.read(src) }
            "yolo" -> yoloOcr(src, lang)
            "openrouter" -> LlmClient.visionOpenRouter(src, EnginePrefs.openrouterKey(ctx), EnginePrefs.orModel(ctx), ruOnly)
                ?: LlmClient.visionOcr(src, EnginePrefs.zenModel(ctx), ruOnly, EnginePrefs.scanMode(ctx))
                ?: localStack(src, prep)
            "local" -> localStack(src, prep)
            else -> LlmClient.visionOcr(src, EnginePrefs.zenModel(ctx), ruOnly, EnginePrefs.scanMode(ctx))
                ?: localStack(src, prep)
        }
        val cleaned = if (ruOnly) RuText.clean(raw) else raw.trim()
        return cleaned
    }

    private fun localStack(src: Bitmap, prep: Bitmap): String {
        val a = MlKitOcr.read(src)
        if (a.length > 3) return a
        val b = tess.read(prep)
        if (b.length > 3) return b
        return ImagePrep.findBubbles(src).mapNotNull { box ->
            val c = Bitmap.createBitmap(src, box.rect.left, box.rect.top, box.rect.width(), box.rect.height())
            MlKitOcr.read(c).ifBlank { tess.read(ImagePrep.prepareForOcr(c)) }.ifBlank { null }
        }.joinToString("\n")
    }

    private fun yoloOcr(src: Bitmap, lang: ScanLang): String {
        val boxes = yolo.boxes(src)
        val parts = ArrayList<String>()
        for (r in boxes) {
            val crop = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
            val t = MlKitOcr.read(crop).ifBlank { tess.read(ImagePrep.prepareForOcr(crop)) }
            if (t.length > 1) parts.add(t)
        }
        if (parts.isEmpty()) return localStack(src, ImagePrep.prepareForOcr(src))
        return parts.joinToString("\n")
    }

    fun close() {
        tess.close()
        yolo.close()
    }
}
