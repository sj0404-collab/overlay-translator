package com.overlay.translator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import android.widget.TextView
import com.overlay.translator.databinding.OverlayBubbleBinding
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class OverlayService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_REBIND = "rebind"
        const val EXTRA_EN = "en"
        const val EXTRA_LIVE = "live"
        const val EXTRA_SPEAK = "speak"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_VOICE_NAME = "voice_name"
        const val EXTRA_TR = "tr"
        var projectionResultCode: Int = 0
        var projectionData: Intent? = null
    }

    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var side: View? = null
    private var resultView: View? = null
    private var regionView: RegionView? = null
    private var tts: TextToSpeech? = null
    private var vision: OnnxVision? = null
    private var tess: TessOcr? = null
    private var translator: Translator? = null
    private var scanLang = ScanLang.EN
    private var live = false
    private var speak = true
    private var voiceKind = VoiceKind.FEMALE
    private var voiceName: String? = null
    private var trMode = Translator.Mode.LOCAL_THEN_ONLINE
    private var region: RectF? = null
    private var lastOcr = ""
    private var lastTr = ""
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private var lastSpoken = ""
    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var screenW = 0
    private var screenH = 0
    private var density = 0

    private val tick = object : Runnable {
        override fun run() {
            if (live && region != null) captureThen(ocrOnly = true, findBubbles = true)
            handler.postDelayed(this, 2800)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("ot", "Overlay", NotificationManager.IMPORTANCE_LOW))
        startForeground(
            1,
            Notification.Builder(this, "ot")
                .setContentTitle("Overlay Translator")
                .setContentText("Выберите область, затем Скан")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build()
        )
        tts = TextToSpeech(this, this)
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        density = dm.densityDpi
        Thread {
            vision = OnnxVision(this)
            tess = TessOcr(this)
            translator = Translator(this)
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                scanLang = if (intent.getBooleanExtra(EXTRA_EN, true)) ScanLang.EN else ScanLang.RU
                live = false
                speak = intent.getBooleanExtra(EXTRA_SPEAK, true)
                voiceKind = runCatching { VoiceKind.valueOf(intent.getStringExtra(EXTRA_VOICE) ?: "FEMALE") }
                    .getOrDefault(VoiceKind.FEMALE)
                voiceName = intent.getStringExtra(EXTRA_VOICE_NAME)
                trMode = runCatching {
                    Translator.Mode.valueOf(intent.getStringExtra(EXTRA_TR) ?: "LOCAL_THEN_ONLINE")
                }.getOrDefault(Translator.Mode.LOCAL_THEN_ONLINE)
                tess?.reopen(scanLang)
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
            val bind = OverlayBubbleBinding.inflate(LayoutInflater.from(this))
            bubble = bind.root
            updateLangLabel(bind.btnLang)
            bind.btnLang.setOnClickListener {
                scanLang = when (scanLang) {
                    ScanLang.EN -> ScanLang.RU
                    ScanLang.RU -> ScanLang.BOTH
                    ScanLang.BOTH -> ScanLang.EN
                }
                tess?.reopen(scanLang)
                updateLangLabel(bind.btnLang)
            }
            bind.btnSelect.setOnClickListener { startRegionPick() }
            bind.btnOnce.setOnClickListener {
                if (region == null) startRegionPick()
                else captureThen(ocrOnly = true, findBubbles = false)
            }
            bind.btnLive.setOnClickListener {
                live = !live
                bind.btnLive.text = if (live) "Live:вкл" else "Live:выкл"
            }
            addDraggable(bind.root, Gravity.TOP or Gravity.START, 24, 160)
        }
        if (side == null) {
            side = LayoutInflater.from(this).inflate(R.layout.overlay_side, null)
            side!!.findViewById<Button>(R.id.btnTranslate).setOnClickListener {
                if (lastOcr.isBlank()) captureThen(ocrOnly = false, findBubbles = true)
                else applyTranslate(lastOcr)
            }
            side!!.findViewById<Button>(R.id.btnBubbles).setOnClickListener {
                captureThen(ocrOnly = true, findBubbles = true)
            }
            side!!.findViewById<Button>(R.id.btnSpeak).setOnClickListener {
                val t = lastTr.ifBlank { lastOcr }
                if (t.isNotBlank()) speakNow(t, force = true)
            }
            addDraggable(side!!, Gravity.TOP or Gravity.START, 8, 420)
        }
    }

    private fun updateLangLabel(tv: TextView) {
        tv.text = when (scanLang) {
            ScanLang.EN -> "EN"
            ScanLang.RU -> "RU"
            ScanLang.BOTH -> "EN+RU"
        }
    }

    private fun addDraggable(v: View, gravity: Int, x: Int, y: Int) {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = gravity
        lp.x = x; lp.y = y
        var px = 0; var py = 0
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { px = e.rawX.toInt(); py = e.rawY.toInt(); false }
                MotionEvent.ACTION_MOVE -> {
                    lp.x += e.rawX.toInt() - px; lp.y += e.rawY.toInt() - py
                    px = e.rawX.toInt(); py = e.rawY.toInt()
                    try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
        try { wm.addView(v, lp) } catch (_: Exception) {}
    }

    private fun startRegionPick() {
        if (regionView != null) return
        setChrome(false)
        val rv = RegionView(this)
        regionView = rv
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        rv.onPicked = { r ->
            region = r
            try { wm.removeView(rv) } catch (_: Exception) {}
            regionView = null
            setChrome(true)
            showResult("область выбрана — нажмите Скан", "перевод по кнопке слева")
        }
        wm.addView(rv, lp)
    }

    private fun setChrome(show: Boolean) {
        val vis = if (show) View.VISIBLE else View.INVISIBLE
        bubble?.visibility = vis
        side?.visibility = vis
        resultView?.visibility = vis
    }

    private fun showResult(src: String, dst: String) {
        handler.post {
            if (resultView == null) {
                resultView = LayoutInflater.from(this).inflate(R.layout.overlay_result, null)
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                lp.y = 100
                wm.addView(resultView, lp)
            }
            resultView?.visibility = View.VISIBLE
            resultView?.findViewById<TextView>(R.id.srcText)?.text = src
            resultView?.findViewById<TextView>(R.id.dstText)?.text = dst
        }
    }

    private fun captureThen(ocrOnly: Boolean, findBubbles: Boolean) {
        val r = region ?: return
        if (!busy.compareAndSet(false, true)) return
        setChrome(false)
        handler.postDelayed({
            Thread {
                try {
                    val piece = grab(r) ?: return@Thread
                    val parts = ArrayList<String>()
                    if (findBubbles) {
                        val bubbles = ImagePrep.findBubbles(piece)
                        if (bubbles.isNotEmpty()) {
                            for (b in bubbles) {
                                val crop = Bitmap.createBitmap(
                                    piece, b.rect.left, b.rect.top, b.rect.width(), b.rect.height()
                                )
                                val t = ocrBitmap(crop)
                                if (t.isNotBlank()) parts.add(t)
                            }
                        }
                    }
                    if (parts.isEmpty()) {
                        val t = ocrBitmap(piece)
                        if (t.isNotBlank()) parts.add(t)
                    }
                    val text = parts.joinToString("\n").trim()
                    if (text.isBlank()) {
                        showResult("(пусто — смените EN/RU или область)", "")
                        return@Thread
                    }
                    if (text == lastOcr && ocrOnly) return@Thread
                    lastOcr = text
                    lastTr = ""
                    showResult(text, if (ocrOnly) "нажмите «Перевести» слева" else "")
                    if (!ocrOnly) applyTranslate(text)
                } catch (_: Exception) {
                } finally {
                    handler.post { setChrome(true) }
                    busy.set(false)
                }
            }.start()
        }, 90)
    }

    private fun ocrBitmap(src: Bitmap): String {
        val prep = ImagePrep.prepareForOcr(src)
        var text = tess?.read(prep).orEmpty()
        if (text.length < 2) {
            val crnn = vision?.crnnLine(prep).orEmpty()
            if (crnn.length > text.length) text = crnn
        }
        val en = scanLang != ScanLang.RU
        return ImagePrep.cleanOcr(text, en && scanLang == ScanLang.EN)
    }

    private fun applyTranslate(text: String) {
        Thread {
            val out = if (scanLang == ScanLang.RU) text
            else translator?.translate(text.replace('\n', ' '), trMode) ?: text
            lastTr = out
            showResult(text, out)
            if (speak) speakNow(out, force = false)
        }.start()
    }

    private fun speakNow(text: String, force: Boolean) {
        if (!force && text == lastSpoken) return
        lastSpoken = text
        handler.post {
            VoiceHelper.apply(tts, voiceKind, voiceName)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "t")
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
            if (crop.width() < 8 || crop.height() < 8) null
            else Bitmap.createBitmap(bmp, crop.left, crop.top, crop.width(), crop.height())
        } finally {
            img.close()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ru", "RU")
            VoiceHelper.apply(tts, voiceKind, voiceName)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { bubble?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { side?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { resultView?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { regionView?.let { wm.removeView(it) } } catch (_: Exception) {}
        vdisplay?.release()
        reader?.close()
        projection?.stop()
        vision?.close()
        tess?.close()
        tts?.shutdown()
        super.onDestroy()
    }
}
