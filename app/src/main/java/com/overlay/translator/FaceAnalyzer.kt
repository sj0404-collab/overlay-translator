package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Local face detection using ML Kit (no API, ~2.5MB model).
 * Detects faces in the scanned region and estimates gender based on
 * facial proportions (jaw width, face width/height ratio).
 */
object FaceAnalyzer {
    private const val TAG = "FaceAnalyzer"

    enum class Gender { MALE, FEMALE, UNKNOWN }

    data class Analysis(val gender: Gender, val faceCount: Int, val confidence: Float)

    private var detector: FaceDetector? = null

    fun init(context: Context) {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.15f)
            .build()
        detector = FaceDetection.getClient(opts)
        Log.i(TAG, "ML Kit face detector initialized")
    }

    fun analyze(bitmap: Bitmap): Analysis {
        val engine = detector ?: return Analysis(Gender.UNKNOWN, 0, 0f)
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val latch = CountDownLatch(1)
            var result = Analysis(Gender.UNKNOWN, 0, 0f)

            engine.process(image)
                .addOnSuccessListener { faces ->
                    result = analyzeFaces(faces)
                    latch.countDown()
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "face detection failed", e)
                    latch.countDown()
                }
            latch.await(3, TimeUnit.SECONDS)
            return result
        } catch (e: Exception) {
            Log.w(TAG, "analyze failed", e)
            return Analysis(Gender.UNKNOWN, 0, 0f)
        }
    }

    private fun analyzeFaces(faces: List<Face>): Analysis {
        if (faces.isEmpty()) return Analysis(Gender.UNKNOWN, 0, 0f)

        val primary = faces.maxByOrNull { f ->
            val area = f.boundingBox.width().toFloat() * f.boundingBox.height().toFloat()
            area * (1 + 0.1f * kotlin.math.abs(f.headEulerAngleY)) // prefer larger, frontal faces
        } ?: return Analysis(Gender.UNKNOWN, faces.size, 0f)

        val width = primary.boundingBox.width().toFloat()
        val height = primary.boundingBox.height().toFloat()
        val ratio = width / (height.coerceAtLeast(1f))

        val jawAngle = kotlin.math.abs(primary.headEulerAngleZ)
        val tilt = kotlin.math.abs(primary.headEulerAngleX)

        // Heuristic: male faces tend to have wider jaw, larger width/height ratio
        // Female faces tend to be narrower, smaller ratio
        // For manga: use ratio as primary indicator
        val score = when {
            ratio > 0.85f && jawAngle > 2f -> 0.8f   // wide face, tilted = likely male
            ratio < 0.75f && tilt < 5f -> 0.7f          // narrow face, straight = likely female
            ratio > 0.80f -> 0.6f                        // slightly wider = mild male indicator
            else -> 0.5f                                 // uncertain
        }

        val gender = if (score > 0.65f) Gender.MALE else Gender.FEMALE
        Log.i(TAG, "faces=${faces.size} ratio=$ratio jaw=$jawAngle tilt=$tilt gender=$gender score=$score")
        return Analysis(gender, faces.size, score)
    }

    fun close() {
        try { detector?.close() } catch (_: Exception) {}
        detector = null
    }
}
