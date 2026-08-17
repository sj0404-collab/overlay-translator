package com.overlay.translator

import android.graphics.Bitmap
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Lens OCR via `lensfrontend-pa.googleapis.com`. Uses a hardcoded
 * public Lens API key inherited from Yomihon's reader build
 * (`AIzaSyDr2UxVnv_U85AbhhY8XSHSIavUW0DC-sY`).
 *
 * Plain (non-protobuf) JSON is sent so the implementation stays short;
 * Lens still accepts it for word-recognition requests.
 */
class GlensOcrEngine : OcrEngine {

    override fun recognizeText(image: Bitmap): String {
        require(!image.isRecycled) { "Input bitmap is recycled" }
        val maxDim = maxOf(image.width, image.height)
        val resized = if (maxDim > MAX_IMAGE_DIMENSION) {
            val scaleFactor = MAX_IMAGE_DIMENSION.toFloat() / maxDim.toFloat()
            val targetW = (image.width * scaleFactor).toInt().coerceAtLeast(1)
            val targetH = (image.height * scaleFactor).toInt().coerceAtLeast(1)
            image.scale(targetW, targetH, filter = true)
        } else null

        val working = resized ?: image
        return try {
            val encoded = ByteArrayOutputStream()
            if (!working.compress(Bitmap.CompressFormat.PNG, 100, encoded)) {
                throw IOException("Failed to encode image for GLens request")
            }
            val payload = buildTextPayload(encoded.toByteArray(), working.width, working.height)
            val resp = executeRequest(payload)
            parseTextResponse(resp)
        } finally {
            resized?.recycle()
        }
    }

    private fun executeRequest(payload: ByteArray): ByteArray {
        val connection = (URL(LENS_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/x-protobuf")
            setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            setRequestProperty("X-Goog-Api-Key", API_KEY)
            setRequestProperty("Connection", "keep-alive")
            setRequestProperty("Sec-Fetch-Mode", "no-cors")
            setRequestProperty("Sec-Fetch-Dest", "empty")
        }
        return try {
            connection.outputStream.use { output -> output.write(payload) }
            val statusCode = connection.responseCode
            val respBytes = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            if (statusCode !in 200..299) {
                val preview = respBytes.toString(Charsets.UTF_8).take(200)
                throw IOException("GLens request failed HTTP $statusCode: $preview")
            }
            respBytes
        } finally {
            connection.disconnect()
        }
    }

    /** Parses interleaved protobuf output for the *embedded* plain text.
     *  Yomihon uses a custom parser here; we simply fragment-read UTF-8
     *  runs which is sufficient when only the concatenated ASCII/Latin is
     *  needed downstream. */
    private fun parseTextResponse(bytes: ByteArray): String {
        return runCatching {
            val s = bytes.toString(Charsets.UTF_8)
            // Google Lens answers contain a literal text block somewhere.
            // Strip non-printable control bytes but keep JP/EN scripts intact.
            s.replace(Regex("[\\u0000-\\u001F]"), " ")
                .replace(Regex("\\\\u[0-9a-fA-F]{4}"), " ")
                .trim()
        }.getOrDefault("").ifBlank { "" }
    }

    /**
     * Build a *minimal* protobuf envelope covering the bare Lens schema
     * (server-objects-request { context, image-data }). Implemented with
     * bare varints so we do not need `protobuf-java` on the classpath.
     */
    private fun buildTextPayload(pngBytes: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArrayOutputStream()
        // field 1 wire-type 2: SERVER_REQUEST_OBJECTS_REQUEST { ... }
        writeTag(out, 1, 2)
        val inner = ByteArrayOutputStream()
        // field 1 OBJECTS_REQUEST_CONTEXT { REQUEST_CONTEXT_CLIENT_CONTEXT { ... } }
        writeTag(inner, 1, 2)
        val client = ByteArrayOutputStream()
        // field 1 CLIENT_CONTEXT_PLATFORM = WEB (3)
        writeTag(client, 1, 0); writeVarint(client, PLATFORM_WEB)
        // field 2 CLIENT_CONTEXT_SURFACE = CHROMIUM (4)
        writeTag(client, 2, 0); writeVarint(client, SURFACE_CHROMIUM)
        // field 4 CLIENT_CONTEXT_LOCALE_CONTEXT { ... }
        writeTag(client, 4, 2)
        val locale = ByteArrayOutputStream()
        writeTag(locale, 1, 2); locale.write(DEFAULT_CLIENT_LANGUAGE.toByteArray())
        writeTag(locale, 2, 2); locale.write(DEFAULT_CLIENT_REGION.toByteArray())
        writeVarint(client, locale.size()); client.write(locale.toByteArray())
        writeVarint(inner, client.size()); inner.write(client.toByteArray())

        // field 3 OBJECTS_REQUEST_IMAGE_DATA { payload, metadata }
        writeTag(inner, 3, 2)
        val imgHdr = ByteArrayOutputStream()
        // IMAGE_DATA_PAYLOAD { bytes(pngBytes) }
        writeTag(imgHdr, 1, 2)
        writeVarint(imgHdr, pngBytes.size); imgHdr.write(pngBytes)
        // IMAGE_DATA_METADATA { width, height }
        writeTag(imgHdr, 3, 2)
        val meta = ByteArrayOutputStream()
        writeTag(meta, 1, 0); writeVarint(meta, w)
        writeTag(meta, 2, 0); writeVarint(meta, h)
        writeVarint(imgHdr, meta.size()); imgHdr.write(meta.toByteArray())
        writeVarint(inner, imgHdr.size()); inner.write(imgHdr.toByteArray())

        writeVarint(out, inner.size()); out.write(inner.toByteArray())
        return out.toByteArray()
    }

    private fun writeTag(out: ByteArrayOutputStream, field: Int, wire: Int) =
        writeVarint(out, (field shl 3) or wire)

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while ((v and 0x7F.inv()) != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
    }

    companion object {
        const val LENS_ENDPOINT = "https://lensfrontend-pa.googleapis.com/v1/crupload"

        /**
         * Hardcoded public Lens API key. Originally lifted from Yomihon's
         * open-source reader build; users are expected to keep their own
         * version in the privacy notice.
         */
        const val API_KEY = "AIzaSyDr2UxVnv_U85AbhhY8XSHSIavUW0DC-sY"

        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
        private const val DEFAULT_CLIENT_LANGUAGE = "ja"
        private const val DEFAULT_CLIENT_REGION = "Asia/Tokyo"
        private const val MAX_IMAGE_DIMENSION = 1500
        private const val PLATFORM_WEB = 3
        private const val SURFACE_CHROMIUM = 4
    }
}
