package com.overlay.translator

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * APK shell for the TSX user interface. The WebView is intentionally limited
 * to packaged local assets; Android-only operations remain explicit bridge
 * calls that the user initiates from the TSX screen.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var siteWeb: WebView
    private lateinit var urlField: android.widget.EditText

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            OverlayService.projectionResultCode = result.resultCode
            OverlayService.projectionData = result.data
        }
        publishNativeState()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        web = findViewById(R.id.tsxWeb)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = false
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = false
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(OverlayNativeBridge(), "OverlayNative")
        web.loadUrl("file:///android_asset/tsx/index.html")

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
        publishNativeState()
    }

    private fun stateJson(): String = JSONObject().apply {
        put("overlay", Settings.canDrawOverlays(this@MainActivity))
        put("capture", OverlayService.projectionData != null)
        put("running", OverlayService.isRunning)
    }.toString()

    private fun publishNativeState() {
        if (!::web.isInitialized) return
        val state = JSONObject.quote(stateJson())
        web.post { web.evaluateJavascript("window.onOverlayNativeState?.($state)", null) }
    }

    private inner class OverlayNativeBridge {
        @JavascriptInterface
        fun state(): String = stateJson()

        @JavascriptInterface
        fun requestOverlay() {
            runOnUiThread {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
        }

        @JavascriptInterface
        fun requestCapture() {
            runOnUiThread {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                capture.launch(manager.createScreenCaptureIntent())
            }
        }

        @JavascriptInterface
        fun startOverlay() {
            runOnUiThread {
                if (!Settings.canDrawOverlays(this@MainActivity) || OverlayService.projectionData == null) {
                    publishNativeState()
                    return@runOnUiThread
                }
                startForegroundService(Intent(this@MainActivity, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_START
                })
                publishNativeState()
                moveTaskToBack(true)
            }
        }

        @JavascriptInterface
        fun stopOverlay() {
            runOnUiThread {
                startService(Intent(this@MainActivity, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
                publishNativeState()
            }
        }
    }
}
