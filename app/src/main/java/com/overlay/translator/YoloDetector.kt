package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

class YoloDetector(ctx: Context) {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession? = runCatching {
        val f = AssetCopy.copyModel(ctx, "yolov5n.onnx")
        env.createSession(f.absolutePath)
    }.getOrNull()

    fun boxes(src: Bitmap, minScore: Float = 0.28f): List<Rect> {
        val s = session ?: return emptyList()
        val size = 640
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        val buf = FloatArray(3 * size * size)
        val px = IntArray(size * size)
        scaled.getPixels(px, 0, size, 0, 0, size, size)
        var i = 0
        while (i < px.size) {
            val c = px[i]
            buf[i] = ((c shr 16) and 255) / 255f
            buf[size * size + i] = ((c shr 8) and 255) / 255f
            buf[2 * size * size + i] = (c and 255) / 255f
            i++
        }
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(buf), longArrayOf(1, 3, size.toLong(), size.toLong()))
        return try {
            val out = s.run(mapOf(s.inputNames.first() to tensor))
            val raw = out[0].value
            val dets = parse(raw, minScore)
            out.close()
            val sx = src.width / size.toFloat()
            val sy = src.height / size.toFloat()
            val mapped = dets.map { d ->
                Rect(
                    ((d[0] - d[2] / 2f) * sx).toInt().coerceIn(0, src.width - 1),
                    ((d[1] - d[3] / 2f) * sy).toInt().coerceIn(0, src.height - 1),
                    ((d[0] + d[2] / 2f) * sx).toInt().coerceIn(1, src.width),
                    ((d[1] + d[3] / 2f) * sy).toInt().coerceIn(1, src.height)
                )
            }.filter { it.width() > 12 && it.height() > 10 }
            nms(mapped, 0.45f).take(12)
        } catch (_: Exception) {
            emptyList()
        } finally {
            tensor.close()
        }
    }

    private fun parse(raw: Any, minScore: Float): List<FloatArray> {
        val acc = ArrayList<FloatArray>()
        when (raw) {
            is Array<*> -> {
                val a0 = raw[0]
                if (a0 is Array<*>) {
                    // [1, N, C]
                    for (row in a0) {
                        if (row is FloatArray) take(row, minScore, acc)
                    }
                }
            }
        }
        return acc
    }

    private fun take(row: FloatArray, minScore: Float, acc: MutableList<FloatArray>) {
        if (row.size < 6) return
        val obj = if (row.size >= 85) row[4] else 1f
        var best = 0f
        val start = if (row.size >= 85) 5 else 4
        var c = start
        while (c < row.size) {
            if (row[c] > best) best = row[c]
            c++
        }
        val score = obj * best
        if (score >= minScore) acc.add(floatArrayOf(row[0], row[1], row[2], row[3], score))
    }

    private fun nms(boxes: List<Rect>, iouThr: Float): List<Rect> {
        val sorted = boxes.sortedByDescending { it.width() * it.height() }
        val keep = ArrayList<Rect>()
        for (b in sorted) {
            if (keep.any { iou(it, b) > iouThr }) continue
            keep.add(b)
        }
        return keep
    }

    private fun iou(a: Rect, b: Rect): Float {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val bot = min(a.bottom, b.bottom)
        val iw = (r - l).coerceAtLeast(0)
        val ih = (bot - t).coerceAtLeast(0)
        val inter = iw * ih
        val u = a.width() * a.height() + b.width() * b.height() - inter
        return if (u <= 0) 0f else inter.toFloat() / u
    }

    fun close() { session?.close() }
}
