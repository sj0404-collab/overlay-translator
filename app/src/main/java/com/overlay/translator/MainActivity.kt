package com.overlay.translator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.overlay.translator.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val notifyPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    private val capture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            OverlayService.projectionResultCode = res.resultCode
            OverlayService.projectionData = res.data
            binding.status.text = "Статус: захват экрана разрешён"
        } else {
            binding.status.text = "Статус: захват экрана отклонён"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val names = Langs.all.map { it.label }
        binding.srcLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        binding.dstLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        binding.srcLang.setSelection(0)
        binding.dstLang.setSelection(1)

        if (Build.VERSION.SDK_INT >= 33) {
            notifyPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.btnOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                Toast.makeText(this, "Оверлей уже разрешён", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCapture.setOnClickListener {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            capture.launch(mpm.createScreenCaptureIntent())
        }

        binding.btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Сначала разрешите оверлей", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (OverlayService.projectionData == null) {
                Toast.makeText(this, "Сначала разрешите захват экрана", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val i = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START
                putExtra(OverlayService.EXTRA_SRC, Langs.all[binding.srcLang.selectedItemPosition].mlkit)
                putExtra(OverlayService.EXTRA_DST, Langs.all[binding.dstLang.selectedItemPosition].mlkit)
                putExtra(OverlayService.EXTRA_LIVE, binding.liveMode.isChecked)
                putExtra(OverlayService.EXTRA_SPEAK, binding.speakMode.isChecked)
            }
            startForegroundService(i)
            binding.status.text = "Статус: оверлей запущен. Выберите область."
            moveTaskToBack(true)
        }

        binding.btnStop.setOnClickListener {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            binding.status.text = "Статус: остановлено"
        }
    }

    override fun onResume() {
        super.onResume()
        val ov = Settings.canDrawOverlays(this)
        val cap = OverlayService.projectionData != null
        binding.status.text = "Статус: overlay=${if (ov) "OK" else "нет"}, capture=${if (cap) "OK" else "нет"}"
    }
}
