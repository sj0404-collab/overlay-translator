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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.overlay.translator.databinding.ActivityMainBinding
import com.overlay.translator.databinding.TabOcrBinding
import com.overlay.translator.databinding.TabOverlayBinding
import com.overlay.translator.databinding.TabTranslateBinding
import com.overlay.translator.databinding.TabVoicesBinding
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var binding: ActivityMainBinding
    private var overlayTab: TabOverlayBinding? = null
    private var ocrTab: TabOcrBinding? = null
    private var trTab: TabTranslateBinding? = null
    private var voiceTab: TabVoicesBinding? = null
    private var tts: TextToSpeech? = null
    private var voiceNames = listOf<String>()

    private val notifyPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            OverlayService.projectionResultCode = res.resultCode
            OverlayService.projectionData = res.data
            overlayTab?.status?.text = "Захват экрана разрешён"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tts = TextToSpeech(this, this)
        if (Build.VERSION.SDK_INT >= 33) notifyPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        binding.pager.adapter = Tabs()
        TabLayoutMediator(binding.tabs, binding.pager) { tab, pos ->
            tab.text = listOf("Оверлей", "OCR", "Перевод", "Голоса")[pos]
        }.attach()
    }

    private inner class Tabs : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = 4
        override fun getItemViewType(position: Int) = position
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val inf = LayoutInflater.from(parent.context)
            val v = when (viewType) {
                0 -> TabOverlayBinding.inflate(inf, parent, false).also { overlayTab = it; wireOverlay(it) }.root
                1 -> TabOcrBinding.inflate(inf, parent, false).also { ocrTab = it; wireOcr(it) }.root
                2 -> TabTranslateBinding.inflate(inf, parent, false).also { trTab = it; wireTr(it) }.root
                else -> TabVoicesBinding.inflate(inf, parent, false).also { voiceTab = it; wireVoices(it) }.root
            }
            return VH(v)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {}
    }

    class VH(v: View) : RecyclerView.ViewHolder(v)

    private fun wireOverlay(b: TabOverlayBinding) {
        b.liveMode.isChecked = EnginePrefs.live(this)
        b.speakMode.isChecked = EnginePrefs.speak(this)
        b.liveMode.setOnCheckedChangeListener { _, v -> EnginePrefs.setLive(this, v) }
        b.speakMode.setOnCheckedChangeListener { _, v -> EnginePrefs.setSpeak(this, v) }
        b.btnOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else Toast.makeText(this, "Оверлей уже есть", Toast.LENGTH_SHORT).show()
        }
        b.btnCapture.setOnClickListener {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            capture.launch(mpm.createScreenCaptureIntent())
        }
        b.btnStart.setOnClickListener { startOverlay() }
        b.btnStop.setOnClickListener {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        }
    }

    private fun wireOcr(b: TabOcrBinding) {
        // Block temporarily so setChecked() doesn't fire onCheckedChange while restoring.
        b.ocrGroup.setOnCheckedChangeListener(null)
        b.langGroup.setOnCheckedChangeListener(null)
        b.scanModeGroup.setOnCheckedChangeListener(null)
        b.regionModeGroup.setOnCheckedChangeListener(null)

        when (EnginePrefs.ocr(this)) {
            "openrouter" -> b.ocrOr.isChecked = true
            "glens" -> b.ocrGlens.isChecked = true
            "google" -> b.ocrGoogle.isChecked = true
            "yolo" -> b.ocrYolo.isChecked = true
            "mlkit" -> b.ocrMlkit.isChecked = true
            "tess" -> b.ocrTess.isChecked = true
            "local" -> b.ocrLocal.isChecked = true
            else -> b.ocrZen.isChecked = true
        }
        when (EnginePrefs.scanLang(this)) {
            "EN" -> b.langEn.isChecked = true
            "AUTO" -> b.langAuto.isChecked = true
            else -> b.langRu.isChecked = true
        }
        when (EnginePrefs.scanMode(this)) {
            "bubble" -> b.scanBubble.isChecked = true
            "full" -> b.scanFull.isChecked = true
            else -> b.scanRect.isChecked = true
        }
        when (EnginePrefs.regionMode(this)) {
            "wide" -> b.regWide.isChecked = true
            "screen" -> b.regScreen.isChecked = true
            else -> b.regRect.isChecked = true
        }

        // Google AI model + key
        b.googleModel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, LlmClient.GEMINI_FREE)
        b.googleModel.setSelection(LlmClient.GEMINI_FREE.indexOf(EnginePrefs.googleModel(this)).coerceAtLeast(0))
        b.googleModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                EnginePrefs.setGoogleModel(this@MainActivity, LlmClient.GEMINI_FREE[pos])
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        b.googleKey.setText(EnginePrefs.googleApiKey(this))
        b.googleKey.setOnFocusChangeListener { _, has ->
            if (!has) EnginePrefs.setGoogleApiKey(this, b.googleKey.text.toString().trim())
        }

        b.ocrGroup.setOnCheckedChangeListener { _, id ->
            EnginePrefs.setOcr(this, when (id) {
                b.ocrOr.id -> "openrouter"
                b.ocrGlens.id -> "glens"
                b.ocrGoogle.id -> "google"
                b.ocrYolo.id -> "yolo"
                b.ocrMlkit.id -> "mlkit"
                b.ocrTess.id -> "tess"
                b.ocrLocal.id -> "local"
                else -> "zen"
            })
        }
        b.langGroup.setOnCheckedChangeListener { _, id ->
            EnginePrefs.setScanLang(this, when (id) {
                b.langEn.id -> "EN"
                b.langAuto.id -> "AUTO"
                else -> "RU"
            })
        }
        b.scanModeGroup.setOnCheckedChangeListener { _, id ->
            EnginePrefs.setScanMode(this, when (id) {
                b.scanBubble.id -> "bubble"
                b.scanFull.id -> "full"
                else -> "rect"
            })
        }
        b.regionModeGroup.setOnCheckedChangeListener { _, id ->
            EnginePrefs.setRegionMode(this, when (id) {
                b.regWide.id -> "wide"
                b.regScreen.id -> "screen"
                else -> "rect"
            })
        }
    }

    private fun wireTr(b: TabTranslateBinding) {
        b.trGroup.setOnCheckedChangeListener(null)

        when (EnginePrefs.tr(this)) {
            "google" -> b.trGoogle.isChecked = true
            "openrouter" -> b.trOr.isChecked = true
            "local" -> b.trLocal.isChecked = true
            "mymemory" -> b.trMymemory.isChecked = true
            "auto" -> b.trAuto.isChecked = true
            "googleai" -> b.trGoogleAi.isChecked = true
            else -> b.trZen.isChecked = true
        }
        b.trGroup.setOnCheckedChangeListener { _, id ->
            EnginePrefs.setTr(this, when (id) {
                b.trGoogle.id -> "google"
                b.trGoogleAi.id -> "googleai"
                b.trOr.id -> "openrouter"
                b.trLocal.id -> "local"
                b.trMymemory.id -> "mymemory"
                b.trAuto.id -> "auto"
                else -> "zen"
            })
        }
        b.zenModel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, LlmClient.ZEN_FREE)
        b.zenModel.setSelection(LlmClient.ZEN_FREE.indexOf(EnginePrefs.zenModel(this)).coerceAtLeast(0))
        b.zenModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                EnginePrefs.setZenModel(this@MainActivity, LlmClient.ZEN_FREE[pos])
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        b.orModel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, LlmClient.OR_FREE)
        b.orModel.setSelection(LlmClient.OR_FREE.indexOf(EnginePrefs.orModel(this)).coerceAtLeast(0))
        b.orModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                EnginePrefs.setOrModel(this@MainActivity, LlmClient.OR_FREE[pos])
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        b.orKey.setText(EnginePrefs.openrouterKey(this))
        b.orKey.setOnFocusChangeListener { _, has ->
            if (!has) EnginePrefs.setOpenrouterKey(this, b.orKey.text.toString().trim())
        }
        b.googleKey.setText(EnginePrefs.googleApiKey(this))
        b.googleKey.setOnFocusChangeListener { _, has ->
            if (!has) EnginePrefs.setGoogleApiKey(this, b.googleKey.text.toString().trim())
        }
    }

    private fun wireVoices(b: TabVoicesBinding) {
        b.voiceGroup.setOnCheckedChangeListener { _, _ -> refreshVoices() }
        refreshVoices()
    }

    private fun voiceKind(): VoiceKind {
        val b = voiceTab ?: return VoiceKind.FEMALE
        return when {
            b.vMale.isChecked -> VoiceKind.MALE
            b.vTeen.isChecked -> VoiceKind.TEEN
            b.vOther.isChecked -> VoiceKind.OTHER
            else -> VoiceKind.FEMALE
        }
    }

    private fun startOverlay() {
        trTab?.orKey?.let { EnginePrefs.setOpenrouterKey(this, it.text.toString().trim()) }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Сначала оверлей", Toast.LENGTH_LONG).show(); return
        }
        if (OverlayService.projectionData == null) {
            Toast.makeText(this, "Сначала захват экрана", Toast.LENGTH_LONG).show(); return
        }
        val exact = voiceNames.getOrNull(voiceTab?.voiceExact?.selectedItemPosition ?: 0)
        startForegroundService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            putExtra(OverlayService.EXTRA_VOICE, voiceKind().name)
            putExtra(OverlayService.EXTRA_VOICE_NAME, exact)
        })
        moveTaskToBack(true)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts?.language = Locale("ru", "RU")
        refreshVoices()
    }

    private fun refreshVoices() {
        val b = voiceTab ?: return
        val ru = VoiceHelper.russianVoices(tts)
        val kind = voiceKind()
        val filtered = ru.filter { VoiceHelper.classify(it) == kind }.ifEmpty { ru }
        voiceNames = filtered.map { it.name }
        val labels = filtered.map { it.name.substringAfterLast(":") }
        b.voiceExact.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            labels.ifEmpty { listOf("Установите Google TTS / RHVoice") }
        )
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}
