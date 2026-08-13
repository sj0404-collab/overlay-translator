package com.overlay.translator

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

class ProjectionRequestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 7)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == RESULT_OK && data != null) {
            OverlayService.projectionResultCode = resultCode
            OverlayService.projectionData = data
            startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_REBIND))
        }
        finish()
    }
}
