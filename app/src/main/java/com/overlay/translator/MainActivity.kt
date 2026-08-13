package com.overlay.translator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.overlay.translator.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var binding: ActivityMainBinding
    private var tts: TextToSpeech? = null
    private var voiceNames = listOf<String>()

    private val notifyPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            OverlayService.projectionResultCode = res.resultCode
            OverlayService.projectionData = res.data
            binding.status.text = "Захват экрана разрешён"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tts = TextToSpeech(this, this)
        if (Build.VERSION.SDK_INT >= 33) notifyPerm.launch(Manifest.permission.POST_NOTIFICATIONS)

        binding.btnOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else Toast.makeText(this, "Оверлей уже есть", Toast.LENGTH_SHORT).show()
        }
        binding.btnCapture.setOnClickListener {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            capture.launch(mpm.createScreenCaptureIntent())
        }
        binding.btnStart.setOnClickListener { startOverlay() }
        binding.btnStop.setOnClickListener {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        }
    }

    private fun voiceKind(): VoiceKind = when {
        binding.vMale.isChecked -> VoiceKind.MALE
        binding.vTeen.isChecked -> VoiceKind.TEEN
        binding.vOther.isChecked -> VoiceKind.OTHER
        else -> VoiceKind.FEMALE
    }

    private fun trMode(): Translator.Mode = when {
        binding.trOnline.isChecked -> Translator.Mode.ONLINE
        binding.trDict.isChecked -> Translator.Mode.DICT
        else -> Translator.Mode.AUTO
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Сначала оверлей", Toast.LENGTH_LONG).show()
            return
        }
        if (OverlayService.projectionData == null) {
            Toast.makeText(this, "Сначала захват экрана", Toast.LENGTH_LONG).show()
            return
        }
        val exact = voiceNames.getOrNull(binding.voiceExact.selectedItemPosition)
        val i = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            putExtra(OverlayService.EXTRA_EN, binding.modeEn.isChecked)
            putExtra(OverlayService.EXTRA_LIVE, binding.liveMode.isChecked)
            putExtra(OverlayService.EXTRA_SPEAK, binding.speakMode.isChecked)
            putExtra(OverlayService.EXTRA_VOICE, voiceKind().name)
            putExtra(OverlayService.EXTRA_VOICE_NAME, exact)
            putExtra(OverlayService.EXTRA_TR, trMode().name)
        }
        startForegroundService(i)
        moveTaskToBack(true)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts?.language = Locale("ru", "RU")
        refreshVoices()
        binding.voiceGroup.setOnCheckedChangeListener { _, _ -> refreshVoices() }
    }

    private fun refreshVoices() {
        val ru = VoiceHelper.russianVoices(tts)
        val kind = voiceKind()
        val filtered = ru.filter { VoiceHelper.classify(it) == kind }.ifEmpty { ru }
        voiceNames = filtered.map { it.name }
        val labels = filtered.map {
            val g = when (VoiceHelper.classify(it)) {
                VoiceKind.MALE -> "♂ "
                VoiceKind.FEMALE -> "♀ "
                VoiceKind.TEEN -> "🧒 "
                else -> "• "
            }
            g + it.name.substringAfterLast(":")
        }
        binding.voiceExact.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels.ifEmpty { listOf("Системный ru (установите Google TTS / RHVoice)") }
        )
        binding.status.text = if (ru.isEmpty())
            "Русских голосов нет. Установите Google TTS (русский) или RHVoice."
        else "Найдено русских голосов: ${ru.size}"
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}
