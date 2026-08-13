package com.overlay.translator

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

object MlKitOcr {
    private val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun read(bmp: Bitmap): String = try {
        val t = rec.process(InputImage.fromBitmap(bmp, 0))
        val r = Tasks.await(t, 8, TimeUnit.SECONDS)
        r.text.trim()
    } catch (_: Exception) {
        ""
    }
}
