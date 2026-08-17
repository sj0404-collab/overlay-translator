package com.overlay.translator

import android.graphics.Bitmap

/**
 * Common interface for every OCR / vision engine. Each implementation is
 * responsible for its own HTTP, model, or TFLite setup; the call site just
 * hands over a [Bitmap] and expects a raw string back.
 *
 * Models from Yomihon reader were reshaped for plain synchronous use: the
 * existing overlay flow does not need coroutine-aware shutdown.
 */
interface OcrEngine {
    fun recognizeText(image: Bitmap): String
    fun close() {}
}
