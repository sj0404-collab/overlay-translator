package com.overlay.translator

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Клиент бесплатной онлайн-озвучки Microsoft Edge (edge-tts): те же WebSocket-
 * endpoint и голоса, что использует «Читать вслух» в браузере Edge.
 *
 * API-ключ не требуется — только интернет. Голоса отдаются MP3, который
 * приложение проигрывает системным MediaPlayer. Только текст реплики
 * отправляется в Microsoft; изображения/OCR-кадры никогда не отправляются.
 *
 * Протокол (из библиотеки rany2/edge-tts):
 *  - список голосов — GET voices/list;
 *  - синтез — WebSocket wss://speech.platform.bing.com/.../edge/v1;
 *  - Sec-MS-GEC — SHA-256 от (Windows file-time, округлённого до 5 минут)
 *    + trusted-токен; иначе сервер закрывает соединение.
 */
object EdgeTts {
    private const val TAG = "EdgeTts"

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    /** Разница эпох Unix (1970) и Windows file time (1601), в секундах. */
    private const val WIN_EPOCH = 11_644_473_600L
    private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    private const val CHROMIUM_MAJOR = "143"
    private const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"

    private const val BASE_URL =
        "speech.platform.bing.com/consumer/speech/synthesize/readaloud"
    private const val VOICE_LIST_URL =
        "https://$BASE_URL/voices/list?trustedclienttoken=$TRUSTED_CLIENT_TOKEN"
    private const val WSS_URL =
        "wss://$BASE_URL/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"

    /** Голос по умолчанию: русский женский (нейтральный). */
    const val DEFAULT_VOICE = "ru-RU-SvetlanaNeural"

    /** Предел одного SSML-запроса — Microsoft режет длиннее 4096 байт. */
    private const val SSML_BYTE_LIMIT = 4000

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .build()
    }

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 " +
            "Edg/$CHROMIUM_MAJOR.0.0.0"

    /** Один голос Edge TTS из публичного списка. */
    data class EdgeVoice(
        val shortName: String,
        val gender: String,
        val locale: String,
        val friendlyName: String,
    ) {
        /** Мультиязычные голоса понимают текст на любом из больших языков. */
        val multilingual: Boolean get() = shortName.contains("Multilingual", ignoreCase = true)
        val language: String get() = locale.substringBefore('-').lowercase()
        val genderIcon: String get() = when {
            gender.contains("Female", true) -> "♀"
            gender.contains("Male", true) -> "♂"
            else -> "•"
        }
        /** Короткая подпись для списка: название • пол • язык (+ 🌐). */
        val label: String
            get() = buildString {
                append(shortName)
                append(" • ").append(genderIcon)
                append(" ").append(locale)
                if (multilingual) append(" • 🌐 мультиязычный")
            }
    }

    /**
     * Sec-MS-GEC токен: SHA-256 от (Windows-file-time, округлённого до 5 минут)
     * + trusted-токен. Без него сервер отвечает 403/закрывает вебсокет.
     */
    fun secMsGec(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): String {
        var ticks = nowEpochSeconds + WIN_EPOCH
        ticks -= ticks % 300L
        val windowsTicks = ticks * 10_000_000L
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$windowsTicks$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    /** «Javascript-стиль» дата-строка, которую ждёт сервер в X-Timestamp. */
    private fun jsTimestamp(): String {
        val cal = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        val days = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        return String.format(
            Locale.US,
            "%s %s %02d %04d %02d:%02d:%02d GMT+0000 (Coordinated Universal Time)",
            days[cal.get(GregorianCalendar.DAY_OF_WEEK) - 1],
            months[cal.get(GregorianCalendar.MONTH)],
            cal.get(GregorianCalendar.DAY_OF_MONTH),
            cal.get(GregorianCalendar.YEAR),
            cal.get(GregorianCalendar.HOUR_OF_DAY),
            cal.get(GregorianCalendar.MINUTE),
            cal.get(GregorianCalendar.SECOND),
        )
    }

    private fun uuidHex(): String = UUID.randomUUID().toString().replace("-", "")

    private fun escapeXml(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    /**
     * Список всех голосов Edge TTS. Пустой список при ошибке сети/403.
     */
    suspend fun fetchVoices(): List<EdgeVoice> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(VOICE_LIST_URL +
                "&Sec-MS-GEC=${secMsGec()}&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Authority", "speech.platform.bing.com")
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Sec-Fetch-Site", "none")
            conn.setRequestProperty("Sec-Fetch-Mode", "cors")
            conn.setRequestProperty("Sec-Fetch-Dest", "empty")
            conn.setRequestProperty("Sec-CH-UA-Mobile", "?0")
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "voices HTTP ${conn.responseCode}")
                return@runCatching emptyList<EdgeVoice>()
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val arr = org.json.JSONArray(body)
            buildList {
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i) ?: continue
                    val short = v.optString("ShortName")
                    if (short.isBlank()) continue
                    val locale = v.optString("Locale").ifBlank { "xx-XX" }
                    add(
                        EdgeVoice(
                            shortName = short,
                            gender = v.optString("Gender"),
                            locale = locale,
                            friendlyName = v.optString("FriendlyName").ifBlank { short },
                        ),
                    )
                }
            }.sortedWith(
                compareBy(
                    { it.friendlyName.contains("Multilingual", true) },
                    { it.locale },
                    { it.shortName },
                ),
            )
        }.getOrElse { error ->
            Log.w(TAG, "voice list fetch failed", error)
            emptyList()
        }
    }

    /**
     * Синтез одного текста в файл MP3. [ratePercent]/[pitchHz] — prosody
     * (0 = обычный голос). Вернёт null при ошибке сети/сервера/пустого результата.
     */
    suspend fun synthesizeToFile(
        context: Context,
        text: String,
        voice: String = DEFAULT_VOICE,
        ratePercent: Int = 0,
        pitchHz: Int = 0,
    ): File? = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "tts_edge_${System.nanoTime()}.mp3")
        val out = target.outputStream().buffered()
        var any = false
        try {
            for (chunk in chunkForSsml(text)) {
                val data = speakChunk(chunk, voice, ratePercent, pitchHz, 0)
                if (data.isEmpty()) {
                    Log.w(TAG, "empty chunk for voice $voice")
                    continue
                }
                out.write(data)
                any = true
            }
            if (!any) null else target
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "synthesis failed", e)
            null
        } finally {
            runCatching { out.close() }
            if (!any && target.exists()) target.delete()
        }
    }

    /**
     * Разбивает текст на куски ≤ [SSML_BYTE_LIMIT] байт UTF-8, не режа символы:
     * большой текст уходит несколькими SSML-запросами.
     */
    private fun chunkForSsml(text: String, limit: Int = SSML_BYTE_LIMIT): List<String> {
        if (text.toByteArray(Charsets.UTF_8).size <= limit) return listOf(text)
        val chunks = mutableListOf<String>()
        val rest = StringBuilder(text)
        while (rest.isNotEmpty()) {
            var take = limit
            while (take > 0) {
                val candidate = rest.substring(0, take)
                if (candidate.isWholeUtf8()) break
                take--
            }
            if (take <= 0) take = limit
            var part = rest.substring(0, take)
            val amp = part.lastIndexOf('&')
            if (amp >= 0 && part.indexOf(';', amp) < 0 && part.length > amp + 1) {
                part = part.substring(0, amp)
            }
            chunks += part.trim()
            rest.delete(0, part.length)
        }
        return chunks.filter(String::isNotBlank)
    }

    private fun String.isWholeUtf8(): Boolean = try {
        this.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == this
    } catch (e: Exception) {
        false
    }

    /** Один SSML-запрос на WebSocket, возвращает конкатенированный MP3. */
    private suspend fun speakChunk(
        text: String,
        voice: String,
        ratePercent: Int,
        pitchHz: Int,
        volumePercent: Int,
    ): ByteArray = suspendCancellableCoroutine { cont ->
        val requestId = uuidHex()
        val url = WSS_URL +
            "&Sec-MS-GEC=${secMsGec()}" +
            "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION" +
            "&ConnectionId=$requestId"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("Sec-WebSocket-Version", "13")
            .header("Cookie", "muid=${uuidHex().uppercase()};")
            .build()
        val ws = client.newWebSocket(request, EdgeSocketListener(
            requestId = requestId,
            voice = voice,
            text = text,
            ratePercent = ratePercent,
            pitchHz = pitchHz,
            volumePercent = volumePercent,
            onResult = { bytes -> if (cont.isActive) cont.resume(bytes) },
            onError = { error ->
                if (cont.isActive) cont.resume(ByteArray(0))
                if (error != null) Log.w(TAG, "websocket error", error)
            },
        ))
        cont.invokeOnCancellation { ws.cancel() }
    }

    /** Собирает MP3 из бинарных аудио-сообщений WebSocket-соединения. */
    private class EdgeSocketListener(
        private val requestId: String,
        private val voice: String,
        private val text: String,
        private val ratePercent: Int,
        private val pitchHz: Int,
        private val volumePercent: Int,
        private val onResult: (ByteArray) -> Unit,
        private val onError: (Throwable?) -> Unit,
    ) : WebSocketListener() {

        private val audio = java.io.ByteArrayOutputStream()
        private var finished = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            val ts = jsTimestamp()
            webSocket.send(
                "X-Timestamp:$ts\r\n" +
                    "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                    "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"}," +
                    "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n",
            )
            val s = escapeXml(text)
            val rate = if (ratePercent >= 0) "+$ratePercent%" else "$ratePercent%"
            val pitch = if (pitchHz >= 0) "+${pitchHz}Hz" else "${pitchHz}Hz"
            val vol = if (volumePercent >= 0) "+$volumePercent%" else "$volumePercent%"
            val ssml =
                "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' " +
                    "xml:lang='en-US'><voice name='$voice'>" +
                    "<prosody pitch='$pitch' rate='$rate' volume='$vol'>$s</prosody>" +
                    "</voice></speak>"
            webSocket.send(
                "X-RequestId:$requestId\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:${ts}Z\r\n" +
                    "Path:ssml\r\n\r\n$ssml",
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.contains("turn.end")) finish(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val n = bytes.size
            if (n < 2) return
            val headerLength = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
            if (2 + headerLength > n) return
            val header = bytes.substring(2, 2 + headerLength).utf8()
            if (header.contains("Path:audio", ignoreCase = true) &&
                header.contains("Content-Type:audio/mpeg", ignoreCase = true)
            ) {
                val data = bytes.substring(2 + headerLength)
                if (data.size > 0) audio.write(data.toByteArray())
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!finished) {
                finished = true
                onError(t)
            }
        }

        private fun finish(webSocket: WebSocket) {
            if (finished) return
            finished = true
            runCatching { webSocket.close(1000, "ok") }
            onResult(audio.toByteArray())
        }
    }

    /* ─── кэш списка голосов (для оффлайн-показа в диалогах) ─── */

    private fun cacheFile(ctx: Context): File = File(ctx.filesDir, "edge_voices.json")

    fun cacheVoices(ctx: Context, voices: List<EdgeVoice>) {
        runCatching {
            val arr = org.json.JSONArray().apply {
                voices.forEach { v ->
                    put(
                        org.json.JSONObject().apply {
                            put("ShortName", v.shortName)
                            put("Gender", v.gender)
                            put("Locale", v.locale)
                            put("FriendlyName", v.friendlyName)
                        },
                    )
                }
            }
            cacheFile(ctx).writeText(arr.toString())
        }.onFailure { Log.w(TAG, "cache write failed", it) }
    }

    fun cachedVoices(ctx: Context): List<EdgeVoice> = runCatching {
        val f = cacheFile(ctx)
        if (!f.isFile) return@runCatching emptyList<EdgeVoice>()
        val arr = org.json.JSONArray(f.readText())
        buildList {
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                val short = v.optString("ShortName")
                if (short.isBlank()) continue
                add(
                    EdgeVoice(
                        shortName = short,
                        gender = v.optString("Gender"),
                        locale = v.optString("Locale").ifBlank { "xx-XX" },
                        friendlyName = v.optString("FriendlyName").ifBlank { short },
                    ),
                )
            }
        }
    }.getOrElse { emptyList() }
}