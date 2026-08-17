package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap

/**
 * Routes bitmap OCR through one of:
 *   - `mlkit` (default local OCR)
 *   - `tess` (Tesseract)
 *   - `yolo` (Seeneva YOLOv4-tiny speech-balloon detector + per-bubble OCR)
 *   - `glens` (online Google Lens, hardcoded API key)
 *   - `google` (Gemini Vision, **user API key** from [EnginePrefs])
 *   - `openrouter` (multimodal Chat Completions, **user API key**)
 *   - `local` (local stack fallback: MLKit→Tess→bubbles)
 *   - `zen` (Zen Vision free cloud gateway)
 *
 * Mirrors OcrRepositoryImpl's engine selection in Yomihon's reader build,
 * but uses shared engines (cheap to construct, no DI required) suitable
 * for the lightweight overlay service.
 */
class OcrRouter(private val ctx: Context) {
    private val tess = TessOcr(ctx)
    private val seeneva = SeenevaDetector(ctx)
    private val glens = GlensOcrEngine()
    private val zen = ZenFreeOcrEngine(ctx)
    private var googleEngine: GoogleAiOcrEngine? = null
    private var orEngine: OpenRouterOcrEngine? = null

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
                "yolo" -> seenevaOcr(src)
                "glens" -> glens.recognizeText(src).ifBlank { localStack(src, prep()) }
                "google" -> {
                    val e = googleEngine ?: GoogleAiOcrEngine(ctx).also { googleEngine = it }
                    e.recognizeText(src).ifBlank { localStack(src, prep()) }
                }
                "openrouter" -> {
                    val e = orEngine ?: OpenRouterOcrEngine(ctx).also { orEngine = it }
                    e.recognizeText(src).ifBlank { localStack(src, prep()) }
                }
                "local" -> localStack(src, prep())
                else -> { // 'zen' default (no key required)
                    LlmClient.visionGemini(ctx, src, ruOnly, EnginePrefs.scanMode(ctx))
                        ?: zen.recognizeText(src).ifBlank { localStack(src, prep()) }
                }
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

    private fun seenevaOcr(src: Bitmap): String {
        val boxes = seeneva.boxes(src)
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
        seeneva.close()
        googleEngine?.close()
        googleEngine = null
        orEngine?.close()
        orEngine = null
    }
}
