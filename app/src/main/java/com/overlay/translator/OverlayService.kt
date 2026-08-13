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
    private var resultView: View? = null
    private var regionView: RegionView? = null
    private var tts: TextToSpeech? = null
    private var vision: OnnxVision? = null
    private var tess: TessOcr? = null
    private var translator: Translator? = null
    private var english = true
    private var live = true
    private var speak = true
    private var voiceKind = VoiceKind.FEMALE
    private var voiceName: String? = null
    private var trMode = Translator.Mode.LOCAL_THEN_ONLINE
    private var region: RectF? = null
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
            if (live && region != null) captureAndProcess()
            handler.postDelayed(this, 1300)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("ot", "Overlay", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, "ot")
            .setContentTitle("Overlay Translator EN/RU")
            .setContentText("OCR + русская озвучка")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build())
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
                english = intent.getBooleanExtra(EXTRA_EN, true)
                live = intent.getBooleanExtra(EXTRA_LIVE, true)
                speak = intent.getBooleanExtra(EXTRA_SPEAK, true)
                voiceKind = runCatching { VoiceKind.valueOf(intent.getStringExtra(EXTRA_VOICE) ?: "FEMALE") }.getOrDefault(VoiceKind.FEMALE)
                voiceName = intent.getStringExtra(EXTRA_VOICE_NAME)
                trMode = runCatching { Translator.Mode.valueOf(intent.getStringExtra(EXTRA_TR) ?: "LOCAL_THEN_ONLINE") }
                    .getOrDefault(Translator.Mode.LOCAL_THEN_ONLINE)
                tess?.setMode(english)
                VoiceHelper.apply(tts, voiceKind, voiceName)
                bindProjection()
                showBubble()
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

    private fun showBubble() {
        if (bubble != null) return
        val bind = OverlayBubbleBinding.inflate(LayoutInflater.from(this))
        bubble = bind.root
        bind.bubbleLabel.text = if (english) "EN→RU" else "RU"
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 40; lp.y = 200
        var px = 0; var py = 0
        bind.root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { px = e.rawX.toInt(); py = e.rawY.toInt(); true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x += e.rawX.toInt() - px; lp.y += e.rawY.toInt() - py
                    px = e.rawX.toInt(); py = e.rawY.toInt()
                    wm.updateViewLayout(bind.root, lp); true
                }
                else -> false
            }
        }
        bind.btnSelect.setOnClickListener { startRegionPick() }
        bind.btnOnce.setOnClickListener { captureAndProcess() }
        wm.addView(bind.root, lp)
    }

    private fun startRegionPick() {
        if (regionView != null) return
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
            captureAndProcess()
        }
        wm.addView(rv, lp)
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
                lp.y = 120
                wm.addView(resultView, lp)
            }
            resultView?.findViewById<TextView>(R.id.srcText)?.text = src
            resultView?.findViewById<TextView>(R.id.dstText)?.text = dst
        }
    }

    private fun captureAndProcess() {
        val r = region ?: return
        if (!busy.compareAndSet(false, true)) return
        Thread {
            try {
                val img = reader?.acquireLatestImage()
                if (img == null) { busy.set(false); return@Thread }
                val plane = img.planes[0]
                val buf = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * screenW
                val bmp = Bitmap.createBitmap(screenW + rowPadding / pixelStride, screenH, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buf)
                img.close()
                val crop = Rect(
                    r.left.toInt().coerceIn(0, screenW - 1),
                    r.top.toInt().coerceIn(0, screenH - 1),
                    r.right.toInt().coerceIn(1, screenW),
                    r.bottom.toInt().coerceIn(1, screenH)
                )
                if (crop.width() < 8 || crop.height() < 8) { busy.set(false); return@Thread }
                val piece = Bitmap.createBitmap(bmp, crop.left, crop.top, crop.width(), crop.height())
                bmp.recycle()
                recognize(piece)
            } catch (_: Exception) {
                busy.set(false)
            }
        }.start()
    }

    private fun recognize(raw: Bitmap) {
        try {
            val enhanced = vision?.enhance(raw) ?: raw
            var text = tess?.read(enhanced).orEmpty()
            if (text.length < 2) {
                val crnn = vision?.crnnLine(enhanced).orEmpty()
                if (crnn.length > text.length) text = crnn
            }
            text = text.replace(Regex("[\\r\\n]+"), " ").trim()
            if (text.isBlank()) { busy.set(false); return }
            val out = if (english) translator?.translate(text, trMode) ?: text else text
            showResult(text, out)
            if (speak && out != lastSpoken) {
                lastSpoken = out
                handler.post {
                    VoiceHelper.apply(tts, voiceKind, voiceName)
                    tts?.speak(out, TextToSpeech.QUEUE_FLUSH, null, "t")
                }
            }
        } finally {
            busy.set(false)
            if (raw != null && !raw.isRecycled) raw.recycle()
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
