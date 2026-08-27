package com.overlay.translator

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
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
