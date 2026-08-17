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
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class OverlayService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_REBIND = "rebind"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_VOICE_NAME = "voice_name"
        private const val CHAN = "ot_overlay"
        private const val NOTIF_ID = 1
        private const val RESULT_NOTIF_ID = 10
        var projectionResultCode: Int = 0
        var projectionData: Intent? = null
    }

    private lateinit var wm: WindowManager
    private var radialMenu: RadialMenuView? = null
    private var resultView: View? = null
    private var regionView: RegionView? = null
    private var tts: TextToSpeech? = null
    private var ocr: OcrRouter? = null
    private var translator: Translator? = null
    private var voiceKind = VoiceKind.FEMALE
    private var voiceName: String? = null
    private var region: RectF? = null
    private var lastOcr = ""
    private var lastTr = ""
    private var lastHash = 0L
    private var live = false
    private var speak = true
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
            if (live && region != null && !busy.get()) captureThen(ocrOnly = false, livePass = true)
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
        Thread {
            ocr = OcrRouter(this)
            translator = Translator(this)
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                live = EnginePrefs.live(this)
                speak = EnginePrefs.speak(this)
                voiceKind = runCatching { VoiceKind.valueOf(intent.getStringExtra(EXTRA_VOICE) ?: "FEMALE") }
                    .getOrDefault(VoiceKind.FEMALE)
                voiceName = intent.getStringExtra(EXTRA_VOICE_NAME)
                VoiceHelper.apply(tts, voiceKind, voiceName)
                bindProjection()
                showMenu()
                handler.removeCallbacks(tick); handler.post(tick)
            }
            ACTION_REBIND -> bindProjection()
        }
        return START_STICKY
    }

    /* ─────── Projection ─────── */

    private fun scanLang() = when (EnginePrefs.scanLang(this)) {
        "EN" -> ScanLang.EN; "AUTO" -> ScanLang.BOTH; else -> ScanLang.RU
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

    /* ─────── SAO-style radial menu ─────── */

    private fun showMenu() {
        if (radialMenu != null) return
        val items = listOf(
            RadialMenuView.RadialItem("Скан", "🔍") {
                if (region == null) startRegionPick() else captureThen(true, false)
            },
            RadialMenuView.RadialItem("Область", "⬚") { startRegionPick() },
            RadialMenuView.RadialItem("Live", if (live) "🟢" else "⚪") {
                live = !live; EnginePrefs.setLive(this, live)
            },
            RadialMenuView.RadialItem("Голос", "🔊") {
                val t = lastTr.ifBlank { lastOcr }
                if (t.isNotBlank()) speakNow(t, true)
            },
            RadialMenuView.RadialItem("Голоса", "🗣") {
                VoiceDialog.show(this, voiceName) { name, kind ->
                    voiceName = name; voiceKind = kind
                }
            },
            RadialMenuView.RadialItem("Копировать", "📋") {
                val t = lastTr.ifBlank { lastOcr }
                if (t.isNotBlank()) {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ot", t))
                }
            },
            RadialMenuView.RadialItem("История", "📋") { HistoryDialog.show(this) },
            RadialMenuView.RadialItem("Стоп", "⏹") {
                live = false; stopSelf()
            },
        )
        val menu = RadialMenuView(this, items)
        radialMenu = menu
        val lp = WindowManager.LayoutParams(
            260, 260,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.END or Gravity.TOP
        lp.x = 16; lp.y = 120
        try { wm.addView(menu, lp) } catch (e: Exception) { Log.e(TAG, "menu addView", e) }
    }

    /* ─────── Region pick ─────── */

    private fun startRegionPick() {
        when (EnginePrefs.regionMode(this)) {
            "screen" -> {
                region = RectF(screenW * 0.04f, screenH * 0.08f, screenW * 0.96f, screenH * 0.92f)
                handler.postDelayed({ captureThen(true, false) }, 200); return
            }
            "wide" -> {
                region = RectF(screenW * 0.06f, screenH * 0.28f, screenW * 0.94f, screenH * 0.72f)
                handler.postDelayed({ captureThen(true, false) }, 200); return
            }
        }
        if (regionView != null) return
        radialMenu?.visibility = View.INVISIBLE
        val rv = RegionView(this)
        regionView = rv
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        rv.onPicked = { r ->
            region = r
            try { wm.removeView(rv) } catch (_: Exception) {}
            regionView = null
            radialMenu?.visibility = View.VISIBLE
            handler.postDelayed({ captureThen(true, false) }, 200)
        }
        wm.addView(rv, lp)
    }

    /* ─────── Scan ─────── */

    private fun captureThen(ocrOnly: Boolean, livePass: Boolean) {
        val r = region ?: return
        if (!busy.compareAndSet(false, true)) return
        radialMenu?.visibility = View.INVISIBLE
        handler.postDelayed({
            Thread {
                try {
                    val piece = grab(r) ?: return@Thread
                    try {
                        val h = PerceptualHash.of(piece)
                        if (livePass && PerceptualHash.isSimilar(h, lastHash)) return@Thread
                        lastHash = h
                        val router = ocr
                        if (router == null) { showText("OCR загружается…"); return@Thread }
                        val engine = EnginePrefs.ocr(this)
                        val text = router.read(piece, engine, scanLang()).trim()
                        if (text.isBlank()) { showText("(пусто)"); return@Thread }
                        if (text == lastOcr && ocrOnly) return@Thread
                        lastOcr = text; lastTr = ""
                        showText(text)
                        postResultNotification(text, "")
                        ScanHistory.add(this, text, "", engine)
                        if (!ocrOnly) applyTranslate(text)
                    } finally {
                        if (!piece.isRecycled) piece.recycle()
                    }
                } catch (e: Exception) {
                    showText("Ошибка: ${e.message}")
                } finally {
                    handler.post { radialMenu?.visibility = View.VISIBLE }
                    busy.set(false)
                }
            }.start()
        }, 140)
    }

    /* ─────── Translate ─────── */

    private fun applyTranslate(text: String) {
        if (!translating.compareAndSet(false, true)) return
        Thread {
            try {
                val joined = text.replace('\n', ' ')
                val skip = scanLang() == ScanLang.RU || ScriptDetect.preferRu(joined)
                val out = if (skip) RuText.clean(text)
                else translator?.translate(joined, EnginePrefs.tr(this)) ?: text
                val cleaned = RuText.clean(out)
                lastTr = cleaned
                showText("$text\n\n→ $cleaned")
                postResultNotification(text, cleaned)
                ScanHistory.add(this, text, cleaned, EnginePrefs.ocr(this))
                if (speak) speakNow(cleaned, false)
            } catch (e: Exception) {
                showText("Ошибка перевода: ${e.message}")
            } finally {
                translating.set(false)
            }
        }.start()
    }

    /* ─────── Result display ─────── */

    private fun showText(text: String) {
        handler.post {
            if (resultView == null) {
                resultView = LayoutInflater.from(this).inflate(R.layout.overlay_result, null)
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                )
                wm.addView(resultView, lp)
            }
            resultView?.visibility = View.VISIBLE
            resultView?.findViewById<TextView>(R.id.srcText)?.text = text
            resultView?.findViewById<TextView>(R.id.dstText)?.text = ""
            resultView?.findViewById<Button>(R.id.btnHide)?.setOnClickListener {
                resultView?.visibility = View.INVISIBLE
            }
            resultView?.findViewById<Button>(R.id.btnCopy)?.setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ot", text))
            }
            resultView?.findViewById<ScrollView>(R.id.resultScroll)
                ?.post { resultView?.findViewById<ScrollView>(R.id.resultScroll)?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /* ─────── Notifications ─────── */

    private fun postResultNotification(ocr: String, tr: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val combined = if (tr.isNotBlank()) "$ocr\n→ $tr" else ocr
        val openIntent = Intent(this, OverlayService::class.java).setAction(ACTION_START)
        val pi = PendingIntent.getService(this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val n = Notification.Builder(this, CHAN)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Результат скана")
            .setContentText(combined.take(100))
            .setStyle(Notification.BigTextStyle().bigText(combined.take(500)))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        nm.notify(RESULT_NOTIF_ID, n)
    }

    private fun baseNotification(text: String): Notification {
        val stopIntent = Intent(this, OverlayService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHAN)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Overlay Translator")
            .setContentText(text)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", stopPi)
            .setOngoing(true)
            .build()
    }

    /* ─────── Voice ─────── */

    private fun speakNow(text: String, force: Boolean) {
        if (!force && text == lastSpoken) return
        lastSpoken = text
        handler.post {
            VoiceHelper.apply(tts, voiceKind, voiceName)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ot")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ru", "RU")
            VoiceHelper.apply(tts, voiceKind, voiceName)
        }
    }

    /* ─────── Screen grab ─────── */

    private fun grab(r: RectF): Bitmap? {
        val img = reader?.acquireLatestImage() ?: reader?.acquireNextImage() ?: return null
        return try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val ps = plane.pixelStride; val rs = plane.rowStride; val pad = rs - ps * screenW
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
        handler.removeCallbacks(tick)
        listOf(radialMenu, resultView, regionView).forEach { v ->
            try { v?.let { wm.removeView(it) } } catch (_: Exception) {}
        }
        vdisplay?.release(); reader?.close(); projection?.stop()
        ocr?.close(); tts?.shutdown()
        super.onDestroy()
    }

    private fun addDraggable(v: View, gravity: Int, x: Int, y: Int) {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = gravity; lp.x = x; lp.y = y
        var px = 0f; var py = 0f
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { px = e.rawX; py = e.rawY; false }
                MotionEvent.ACTION_MOVE -> {
                    view.translationX += e.rawX - px; view.translationY += e.rawY - py
                    px = e.rawX; py = e.rawY; true
                }
                else -> false
            }
        }
        try { wm.addView(v, lp) } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "OverlayService"
    }
}
