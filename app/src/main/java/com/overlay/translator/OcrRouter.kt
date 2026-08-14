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
        var prepared: Bitmap? = null
        fun prep(): Bitmap = prepared ?: ImagePrep.prepareForOcr(src).also { prepared = it }
        return try {
            val raw = when (engine) {
                "mlkit" -> MlKitOcr.read(src).ifBlank { tess.read(prep()) }
                "tess" -> tess.read(prep()).ifBlank { MlKitOcr.read(src) }
                "yolo" -> yoloOcr(src, lang)
                "openrouter" -> LlmClient.visionOpenRouter(src, EnginePrefs.openrouterKey(ctx), EnginePrefs.orModel(ctx), ruOnly)
                    ?: LlmClient.visionOcr(src, EnginePrefs.zenModel(ctx), ruOnly, EnginePrefs.scanMode(ctx))
                    ?: localStack(src, prep())
                "local" -> localStack(src, prep())
                else -> LlmClient.visionOcr(src, EnginePrefs.zenModel(ctx), ruOnly, EnginePrefs.scanMode(ctx))
                    ?: localStack(src, prep())
            }
            if (ruOnly) RuText.clean(raw) else raw.trim()
        } finally {
            prepared?.takeIf { it !== src && !it.isRecycled }?.recycle()
        }
    }

    private fun localStack(src: Bitmap, prep: Bitmap): String {
        val a = MlKitOcr.read(src)
        if (a.length > 3) return a
        val b = tess.read(prep)
        if (b.length > 3) return b
        return ImagePrep.findBubbles(src).mapNotNull { box ->
            val c = Bitmap.createBitmap(src, box.rect.left, box.rect.top, box.rect.width(), box.rect.height())
            try {
                MlKitOcr.read(c).ifBlank {
                    val p = ImagePrep.prepareForOcr(c)
                    try { tess.read(p) } finally { if (p !== c) p.recycle() }
                }.ifBlank { null }
            } finally { c.recycle() }
        }.joinToString("\n")
    }

    private fun yoloOcr(src: Bitmap, lang: ScanLang): String {
        val boxes = yolo.boxes(src)
        val parts = ArrayList<String>()
        for (r in boxes) {
            val crop = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
            val t = try {
                MlKitOcr.read(crop).ifBlank {
                    val p = ImagePrep.prepareForOcr(crop)
                    try { tess.read(p) } finally { if (p !== crop) p.recycle() }
                }
            } finally { crop.recycle() }
            if (t.length > 1) parts.add(t)
        }
        if (parts.isEmpty()) {
            val p = ImagePrep.prepareForOcr(src)
            return try { localStack(src, p) } finally { if (p !== src) p.recycle() }
        }
        return parts.joinToString("\n")
    }

    fun close() {
        tess.close()
        yolo.close()
    }
}
