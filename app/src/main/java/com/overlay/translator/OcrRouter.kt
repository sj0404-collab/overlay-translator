package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap

class OcrRouter(private val ctx: Context) {
    private val tess = TessOcr(ctx)
    private val vision = OnnxVision(ctx)
    private val phrases = PhraseBank(ctx)

    fun setLang(l: ScanLang) = tess.reopen(l)

    fun read(src: Bitmap, engine: String, lang: ScanLang): String {
        val prep = ImagePrep.prepareForOcr(src)
        val raw = when (engine) {
            "tess" -> tess.read(prep)
            "onnx" -> vision.crnnLine(prep).ifBlank { tess.read(prep) }
            "easy" -> easy(src, prep)
            "vision" -> LlmClient.visionOcr(src, EnginePrefs.zenModel(ctx))
                ?: LlmClient.visionOcr(prep, "mimo-v2.5-free")
                ?: tess.read(prep)
            else -> manga(src, prep) // manga default
        }
        val treatEn = when (lang) {
            ScanLang.EN -> true
            ScanLang.RU -> false
            ScanLang.BOTH -> !ScriptDetect.preferRu(raw)
        }
        return phrases.correct(ImagePrep.cleanOcr(raw, treatEn))
    }

    private fun manga(src: Bitmap, prep: Bitmap): String {
        val bubbles = ImagePrep.findBubbles(src)
        val parts = ArrayList<String>()
        if (bubbles.isNotEmpty()) {
            for (b in bubbles) {
                val crop = Bitmap.createBitmap(src, b.rect.left, b.rect.top, b.rect.width(), b.rect.height())
                val p = ImagePrep.prepareForOcr(crop)
                var t = tess.read(p)
                val c = vision.crnnLine(p)
                if (c.length > t.length + 2) t = c
                if (t.isNotBlank()) parts.add(t)
            }
        }
        if (parts.isEmpty()) {
            var t = tess.read(prep)
            val c = vision.crnnLine(prep)
            if (c.length > t.length + 2) t = c
            if (t.isNotBlank()) parts.add(t)
        }
        return parts.joinToString("\n")
    }

    private fun easy(src: Bitmap, prep: Bitmap): String {
        val a = tess.read(prep)
        val inv = ImagePrep.prepareForOcr(src)
        val b = tess.read(inv)
        return if (b.length > a.length + 3) b else a
    }

    fun close() {
        tess.close()
        vision.close()
    }
}
