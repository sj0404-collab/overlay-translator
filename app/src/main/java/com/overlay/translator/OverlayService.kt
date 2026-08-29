package com.overlay.translator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class OverlayService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_REBIND = "rebind"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_VOICE_NAME = "voice_name"
        private const val TAG = "OverlayService"
        private const val CHAN = "ot_overlay"
        private const val NOTIF_ID = 1
        private const val RESULT_NOTIF_ID = 10
        var projectionResultCode: Int = 0
        var projectionData: Intent? = null
        @Volatile var isRunning: Boolean = false
    }

    private lateinit var wm: WindowManager
    private var menu: WebView? = null
    private var menuLp: WindowManager.LayoutParams? = null
    private var regionView: RegionView? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ocr: OcrRouter? = null
    private var translator: Translator? = null
    private var voiceKind = VoiceKind.FEMALE
    private var voiceName: String? = null
    private var region: RectF? = null
    private var regionPreset = "rect" // Manual page frame only in local build.
    private var autoTranslate = false
    private var lastOcr = ""
    private var lastTr = ""
    private var lastHash = 0L
    private var live = false
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private val translating = AtomicBoolean(false)
    private var lastSpoken = ""
    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var screenW = 0; private var screenH = 0; private var density = 0

    private val tick = object : Runnable {
        override fun run() {
            if (live && region != null && !busy.get()) captureThen(ocrOnly = !autoTranslate, livePass = true)
            handler.postDelayed(this, 5200)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHAN, "Overlay Translator", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIF_ID, baseNotification("Готов к работе"))
        tts = TextToSpeech(this, this)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels; screenH = dm.heightPixels; density = dm.densityDpi
        Thread { ocr = OcrRouter(this); translator = Translator(this) }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                isRunning = true
                live = EnginePrefs.live(this)
                autoTranslate = EnginePrefs.autoTranslate(this)
                voiceKind = runCatching { VoiceKind.valueOf(intent.getStringExtra(EXTRA_VOICE) ?: "FEMALE") }
                    .getOrDefault(VoiceKind.FEMALE)
                voiceName = intent.getStringExtra(EXTRA_VOICE_NAME)
                    ?.takeIf { it.isNotBlank() }
                    ?: EnginePrefs.voiceName(this).takeIf { it.isNotBlank() }
                if (ttsReady) safeApplyVoice()
                bindProjection()
                showMenu()
                handler.removeCallbacks(tick); handler.post(tick)
            }
            ACTION_REBIND -> bindProjection()
        }
        return START_STICKY
    }

    private fun scanLang() = when (EnginePrefs.scanLang(this)) {
        "EN" -> ScanLang.EN; "RU" -> ScanLang.RU; else -> ScanLang.BOTH
    }

    private fun bindProjection() {
        val data = projectionData ?: return
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection?.stop()
        projection = mpm.getMediaProjection(projectionResultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { vdisplay?.release() }
        }, handler)
        reader?.close()
        reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        vdisplay?.release()
        vdisplay = projection?.createVirtualDisplay(
            "ot", screenW, screenH, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, handler
        )
    }

    /* ─────── TSX overlay control panel ─────── */

    private fun showMenu() {
        if (menu != null) return
        val v = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            webViewClient = WebViewClient()
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addJavascriptInterface(OverlayPanelBridge(), "OverlayNative")
            loadUrl("file:///android_asset/tsx/index.html#overlay")
        }
        menu = v
        menuLp = WindowManager.LayoutParams(
            300, 430,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        menuLp?.gravity = Gravity.END or Gravity.TOP
        menuLp?.x = 12; menuLp?.y = 100
        try { wm.addView(v, menuLp) } catch (e: Exception) { Log.e(TAG, "menu addView", e) }
    }

    private fun overlayStateJson(): String = JSONObject().apply {
        put("frame", region != null)
        put("scanning", busy.get())
        put("tts", ttsReady)
        put("text", lastTr.ifBlank { lastOcr })
        put("selectedVoice", voiceName ?: "")
        put("voices", voiceOptionsJson())
    }.toString()

    private fun voiceOptionsJson(): JSONArray = JSONArray().apply {
        VoiceHelper.russianVoices(tts).forEach { voice ->
            put(JSONObject().apply {
                put("name", voice.name)
                put("label", "${voice.name} · ${voice.locale.toLanguageTag()}")
                put("selected", voice.name == voiceName)
            })
        }
    }

    private fun publishOverlayState() {
        val serialized = JSONObject.quote(overlayStateJson())
        menu?.post { menu?.evaluateJavascript("window.onOverlayNativeState?.($serialized)", null) }
    }

    private inner class OverlayPanelBridge {
        @JavascriptInterface fun state(): String = overlayStateJson()

        @JavascriptInterface fun pickFrame() = handler.post { startRegionPick() }

        @JavascriptInterface fun scanFrame() = handler.post {
            if (region == null) startRegionPick() else captureThen(ocrOnly = true)
        }

        @JavascriptInterface fun speak() = handler.post {
            val text = lastTr.ifBlank { lastOcr }
            if (text.isBlank()) toast("Сначала выполните OCR") else speakNow(text, true)
        }

        @JavascriptInterface fun listVoices(): String = voiceOptionsJson().toString()

        @JavascriptInterface fun selectVoice(name: String) = handler.post {
            val available = VoiceHelper.russianVoices(tts).any { it.name == name }
            if (!available) {
                toast("Русский голос не найден")
                return@post
            }
            voiceName = name
            EnginePrefs.setVoiceName(this@OverlayService, name)
            if (ttsReady) safeApplyVoice()
            publishOverlayState()
            toast("Голос выбран")
        }

        @JavascriptInterface fun copy() = handler.post {
            val text = lastTr.ifBlank { lastOcr }
            if (text.isBlank()) return@post
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("local-ocr", text))
            toast("Скопировано")
        }

        @JavascriptInterface fun stopOverlay() = handler.post { live = false; stopSelf() }
    }

    /* Region selection */
    private fun startRegionPick() {
        if (regionView != null) return
        menu?.visibility = View.INVISIBLE
        val rv = RegionView(this); regionView = rv
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        rv.onPicked = { r ->
            region = r; regionPreset = "rect"
            EnginePrefs.setRegionMode(this, "rect")
            try { wm.removeView(rv) } catch (_: Exception) {}
            regionView = null; menu?.visibility = View.VISIBLE
            toast("Область выбрана")
            publishOverlayState()
        }
        wm.addView(rv, lp)
    }

    /** Brief on-screen status */
    private fun toast(msg: String) {
        handler.post {
            val tv = TextView(this).apply {
                text = msg; setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xCC1E293B.toInt())
                setPadding(24, 12, 24, 12); textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; lp.y = 80
            try { wm.addView(tv, lp) } catch (_: Exception) {}
            handler.postDelayed({ try { wm.removeView(tv) } catch (_: Exception) {} }, 2000)
        }
    }

    /* Capture screen and run OCR + face analysis + translation */
    private fun captureThen(ocrOnly: Boolean, livePass: Boolean = false) {
        // Never silently use the full display. The user must frame the actual
        // reader page, so system chrome and overlay UI remain outside OCR.
        val r = region ?: run {
            if (!livePass) toast("Сначала выберите рамку страницы")
            return
        }
        if (!busy.compareAndSet(false, true)) return
        menu?.visibility = View.INVISIBLE
        publishOverlayState()
        handler.postDelayed({
            Thread {
                try {
                    val piece = grab(r) ?: return@Thread

                    try {
                        val h = PerceptualHash.of(piece)
                        if (livePass && PerceptualHash.isSimilar(h, lastHash)) return@Thread
                        lastHash = h
                        val router = ocr ?: run { toast("OCR загружается…"); return@Thread }
                        val engine = EnginePrefs.ocr(this)
                        val lang = scanLang()
                        var text = router.read(piece, engine, lang).trim()
                        text = TextPostprocessor().postprocess(text)
                        // Noise filter: keep Cyrillic lines, filter short ASCII noise
                        text = text.lines().filter { line ->
                            if (line.isBlank()) return@filter false
                            val hasCyrillic = line.any { it in '\u0400'..'\u04FF' }
                            val isAsciiOnly = line.all { it.isLetterOrDigit() || it.isWhitespace() || it == '/' }
                            hasCyrillic || (line.length > 4 && isAsciiOnly && line.count { it.isLetter() } > 2)
                        }.joinToString("\n")
                        if (text.isBlank()) { if (!livePass) toast("(пусто)"); return@Thread }
                        lastOcr = text; lastTr = ""
                        if (!livePass) toast("✓ ${text.take(60)}")
                        showLocalResult(text)
                        postResultNotification(text, "")
                        ScanHistory.add(this, text, "", engine)
                        // Auto voice selection based on text
                        Thread {
                            val d = VoiceAssistant.analyze(text, voiceKind)
                            handler.post {
                                applyVoiceDecision(d)
                                Log.i(TAG, "voice: ${d.reason}")
                            }
                        }.start()
                        if (!ocrOnly) applyTranslate(text)
                    } finally { if (!piece.isRecycled) piece.recycle() }
                } catch (e: Exception) {
                    Log.e(TAG, "scan error", e); if (!livePass) toast("Ошибка: ${e.message?.take(50)}")
                } finally {
                    busy.set(false)
                    handler.post { menu?.visibility = View.VISIBLE; publishOverlayState() }
                }
            }.start()
        }, 250)
    }

    /** Sends the local OCR result to the visible TSX overlay panel. */
    private fun showLocalResult(text: String) {
        handler.post {
            val value = JSONObject.quote(text)
            menu?.evaluateJavascript("window.onOverlayOcrResult?.($value)", null)
            publishOverlayState()
        }
    }

    /* Translation */
    private fun applyTranslate(text: String) {
        if (!translating.compareAndSet(false, true)) return
        toast("🔄 Перевожу…")
        Thread {
            try {
                val joined = text.replace('\n', ' ')
                val cleaned = RuText.clean(translator?.translate(joined, EnginePrefs.tr(this)) ?: text)
                lastTr = cleaned
                toast("✓ ${cleaned.take(60)}")
                postResultNotification(text, cleaned)
                ScanHistory.add(this, text, cleaned, EnginePrefs.ocr(this))
                Thread {
                    val d = VoiceAssistant.analyze(text, voiceKind)
                    handler.post {
                        applyVoiceDecision(d)
                        toast("🎙 ${d.reason}")
                    }
                }.start()
            } catch (e: Exception) {
                Log.e(TAG, "translate error", e); toast("Ошибка: ${e.message?.take(50)}")
            } finally { translating.set(false) }
        }.start()
    }

    /** Apply voice decision from VoiceAssistant */
    private fun applyVoiceDecision(d: VoiceAssistant.VoiceDecision) {
        if (tts == null || !ttsReady) return
        try {
            voiceKind = d.kind
            safeApplyVoice()
            tts?.setPitch(d.pitch)
            tts?.setSpeechRate(d.rate)
            Log.i(TAG, "voice: ${d.kind} pitch=${d.pitch} rate=${d.rate}")
        } catch (e: Exception) { Log.w(TAG, "voice apply failed", e) }
    }

    /* Notifications */
    private fun postResultNotification(ocr: String, tr: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val combined = if (tr.isNotBlank()) "$ocr\n→ $tr" else ocr
        val openIntent = Intent(this, OverlayService::class.java).setAction(ACTION_START)
        val pi = PendingIntent.getService(this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHAN)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Результат").setContentText(combined.take(100))
            .setStyle(Notification.BigTextStyle().bigText(combined.take(500)))
            .setContentIntent(pi).setAutoCancel(true).setOngoing(false).build()
        nm.notify(RESULT_NOTIF_ID, n)
    }

    private fun baseNotification(text: String): Notification {
        val pi = PendingIntent.getService(this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHAN)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Overlay Translator").setContentText(text)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", pi)
            .setOngoing(true).build()
    }

    /* Voice */
    private fun safeApplyVoice() {
        try { tts?.let { VoiceHelper.apply(it, voiceKind, voiceName) } } catch (_: Exception) {}
    }

    private fun speakNow(text: String, force: Boolean) {
        if (!force && text == lastSpoken) return
        lastSpoken = text
        handler.post {
            try {
                if (tts == null || !ttsReady) { toast("TTS не инициализирован"); return@post }
                safeApplyVoice()
                val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ot")
                if (r == TextToSpeech.SUCCESS) toast("🔊 Озвучиваю…")
                else toast("TTS: голос недоступен")
            } catch (e: Exception) { Log.e(TAG, "speak", e); toast("TTS ошибка") }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.language = Locale("ru", "RU")
            safeApplyVoice()
            toast("TTS: ${tts?.defaultEngine ?: "ok"}")
            publishOverlayState()
        } else { ttsReady = false; Log.w(TAG, "TTS init: $status") }
    }

    /* Screen grab */
    private fun grab(r: RectF): Bitmap? {
        val img = reader?.acquireLatestImage() ?: reader?.acquireNextImage() ?: return null
        return try {
            val p = img.planes[0]; val buf = p.buffer
            val ps = p.pixelStride; val rs = p.rowStride; val pad = rs - ps * screenW
            val bmp = Bitmap.createBitmap(screenW + pad / ps, screenH, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            val crop = Rect(
                r.left.toInt().coerceIn(0, screenW - 1), r.top.toInt().coerceIn(0, screenH - 1),
                r.right.toInt().coerceIn(1, screenW), r.bottom.toInt().coerceIn(1, screenH)
            )
            if (crop.width() < 8 || crop.height() < 8) { bmp.recycle(); null }
            else { val res = Bitmap.createBitmap(bmp, crop.left, crop.top, crop.width(), crop.height()); bmp.recycle(); res }
        } finally { img.close() }
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(tick)
        listOf(menu, regionView).forEach { v ->
            try { v?.let { wm.removeView(it) } } catch (_: Exception) {}
        }
        vdisplay?.release(); reader?.close(); projection?.stop()
        try { tts?.shutdown() } catch (_: Exception) {}; ocr?.close()
        super.onDestroy()
    }
}
