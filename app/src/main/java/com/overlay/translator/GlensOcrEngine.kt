package com.overlay.translator

import android.graphics.Bitmap
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random

/**
 * Google Lens OCR via protobuf endpoint. Ported from Yomihon's GlensOcrEngine
 * with the full ProtoWriter/ProtoReader so requests are encoded correctly.
 */
class GlensOcrEngine : OcrEngine {

    override fun recognizeText(image: Bitmap): String {
        require(!image.isRecycled) { "Input bitmap is recycled" }
        val maxDim = maxOf(image.width, image.height)
        val resized = if (maxDim > MAX_IMAGE_DIMENSION) {
            val scale = MAX_IMAGE_DIMENSION.toFloat() / maxDim.toFloat()
            image.scale((image.width * scale).toInt().coerceAtLeast(1),
                (image.height * scale).toInt().coerceAtLeast(1), filter = true)
        } else null
        val working = resized ?: image
        return try {
            val encoded = ByteArrayOutputStream()
            if (!working.compress(Bitmap.CompressFormat.PNG, 100, encoded))
                throw IOException("PNG encode failed")
            val payload = buildRequestPayload(encoded.toByteArray(), working.width, working.height)
            val resp = executeRequest(payload)
            extractText(resp)
        } finally {
            resized?.recycle()
        }
    }

    private fun buildRequestPayload(pngBytes: ByteArray, w: Int, h: Int): ByteArray {
        val requestId = Random.nextLong()
        return ProtoWriter().apply {
            writeMessage(1) { objectsRequest ->
                objectsRequest.writeMessage(1) { ctx ->
                    ctx.writeMessage(3) { rid ->
                        rid.writeUInt64(1, requestId)
                        rid.writeInt32(2, 0)
                        rid.writeInt32(3, 0)
                        rid.writeBytes(4, Random.nextBytes(16))
                    }
                    ctx.writeMessage(4) { cc ->
                        cc.writeInt32(1, PLATFORM_WEB)
                        cc.writeInt32(2, SURFACE_CHROMIUM)
                        cc.writeMessage(4) { lc ->
                            lc.writeString(1, DEFAULT_CLIENT_LANGUAGE)
                            lc.writeString(2, DEFAULT_CLIENT_REGION)
                        }
                        cc.writeMessage(7) { f ->
                            f.writeMessage(1) { fl ->
                                fl.writeInt32(1, AUTO_FILTER)
                            }
                        }
                    }
                }
                objectsRequest.writeMessage(3) { img ->
                    img.writeMessage(1) { p ->
                        p.writeBytes(1, pngBytes)
                    }
                    img.writeMessage(3) { m ->
                        m.writeInt32(1, w)
                        m.writeInt32(2, h)
                    }
                }
            }
        }.toByteArray()
    }

    private fun executeRequest(payload: ByteArray): ByteArray {
        val conn = (URL(LENS_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/x-protobuf")
            setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            setRequestProperty("X-Goog-Api-Key", API_KEY)
            setRequestProperty("Connection", "keep-alive")
        }
        return try {
            conn.outputStream.use { it.write(payload) }
            val status = conn.responseCode
            val bytes = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            if (status !in 200..299)
                throw IOException("GLens HTTP $status: ${bytes.toString(Charsets.UTF_8).take(200)}")
            bytes
        } finally { conn.disconnect() }
    }

    /** Walk the protobuf response and concatenate all plain_text strings. */
    private fun extractText(bytes: ByteArray): String {
        return runCatching {
            val reader = ProtoReader(bytes)
            val allLines = mutableListOf<String>()
            while (reader.hasRemaining()) {
                val tag = reader.readTag() ?: break
                val field = tag ushr 3
                val wire = tag and 0x7
                if (field == 2 && wire == 2) {
                    // server_response_objects_response
                    val objBytes = reader.readBytes()
                    allLines += parseObjectsResponse(objBytes)
                } else {
                    reader.skipField(wire)
                }
            }
            allLines.joinToString(" ").trim()
        }.getOrDefault("")
    }

    private fun parseObjectsResponse(bytes: ByteArray): List<String> {
        val reader = ProtoReader(bytes)
        val lines = mutableListOf<String>()
        while (reader.hasRemaining()) {
            val tag = reader.readTag() ?: break
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == 3 && wire == 2) {
                lines += parseText(reader.readBytes())
            } else {
                reader.skipField(wire)
            }
        }
        return lines
    }

    private fun parseText(bytes: ByteArray): List<String> {
        val reader = ProtoReader(bytes)
        val result = mutableListOf<String>()
        while (reader.hasRemaining()) {
            val tag = reader.readTag() ?: break
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == 1 && wire == 2) {
                result += parseTextLayout(reader.readBytes())
            } else {
                reader.skipField(wire)
            }
        }
        return result
    }

    private fun parseTextLayout(bytes: ByteArray): List<String> {
        val reader = ProtoReader(bytes)
        val lines = mutableListOf<String>()
        while (reader.hasRemaining()) {
            val tag = reader.readTag() ?: break
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == 1 && wire == 2) {
                // paragraph
                val paraBytes = reader.readBytes()
                lines += parseParagraph(paraBytes)
            } else {
                reader.skipField(wire)
            }
        }
        return lines
    }

    private fun parseParagraph(bytes: ByteArray): List<String> {
        val reader = ProtoReader(bytes)
        val words = mutableListOf<String>()
        while (reader.hasRemaining()) {
            val tag = reader.readTag() ?: break
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == 2 && wire == 2) {
                // line
                val lineBytes = reader.readBytes()
                words += parseLine(lineBytes)
            } else {
                reader.skipField(wire)
            }
        }
        return words
    }

    private fun parseLine(bytes: ByteArray): List<String> {
        val reader = ProtoReader(bytes)
        val parts = mutableListOf<String>()
        while (reader.hasRemaining()) {
            val tag = reader.readTag() ?: break
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == 1 && wire == 2) {
                // word
                val wordBytes = reader.readBytes()
                parseWord(wordBytes)?.let { parts.add(it) }
            } else {
                reader.skipField(wire)
            }
        }
        return listOf(parts.joinToString(""))
    }

    private fun parseWord(bytes: ByteArray): String? {
        val reader = ProtoReader(bytes)
        var text = ""
        var separator = ""
        while (reader.hasRemaining()) {
            val tag = reader.readTag() ?: break
            val field = tag ushr 3
            val wire = tag and 0x7
            when {
                field == 2 && wire == 2 -> text = reader.readString()
                field == 3 && wire == 2 -> separator = reader.readString()
                else -> reader.skipField(wire)
            }
        }
        return text.ifBlank { null }
    }

    override fun close() {}

    companion object {
        private const val LENS_ENDPOINT = "https://lensfrontend-pa.googleapis.com/v1/crupload"
        const val API_KEY = "AIzaSyDr2UxVnv_U85AbhhY8XSHSIavUW0DC-sY"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
        private const val DEFAULT_CLIENT_LANGUAGE = "ja"
        private const val DEFAULT_CLIENT_REGION = "Asia/Tokyo"
        private const val MAX_IMAGE_DIMENSION = 1500
        private const val PLATFORM_WEB = 3
        private const val SURFACE_CHROMIUM = 4
        private const val AUTO_FILTER = 1
    }
}

/* ── Lightweight protobuf reader/writer (no dependency) ── */

private class ProtoReader(private val bytes: ByteArray) {
    private var pos = 0
    fun hasRemaining() = pos < bytes.size

    fun readTag(): Int? {
        if (!hasRemaining()) return null
        val v = readVarint32() ?: return null
        return v
    }

    fun readBytes(): ByteArray {
        val len = readVarint32() ?: 0
        if (pos + len > bytes.size) return ByteArray(0)
        val result = bytes.copyOfRange(pos, pos + len)
        pos += len
        return result
    }

    fun readString(): String = readBytes().toString(Charsets.UTF_8)

    fun skipField(wireType: Int) {
        when (wireType) {
            0 -> { readVarint32() }
            1 -> { pos += 8; if (pos > bytes.size) pos = bytes.size }
            2 -> { val len = readVarint32() ?: 0; pos += len; if (pos > bytes.size) pos = bytes.size }
            5 -> { pos += 4; if (pos > bytes.size) pos = bytes.size }
        }
    }

    private fun readVarint32(): Int? {
        var result = 0; var shift = 0
        while (shift < 32) {
            if (!hasRemaining()) return null
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
        return result
    }
}

private class ProtoWriter {
    private val output = ByteArrayOutputStream()

    fun writeInt32(fieldNumber: Int, value: Int) {
        writeTag(fieldNumber, 0); writeVarint32(value)
    }

    fun writeUInt64(fieldNumber: Int, value: Long) {
        writeTag(fieldNumber, 0); writeVarint64(value)
    }

    fun writeString(fieldNumber: Int, value: String) {
        writeBytes(fieldNumber, value.toByteArray(Charsets.UTF_8))
    }

    fun writeBytes(fieldNumber: Int, value: ByteArray) {
        writeTag(fieldNumber, 2); writeVarint32(value.size); output.write(value)
    }

    fun writeMessage(fieldNumber: Int, block: (ProtoWriter) -> Unit) {
        val inner = ProtoWriter().also(block).toByteArray()
        writeBytes(fieldNumber, inner)
    }

    fun toByteArray(): ByteArray = output.toByteArray()

    private fun writeTag(field: Int, wire: Int) = writeVarint32((field shl 3) or wire)

    private fun writeVarint32(value: Int) {
        var v = value
        while ((v and 0x7F.inv()) != 0) { output.write((v and 0x7F) or 0x80); v = v ushr 7 }
        output.write(v and 0x7F)
    }

    private fun writeVarint64(value: Long) {
        var v = value
        while ((v and 0x80L.inv()) != 0L) { output.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        output.write((v and 0x7F).toInt())
    }
}

private fun Random.nextBytes(count: Int): ByteArray = ByteArray(count).also { nextBytes(it) }
