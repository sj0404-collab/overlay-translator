package com.overlay.translator

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Main window shell. The "Рамка" tab is a native control panel (overlay and
 * capture permissions, start/stop of the floating overlay); the "Сайт" tab is
 * a WebView browser for translating web pages. Android-only operations are
 * driven by native buttons instead of a JS bridge.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var siteWeb: WebView
    private lateinit var urlField: android.widget.EditText

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            OverlayService.projectionResultCode = result.resultCode
            OverlayService.projectionData = result.data
        }
        updateFrameUi()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.btnOverlayStep).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
            updateFrameUi()
        }

        findViewById<View>(R.id.btnCaptureStep).setOnClickListener {
            if (OverlayService.projectionData == null) {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                capture.launch(manager.createScreenCaptureIntent())
            }
            updateFrameUi()
        }

        findViewById<View>(R.id.btnStart).setOnClickListener {
            if (OverlayService.isRunning) {
                startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
                updateFrameUi()
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this) || OverlayService.projectionData == null) {
                updateFrameUi()
                return@setOnClickListener
            }
            startForegroundService(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START
            })
            updateFrameUi()
            moveTaskToBack(true)
        }

        siteWeb = findViewById(R.id.siteWeb)
        with(siteWeb.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        siteWeb.webViewClient = WebViewClient()

        urlField = findViewById(R.id.urlField)
        urlField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { openUrl(urlField.text.toString()); true } else false
        }
        findViewById<View>(R.id.goBtn).setOnClickListener { openUrl(urlField.text.toString()) }

        findViewById<View>(R.id.tabFrame).setOnClickListener { showScreen("frame") }
        findViewById<View>(R.id.tabSite).setOnClickListener { showScreen("site") }

        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateFrameUi()
    }

    private fun updateFrameUi() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val captureGranted = OverlayService.projectionData != null
        val running = OverlayService.isRunning
        val ready = overlayGranted && captureGranted

        val readyColor = "#22C55E"
        val idleColor = "#8EA6C8"
        val readyGreen = ColorStateList.valueOf(android.graphics.Color.parseColor(readyColor))
        val idleSlate = ColorStateList.valueOf(android.graphics.Color.parseColor(idleColor))

        val stepNum1 = findViewById<TextView>(R.id.stepOverlayNum)
        val stepBtn1 = findViewById<Button>(R.id.btnOverlayStep)
        val stepNum2 = findViewById<TextView>(R.id.stepCaptureNum)
        val stepBtn2 = findViewById<Button>(R.id.btnCaptureStep)
        val startBtn = findViewById<Button>(R.id.btnStart)
        val dot = findViewById<View>(R.id.footerDot)
        val footer = findViewById<TextView>(R.id.footerText)

        stepNum1.text = if (overlayGranted) "✓" else "01"
        stepNum1.setTextColor(android.graphics.Color.parseColor(if (overlayGranted) readyColor else idleColor))
        stepNum1.backgroundTintList = if (overlayGranted) readyGreen else idleSlate
        stepBtn1.text = if (overlayGranted) "Разрешено" else "Открыть настройки"
        stepBtn1.isEnabled = !overlayGranted

        stepNum2.text = if (captureGranted) "✓" else "02"
        stepNum2.setTextColor(android.graphics.Color.parseColor(if (captureGranted) readyColor else idleColor))
        stepNum2.backgroundTintList = if (captureGranted) readyGreen else idleSlate
        stepBtn2.text = if (captureGranted) "Разрешено" else "Разрешить"
        stepBtn2.isEnabled = !captureGranted

        startBtn.text = if (running) "Остановить оверлей" else "Запустить оверлей"
        startBtn.isEnabled = ready

        dot.backgroundTintList = if (ready) readyGreen else idleSlate
        footer.text = if (ready) {
            "Готово: запустите поверх страницы и нажмите «Рамка»."
        } else {
            "Сначала выдайте два разрешения."
        }
    }

    private fun showScreen(which: String) {
        val frame = findViewById<View>(R.id.screenFrame)
        val site = findViewById<View>(R.id.screenSite)
        val tabFrame = findViewById<android.widget.Button>(R.id.tabFrame)
        val tabSite = findViewById<android.widget.Button>(R.id.tabSite)
        val on = "#EAF2FF"
        val off = "#8EA6C8"
        if (which == "site") {
            frame.visibility = View.GONE
            site.visibility = View.VISIBLE
            tabSite.setTextColor(android.graphics.Color.parseColor(on))
            tabFrame.setTextColor(android.graphics.Color.parseColor(off))
        } else {
            site.visibility = View.GONE
            frame.visibility = View.VISIBLE
            tabFrame.setTextColor(android.graphics.Color.parseColor(on))
            tabSite.setTextColor(android.graphics.Color.parseColor(off))
        }
    }

    override fun onBackPressed() {
        if (siteWeb.visibility == View.VISIBLE && siteWeb.canGoBack()) {
            siteWeb.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun openUrl(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        val url = when {
            q.startsWith("http://") || q.startsWith("https://") || q.startsWith("file://") -> q
            q.split(' ').size > 1 -> "https://www.google.com/search?q=${Uri.encode(q)}"
            Regex("^[\\p{L}\\d-]+\\.[\\p{L}]{2,}([/:#?].*)?$").matches(q) -> "https://$q"
            else -> "https://www.google.com/search?q=${Uri.encode(q)}"
        }
        urlField.setText(url)
        siteWeb.loadUrl(url)
    }

    override fun onResume() {
        super.onResume()
        updateFrameUi()
    }
}