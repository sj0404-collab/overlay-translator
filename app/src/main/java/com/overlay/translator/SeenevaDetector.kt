/*
 * Seeneva-compatible speech-balloon detector integration.
 * Copyright (C) 2026 Overlay Translator contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Runs Seeneva's YOLOv4-tiny TFLite model, trained for comic speech balloons and panels.
 *
 * The preprocessing and output mapping intentionally follow Seeneva's top-left padding and
 * wide-page slicing strategy. Only class 0 (speech balloon) is returned to the OCR pipeline.
 */
class SeenevaDetector(context: Context) : AutoCloseable {
    private data class Slice(val bitmap: Bitmap, val left: Int, val sourceWidth: Int, val scaledWidth: Int, val scaledHeight: Int)
    private data class Detection(val rect: Rect, val score: Float)

    private val interpreter: Interpreter? = runCatching {
        val model = AssetCopy.copyModel(context, MODEL_FILE)
        Interpreter(model, Interpreter.Options().apply {
            setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
        })
    }.getOrNull()

    @Synchronized
    fun boxes(source: Bitmap, minScore: Float = DEFAULT_SCORE): List<Rect> {
        val engine = interpreter ?: return emptyList()
        val inputShape = engine.getInputTensor(0).shape()
        if (inputShape.size != 4 || inputShape[3] != 3) return emptyList()

        val batchSize = inputShape[0].coerceAtLeast(1)
        val inputHeight = inputShape[1]
        val inputWidth = inputShape[2]
        val slices = createSlices(source, inputWidth, inputHeight)
        val detections = ArrayList<Detection>()

        try {
            for (start in slices.indices step batchSize) {
                val current = slices.subList(start, min(start + batchSize, slices.size))
                val input = allocateFloatBuffer(batchSize * inputWidth * inputHeight * 3)
                current.forEach { slice -> putRgb(input, slice.bitmap, inputWidth, inputHeight) }
                input.rewind()

                val scoredShape = engine.getOutputTensor(0).shape()
                val classesShape = engine.getOutputTensor(1).shape()
                val validShape = engine.getOutputTensor(2).shape()
                val scored = allocateFloatBuffer(scoredShape.product())
                val classes = allocateIntBuffer(classesShape.product())
                val valid = allocateIntBuffer(validShape.product())

                engine.runForMultipleInputsOutputs(
                    arrayOf(input),
                    mapOf(0 to scored, 1 to classes, 2 to valid),
                )
                scored.rewind(); classes.rewind(); valid.rewind()
                val scoredValues = scored.asFloatBuffer()
                val classValues = classes.asIntBuffer()
                val validValues = valid.asIntBuffer()

                val maxDetections = scoredShape.getOrNull(1) ?: 0
                current.forEachIndexed { batchIndex, slice ->
                    val count = validValues.get(batchIndex).coerceIn(0, maxDetections)
                    repeat(count) { detectionIndex ->
                        val base = (batchIndex * maxDetections + detectionIndex) * 5
                        val score = scoredValues.get(base + 4)
                        val classId = classValues.get(batchIndex * maxDetections + detectionIndex)
                        if (classId != SPEECH_BALLOON_CLASS || score < minScore) return@repeat

                        val top = scoredValues.get(base)
                        val left = scoredValues.get(base + 1)
                        val bottom = scoredValues.get(base + 2)
                        val right = scoredValues.get(base + 3)
                        mapBox(source, slice, inputWidth, inputHeight, left, top, right, bottom)
                            ?.let { detections += Detection(it, score) }
                    }
                }
            }
        } finally {
            slices.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        }

        return nonMaximumSuppression(detections, NMS_IOU)
            .sortedWith(compareBy({ it.top }, { it.left }))
            .take(MAX_BOXES)
    }

    private fun createSlices(source: Bitmap, inputWidth: Int, inputHeight: Int): List<Slice> {
        val count = if (source.width > source.height) ceil(source.width.toDouble() / source.height).toInt() else 1
        val nominalWidth = (source.width.toFloat() / count).roundToInt().coerceAtLeast(1)
        return (0 until count).mapNotNull { index ->
            val left = nominalWidth * index
            if (left >= source.width) return@mapNotNull null
            val width = if (index == count - 1) source.width - left else min(nominalWidth, source.width - left)
            val crop = Bitmap.createBitmap(source, left, 0, width, source.height)
            val scale = minOf(1f, inputWidth / width.toFloat(), inputHeight / source.height.toFloat())
            val scaledWidth = (width * scale).roundToInt().coerceIn(1, inputWidth)
            val scaledHeight = (source.height * scale).roundToInt().coerceIn(1, inputHeight)
            val prepared = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
            val resized = if (crop.width == scaledWidth && crop.height == scaledHeight) crop
                else Bitmap.createScaledBitmap(crop, scaledWidth, scaledHeight, true)
            android.graphics.Canvas(prepared).drawBitmap(resized, 0f, 0f, null)
            if (resized !== crop) resized.recycle()
            crop.recycle()
            Slice(prepared, left, width, scaledWidth, scaledHeight)
        }
    }

    private fun putRgb(buffer: ByteBuffer, bitmap: Bitmap, width: Int, height: Int) {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        pixels.forEach { color ->
            buffer.putFloat(((color shr 16) and 255) / 255f)
            buffer.putFloat(((color shr 8) and 255) / 255f)
            buffer.putFloat((color and 255) / 255f)
        }
    }

    private fun mapBox(
        source: Bitmap,
        slice: Slice,
        inputWidth: Int,
        inputHeight: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Rect? {
        val x1 = (left * inputWidth / slice.scaledWidth * slice.sourceWidth + slice.left).roundToInt()
        val x2 = (right * inputWidth / slice.scaledWidth * slice.sourceWidth + slice.left).roundToInt()
        val y1 = (top * inputHeight / slice.scaledHeight * source.height).roundToInt()
        val y2 = (bottom * inputHeight / slice.scaledHeight * source.height).roundToInt()
        val rect = Rect(
            min(x1, x2).coerceIn(0, source.width - 1),
            min(y1, y2).coerceIn(0, source.height - 1),
            max(x1, x2).coerceIn(1, source.width),
            max(y1, y2).coerceIn(1, source.height),
        )
        if (rect.width() < MIN_BOX_SIDE || rect.height() < MIN_BOX_SIDE) return null
        return rect
    }

    private fun nonMaximumSuppression(input: List<Detection>, threshold: Float): List<Rect> {
        val kept = ArrayList<Detection>()
        input.sortedByDescending { it.score }.forEach { candidate ->
            if (kept.none { iou(it.rect, candidate.rect) > threshold }) kept += candidate
        }
        return kept.map { it.rect }
    }

    private fun iou(a: Rect, b: Rect): Float {
        val intersectionWidth = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0)
        val intersectionHeight = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0)
        val intersection = intersectionWidth * intersectionHeight
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0) 0f else intersection.toFloat() / union
    }

    override fun close() { interpreter?.close() }

    private fun allocateFloatBuffer(count: Int): ByteBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())

    private fun allocateIntBuffer(count: Int): ByteBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())

    private fun IntArray.product(): Int = fold(1) { acc, value -> acc * value }

    companion object {
        private const val MODEL_FILE = "yolo_seeneva.tflite"
        private const val SPEECH_BALLOON_CLASS = 0
        private const val DEFAULT_SCORE = 0.30f
        private const val NMS_IOU = 0.45f
        private const val MIN_BOX_SIDE = 12
        private const val MAX_BOXES = 16
    }
}
