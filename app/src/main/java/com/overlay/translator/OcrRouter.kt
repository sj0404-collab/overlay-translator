package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * Local-only router for the standalone screen-frame overlay.
 *
 * The captured bitmap is processed by the bundled Cyrillic PP-OCR models only;
 * it is never passed to a network OCR or vision provider.
 */
class OcrRouter(context: Context) {
    private val localCyrillic = CyrillicOcrEngine(context.applicationContext)

    fun read(src: Bitmap, engine: String, lang: ScanLang): String {
        return try {
            localCyrillic.recognizeText(src).trim()
        } catch (error: Exception) {
            Log.e(TAG, "Local Cyrillic OCR failed", error)
            ""
        }
    }

    fun close() = localCyrillic.close()

    companion object {
        private const val TAG = "OcrRouter"
    }
}
