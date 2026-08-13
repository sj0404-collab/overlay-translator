package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

class OnnxVision(ctx: Context) {
    private val env = OrtEnvironment.getEnvironment()
    private val enhance: OrtSession?
    private val crnn: OrtSession?
    private val charset: List<String>

    init {
        enhance = runCatching {
            env.createSession(AssetCopy.copyModel(ctx, "vision_enhance.onnx").absolutePath)
        }.getOrNull()
        crnn = runCatching {
            env.createSession(AssetCopy.copyModel(ctx, "ocr_crnn.onnx").absolutePath)
        }.getOrNull()
        charset = runCatching {
            ctx.assets.open("models/charset.txt").bufferedReader().readLines()
        }.getOrDefault(emptyList())
    }

    fun enhance(src: Bitmap): Bitmap {
        val s = enhance ?: return src
        val w = src.width.coerceAtMost(512)
        val h = src.height.coerceAtMost(256)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val n = w * h
        val buf = FloatArray(n)
        val px = IntArray(n)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val y = ((c shr 16 and 255) * 0.299f + (c shr 8 and 255) * 0.587f + (c and 255) * 0.114f) / 255f
            buf[i] = 1f - y
        }
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(buf), longArrayOf(1, 1, h.toLong(), w.toLong()))
        return try {
            val out = s.run(mapOf(s.inputNames.first() to tensor))
            val arr = (out[0].value as Array<Array<Array<FloatArray>>>)[0][0]
            val hh = arr.size
            val ww = arr[0].size
            val bmp = Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888)
            val op = IntArray(ww * hh)
            for (yy in 0 until hh) for (xx in 0 until ww) {
                val v = (arr[yy][xx].coerceIn(0f, 1f) * 255f).toInt()
                val g = 255 - v
                op[yy * ww + xx] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
            bmp.setPixels(op, 0, ww, 0, 0, ww, hh)
            out.close()
            bmp
        } catch (_: Exception) {
            src
        } finally {
            tensor.close()
        }
    }

    fun crnnLine(line: Bitmap): String {
        val s = crnn ?: return ""
        val h = 32
        val w = (line.width * h / line.height.coerceAtLeast(1)).coerceIn(32, 320)
        val scaled = Bitmap.createScaledBitmap(line, w, h, true)
        val buf = FloatArray(w * h)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val y = ((c shr 16 and 255) * 0.299f + (c shr 8 and 255) * 0.587f + (c and 255) * 0.114f) / 255f
            buf[i] = 1f - y
        }
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(buf), longArrayOf(1, 1, 32, w.toLong()))
        return try {
            val out = s.run(mapOf(s.inputNames.first() to tensor))
            val logits = out[0].value
            val text = decodeCtc(logits)
            out.close()
            text
        } catch (_: Exception) {
            ""
        } finally {
            tensor.close()
        }
    }

    private fun decodeCtc(logits: Any): String {
        // expected [1, T, C] or [T, C]
        val seq: Array<FloatArray> = when (logits) {
            is Array<*> -> {
                val a0 = logits[0]
                if (a0 is Array<*>) {
                    @Suppress("UNCHECKED_CAST")
                    (a0 as Array<FloatArray>)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    (logits as Array<FloatArray>)
                }
            }
            else -> return ""
        }
        val sb = StringBuilder()
        var prev = 0
        for (t in seq.indices) {
            var best = 0
            var bv = Float.NEGATIVE_INFINITY
            for (c in seq[t].indices) {
                if (seq[t][c] > bv) {
                    bv = seq[t][c]; best = c
                }
            }
            if (best != 0 && best != prev) {
                val idx = best - 1
                if (idx in charset.indices) sb.append(charset[idx])
            }
            prev = best
        }
        return sb.toString().trim()
    }

    fun close() {
        enhance?.close()
        crnn?.close()
    }
}
