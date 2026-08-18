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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
        private const val TAG = "OverlayService"
        private const val CHAN = "ot_overlay"
        private const val NOTIF_ID = 1
        private const val RESULT_NOTIF_ID = 10
        var projectionResultCode: Int = 0
        var projectionData: Intent? = null
    }

    private lateinit var wm: WindowManager
    private var menu: VerticalMenuView? = null
    private var menuLp: WindowManager.LayoutParams? = null
    private var regionView: RegionView? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ocr: OcrRouter? = null
    private var translator: Translator? = null
    private var voiceKind = VoiceKind.FEMALE
    private var voiceName: String? = null
    private var region: RectF? = null
    private var regionPreset = "rect" // rect | screen | wide | bottom
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
                live = EnginePrefs.live(this)
                autoTranslate = EnginePrefs.autoTranslate(this)
                voiceKind = runCatching { VoiceKind.valueOf(intent.getStringExtra(EXTRA_VOICE) ?: "FEMALE") }
                    .getOrDefault(VoiceKind.FEMALE)
                voiceName = intent.getStringExtra(EXTRA_VOICE_NAME)
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

    /* ─────── Vertical menu ─────── */

    private fun showMenu() {
        if (menu != null) return
        regionPreset = EnginePrefs.regionMode(this)
        val items = listOf(
            VerticalMenuView.VerticalItem("Скан (${regionLabel(regionPreset)})", "🔍") {
                if (regionPreset == "rect") {
                    if (region == null) startRegionPick() else captureThen(ocrOnly = !autoTranslate)
                } else {
                    region = applyRegionPreset()
                    captureThen(ocrOnly = !autoTranslate)
                }
            },
            VerticalMenuView.VerticalItem(
                "Зона: ${regionLabel(regionPreset)}", "📐"
            ) { cycleRegionPreset() },
            VerticalMenuView.VerticalItem(
                "Live ${if (autoTranslate) "+ Перевод" else ""}", if (live) "🟢" else "⚪"
            ) { toggleLive() },
            VerticalMenuView.VerticalItem("Озвучить", "🔊") {
                try {
                    if (ttsReady) {
                        val t = lastTr.ifBlank { lastOcr }
                        if (t.isNotBlank()) speakNow(t, true)
                        else toast("Нет текста")
                    } else toast("TTS не готов")
                } catch (e: Exception) {
                    Log.e(TAG, "voice err", e); toast("Ошибка TTS")
                }
            },
            VerticalMenuView.VerticalItem("Выбор голоса", "🗣") {
                try {
                    if (ttsReady) {
                        VoiceDialog.show(this, wm, voiceName) { name, kind ->
                            voiceName = name; voiceKind = kind; safeApplyVoice()
                            toast("Голос: ${name.substringAfterLast(":")}")
                        }
                    } else toast("TTS не готов")
                } catch (e: Exception) {
                    Log.e(TAG, "VoiceDialog err", e); toast("Ошибка диалога")
                }
            },
            VerticalMenuView.VerticalItem("Копировать", "📋") {
                val t = lastTr.ifBlank { lastOcr }
                if (t.isNotBlank()) {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ot", t)); toast("✓ Скопировано")
                } else toast("Нет текста")
            },
            VerticalMenuView.VerticalItem("История", "📋") {
                try { HistoryDialog.show(this, wm) } catch (e: Exception) { Log.e(TAG, "hist err", e) }
            },
            VerticalMenuView.VerticalItem("Стоп", "⏹") { live = false; stopSelf() },
        )
        val v = VerticalMenuView(this, items)
        menu = v
        menuLp = WindowManager.LayoutParams(
            240, (v.expandedHeight()).toInt().coerceAtLeast(100),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        menuLp?.gravity = Gravity.END or Gravity.TOP
        menuLp?.x = 12; menuLp?.y = 100
        v.attachWindowManager(wm, menuLp!!)
        try { wm.addView(v, menuLp) } catch (e: Exception) { Log.e(TAG, "menu addView", e) }
    }

    private fun regionLabel(p: String) = when (p) {
        "rect" -> "область"; "screen" -> "экран"; "wide" -> "полоса"; "bottom" -> "низ"
        else -> p
    }

    private fun applyRegionPreset(): RectF = when (regionPreset) {
        "screen" -> RectF(0f, 0f, screenW.toFloat(), screenH.toFloat())
        "wide" -> RectF(0f, screenH * 0.30f, screenW.toFloat(), screenH * 0.70f)
        "bottom" -> RectF(0f, screenH * 0.55f, screenW.toFloat(), screenH * 0.92f)
        else -> RectF(0f, 0f, screenW.toFloat(), screenH.toFloat()) // rect = full screen fallback
    }

    private fun toggleLive() {
        live = !live; EnginePrefs.setLive(this, live)
        autoTranslate = !autoTranslate; EnginePrefs.setAutoTranslate(this, autoTranslate)
        menu?.collapse()
        handler.postDelayed({ showMenu() }, 250)
        toast(if (autoTranslate) "Live перевод: вкл" else "Live: ${if (live) "вкл" else "выкл"}")
    }

    private fun cycleRegionPreset() {
        val presets = listOf("rect", "screen", "wide", "bottom")
        val i = presets.indexOf(regionPreset).coerceAtLeast(0)
        regionPreset = presets[(i + 1) % presets.size]
        EnginePrefs.setRegionMode(this, regionPreset)
        region = null // reset manual region
        toast("Зона: ${regionLabel(regionPreset)}")
        // Re-show menu to update the item label
        menu?.collapse()
        handler.postDelayed({ showMenu() }, 250)
    }

    /* ─────── Region ─────── */

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
            captureThen(ocrOnly = !autoTranslate)
        }
        wm.addView(rv, lp)
    }

    /* ─────── Toast ─────── */

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

    /* ─────── Scan ─────── */

    private fun captureThen(ocrOnly: Boolean, livePass: Boolean = false) {
        val r = region ?: applyRegionPreset().also { region = it }
        if (!busy.compareAndSet(false, true)) return
        menu?.visibility = View.INVISIBLE
        if (!livePass) toast("⏳ Сканирую…")
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
                        // Post-process: collapse dots/exclamation, normalize spacing
                        text = TextPostprocessor().postprocess(text)
                        // Filter out icon labels / badge noise (short ASCII-only junk)
                        // but keep any line with Cyrillic (real Russian text) regardless of length
                        text = text.lines().filter { line ->
                            if (line.isBlank()) return@filter false
                            val hasCyrillic = line.any { it in '\u0400'..'\u04FF' }
                            val isAsciiOnly = line.all { it in 'A'..'z' || it.isDigit() || it == ' ' || it == '/' }
                            hasCyrillic || (line.length > 4 && isAsciiOnly && line.count { it.isLetter() } > 2)
                        }.joinToString("\n")
                        if (text.isBlank()) { if (!livePass) toast("(пусто)"); return@Thread }
                        lastOcr = text; lastTr = ""
                        if (!livePass) toast("✓ ${text.take(60)}")
                        postResultNotification(text, "")
                        ScanHistory.add(this, text, "", engine)
                        if (!ocrOnly) applyTranslate(text)
                    } finally { if (!piece.isRecycled) piece.recycle() }
                } catch (e: Exception) {
                    Log.e(TAG, "scan error", e); if (!livePass) toast("Ошибка: ${e.message?.take(50)}")
                } finally {
                    handler.post { menu?.visibility = View.VISIBLE }; busy.set(false)
                }
            }.start()
        }, 140)
    }

    /* ─────── Translate ─────── */

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
                // Best-effort voice selection based on text linguistics
                Thread {
                    val decision = VoiceAssistant.analyze(text, voiceKind)
                    handler.post {
                        applyVoiceDecision(decision)
                        toast("🎙 ${decision.reason}")
                    }
                }.start()
            } catch (e: Exception) {
                Log.e(TAG, "translate error", e); toast("Ошибка: ${e.message?.take(50)}")
            } finally { translating.set(false) }
        }.start()
    }

    /** Apply voice decision from VoiceAssistant. */
    private fun applyVoiceDecision(d: VoiceAssistant.VoiceDecision) {
        if (tts == null || !ttsReady) return
        voiceKind = d.kind
        safeApplyVoice()
        try {
            tts?.setPitch(d.pitch)
            tts?.setSpeechRate(d.rate)
        } catch (e: Exception) { Log.w(TAG, "pitch/rate failed", e) }
    }

    /** Fallback: pick voice/pitch based on detected speaker. */
    private fun applyVoiceForSpeaker(speaker: VoiceKind) {
        if (tts == null || !ttsReady) return
        try {
            when (speaker) {
                VoiceKind.MALE -> {
                    voiceKind = VoiceKind.MALE
                    safeApplyVoice()
                    tts?.setPitch(0.95f) // Slightly lower for male
                    toast("Голос: мужской")
                }
                VoiceKind.FEMALE -> {
                    voiceKind = VoiceKind.FEMALE
                    safeApplyVoice()
                    tts?.setPitch(1.05f) // Slightly higher for female
                    toast("Голос: женский")
                }
                VoiceKind.TEEN -> {
                    voiceKind = VoiceKind.MALE // Assume male teen for now
                    safeApplyVoice()
                    tts?.setPitch(1.15f) // Higher pitch for teen
                    toast("Голос: подростковый")
                }
                else -> {
                    voiceKind = VoiceKind.FEMALE // Default for narrator/other
                    safeApplyVoice()
                    tts?.setPitch(0.92f) // Narration tone
                    toast("Голос: рассказчик")
                }
            }
        } catch (e: Exception) { Log.w(TAG, "voice apply", e) }
        tts?.setSpeechRate(0.96f)
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

    /* ─────── Voice ─────── */

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
        } else { ttsReady = false; Log.w(TAG, "TTS init: $status") }
    }

    /* ─────── Grab ─────── */

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
        handler.removeCallbacks(tick)
        listOf(menu, regionView).forEach { v ->
            try { v?.let { wm.removeView(it) } } catch (_: Exception) {}
        }
        DialogOverlay.dismiss()
        vdisplay?.release(); reader?.close(); projection?.stop()
        try { tts?.shutdown() } catch (_: Exception) {}; ocr?.close()
        super.onDestroy()
    }

    private fun addDraggable(v: View, lp: WindowManager.LayoutParams) {
        var px = 0f; var py = 0f
        var initialX = 0f; var initialY = 0f
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x.toFloat(); initialY = lp.y.toFloat()
                    px = e.rawX; py = e.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - px; val dy = e.rawY - py
                    lp.x = (initialX + dx).toInt(); lp.y = (initialY + dy).toInt()
                    wm.updateViewLayout(view, lp)
                    true
                }
                else -> false
            }
        }
        try { wm.addView(v, lp) } catch (_: Exception) {}
    }
}
