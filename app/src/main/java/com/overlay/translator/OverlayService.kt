package com.overlay.translator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
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
        var projectionResultCode: Int = 0
        var projectionData: Intent? = null
    }

    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var side: View? = null
    private var resultView: View? = null
    private var fab: TextView? = null
    private var regionView: RegionView? = null
    private var tts: TextToSpeech? = null
    private var ocr: OcrRouter? = null
    private var translator: Translator? = null
    private var live = false
    private var speak = true
    private var voiceKind = VoiceKind.FEMALE
    private var voiceName: String? = null
    private var region: RectF? = null
    private var lastOcr = ""
    private var lastTr = ""
    private var lastHash = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private val translating = AtomicBoolean(false)
    private var lastSpoken = ""
    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var screenW = 0
    private var screenH = 0
    private var density = 0

    private val tick = object : Runnable {
        override fun run() {
            if (live && region != null && !busy.get()) captureThen(ocrOnly = false, livePass = true)
            handler.postDelayed(this, 5200)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("ot", "Overlay", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, "ot")
            .setContentTitle("Overlay Translator")
            .setContentText("Zen Vision")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build())
        tts = TextToSpeech(this, this)
        val dm = DisplayMetrics()
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
                showChrome()
                handler.removeCallbacks(tick)
                handler.post(tick)
            }
            ACTION_REBIND -> bindProjection()
        }
        return START_STICKY
    }

    private fun scanLang() = when (EnginePrefs.scanLang(this)) {
        "EN" -> ScanLang.EN
        "AUTO" -> ScanLang.BOTH
        else -> ScanLang.RU
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
            "ocr", screenW, screenH, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, handler
        )
    }

    private fun showChrome() {
        if (bubble == null) {
            // Single floating trigger button that toggles the side panel
            val fab = TextView(this)
            fab.text = "☰"
            fab.setTextColor(0xFFFFFFFF.toInt())
            fab.setBackgroundColor(0xE05B8DEF.toInt())
            fab.setPadding(28, 18, 28, 18)
            fab.textSize = 22f
            fab.setOnClickListener { togglePanel() }
            this.fab = fab
            addDraggable(fab, Gravity.END or Gravity.TOP, 24, 140)
        }
        if (side == null) {
            // Side panel with ALL controls — replaces the old bubble
            val panel = LayoutInflater.from(this).inflate(R.layout.overlay_side, null)
            side = panel
            // Language toggle
            val langBtn = panel.findViewById<Button>(R.id.btnTranslate)
            langBtn.text = EnginePrefs.scanLang(this)
            langBtn.setOnClickListener {
                val next = when (EnginePrefs.scanLang(this)) {
                    "RU" -> "EN"; "EN" -> "AUTO"; else -> "RU"
                }
                EnginePrefs.setScanLang(this, next)
                langBtn.text = next
            }
            // Region select + immediate scan
            panel.findViewById<Button>(R.id.btnBubbles).text = "Область"
            panel.findViewById<Button>(R.id.btnBubbles).setOnClickListener { startRegionPick() }
            // One-shot scan
            panel.findViewById<Button>(R.id.btnSpeak).text = "Скан"
            panel.findViewById<Button>(R.id.btnSpeak).setOnClickListener {
                if (region == null) startRegionPick() else captureThen(true, false)
            }
            // Live toggle (reusing btnSpeak's row with extra button if available)
            addDraggable(panel, Gravity.END or Gravity.TOP, 8, 200)
            panel.visibility = View.INVISIBLE
        }
    }

    private fun togglePanel() {
        val p = side ?: return
        if (p.visibility == View.VISIBLE) {
            p.visibility = View.INVISIBLE
            fab?.visibility = View.VISIBLE
        } else {
            p.visibility = View.VISIBLE
        }
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
                MotionEvent.ACTION_DOWN -> {
                    px = e.rawX; py = e.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    view.translationX += e.rawX - px
                    view.translationY += e.rawY - py
                    px = e.rawX; py = e.rawY
                    true
                }
                else -> false
            }
        }
        try { wm.addView(v, lp) } catch (_: Exception) {}
    }

    private fun startRegionPick() {
        when (EnginePrefs.regionMode(this)) {
            "screen" -> {
                region = RectF(screenW * 0.04f, screenH * 0.08f, screenW * 0.96f, screenH * 0.92f)
                handler.postDelayed({ captureThen(true, false) }, 200)
                return
            }
            "wide" -> {
                region = RectF(screenW * 0.06f, screenH * 0.28f, screenW * 0.94f, screenH * 0.72f)
                handler.postDelayed({ captureThen(true, false) }, 200)
                return
            }
        }
        if (regionView != null) return
        setChrome(false)
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
            setChrome(true)
            // Auto-scan immediately after area selection
            handler.postDelayed({ captureThen(true, false) }, 200)
        }
        wm.addView(rv, lp)
    }

    private fun setChrome(show: Boolean) {
        if (!show) {
            side?.visibility = View.INVISIBLE
            resultView?.visibility = View.INVISIBLE
            fab?.visibility = View.VISIBLE
        } else {
            side?.visibility = View.INVISIBLE
        }
    }

    private fun showResult(src: String, dst: String) {
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
            // Keep trigger FAB visible even over results
            resultView?.findViewById<TextView>(R.id.srcText)?.text = src
            resultView?.findViewById<TextView>(R.id.dstText)?.text = dst
            val sc = resultView?.findViewById<ScrollView>(R.id.resultScroll)
            resultView?.findViewById<Button>(R.id.btnCopy)?.setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("tr", dst.ifBlank { src }))
            }
            resultView?.findViewById<Button>(R.id.btnDown)?.setOnClickListener {
                sc?.post { sc.fullScroll(View.FOCUS_DOWN) }
            }
            resultView?.findViewById<Button>(R.id.btnHide)?.setOnClickListener {
                resultView?.visibility = View.INVISIBLE
            }
            sc?.post { sc.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun captureThen(ocrOnly: Boolean, livePass: Boolean) {
        val r = region ?: return
        if (!busy.compareAndSet(false, true)) return
        setChrome(false)
        handler.postDelayed({
            Thread {
                try {
                    val piece = grab(r) ?: return@Thread
                    try {
                        val h = PerceptualHash.of(piece)
                        if (livePass && PerceptualHash.isSimilar(h, lastHash)) return@Thread
                        lastHash = h
                        val router = ocr
                        if (router == null) {
                            showResult("OCR ещё запускается…", "Повторите через секунду")
                            return@Thread
                        }
                        val text = router.read(piece, EnginePrefs.ocr(this), scanLang()).trim()
                        if (text.isBlank()) {
                            showResult("(пусто — повторите скан)", ""); return@Thread
                        }
                        if (text == lastOcr && ocrOnly) return@Thread
                        lastOcr = text; lastTr = ""
                        showResult(text, if (ocrOnly) "Перевести слева" else "")
                        if (!ocrOnly) applyTranslate(text)
                    } finally {
                        if (!piece.isRecycled) piece.recycle()
                    }
                } catch (error: Exception) {
                    showResult("Ошибка сканирования", error.message ?: "Не удалось обработать изображение")
                } finally {
                    handler.post { setChrome(true) }
                    busy.set(false)
                }
            }.start()
        }, 140)
    }

    private fun applyTranslate(text: String) {
        if (!translating.compareAndSet(false, true)) return
        Thread {
            try {
                val joined = text.replace('\n', ' ')
                val skip = scanLang() == ScanLang.RU || ScriptDetect.preferRu(joined)
                var out = when {
                    skip -> RuText.clean(text)
                    else -> translator?.translate(joined, EnginePrefs.tr(this)) ?: text
                }
                out = RuText.clean(out)
                lastTr = out
                showResult(text, out)
                if (speak) speakNow(out, false)
            } catch (error: Exception) {
                showResult(text, "Ошибка перевода: ${error.message ?: "неизвестная ошибка"}")
            } finally {
                translating.set(false)
            }
        }.start()
    }

    private fun speakNow(text: String, force: Boolean) {
        if (!force && text == lastSpoken) return
        lastSpoken = text
        handler.post {
            VoiceHelper.apply(tts, voiceKind, voiceName)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "t")
            resultView?.findViewById<ScrollView>(R.id.resultScroll)?.post {
                resultView?.findViewById<ScrollView>(R.id.resultScroll)?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun grab(r: RectF): Bitmap? {
        val img = reader?.acquireLatestImage() ?: reader?.acquireNextImage() ?: return null
        return try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenW
            val bmp = Bitmap.createBitmap(screenW + rowPadding / pixelStride, screenH, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            val crop = Rect(
                r.left.toInt().coerceIn(0, screenW - 1),
                r.top.toInt().coerceIn(0, screenH - 1),
                r.right.toInt().coerceIn(1, screenW),
                r.bottom.toInt().coerceIn(1, screenH)
            )
            if (crop.width() < 8 || crop.height() < 8) {
                bmp.recycle()
                null
            } else {
                val result = Bitmap.createBitmap(bmp, crop.left, crop.top, crop.width(), crop.height())
                bmp.recycle()
                result
            }
        } finally { img.close() }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ru", "RU")
            VoiceHelper.apply(tts, voiceKind, voiceName)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        listOf(bubble, side, resultView, fab, regionView).forEach { v ->
            try { v?.let { wm.removeView(it) } } catch (_: Exception) {}
        }
        vdisplay?.release(); reader?.close(); projection?.stop()
        ocr?.close(); tts?.shutdown()
        super.onDestroy()
    }
}
