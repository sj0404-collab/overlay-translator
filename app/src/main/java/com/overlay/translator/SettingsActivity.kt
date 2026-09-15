package com.overlay.translator

import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Native settings screen: engines, connectivity mode and scan behavior. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var spMode: Spinner
    private lateinit var modeDesc: TextView
    private lateinit var spOcr: Spinner
    private lateinit var ocrDesc: TextView
    private lateinit var spTr: Spinner
    private lateinit var trDesc: TextView
    private lateinit var spTts: Spinner
    private lateinit var spScanLang: Spinner
    private lateinit var chLive: CheckBox
    private lateinit var chAutoTranslate: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)

        spMode = findViewById(R.id.spMode)
        modeDesc = findViewById(R.id.modeDesc)
        spOcr = findViewById(R.id.spOcr)
        ocrDesc = findViewById(R.id.ocrDesc)
        spTr = findViewById(R.id.spTr)
        trDesc = findViewById(R.id.trDesc)
        spTts = findViewById(R.id.spTts)
        spScanLang = findViewById(R.id.spScanLang)
        chLive = findViewById(R.id.chLive)
        chAutoTranslate = findViewById(R.id.chAutoTranslate)

        bindMode()
        bindOcr()
        bindTr()
        bindTts()
        bindScanLang()

        chLive.isChecked = EnginePrefs.live(this)
        chLive.setOnCheckedChangeListener { _, v -> EnginePrefs.setLive(this, v) }
        chAutoTranslate.isChecked = EnginePrefs.autoTranslate(this)
        chAutoTranslate.setOnCheckedChangeListener { _, v -> EnginePrefs.setAutoTranslate(this, v) }

        val googleKey = findViewById<EditText>(R.id.inputGoogleKey)
        val googleModel = findViewById<EditText>(R.id.inputGoogleModel)
        val orKey = findViewById<EditText>(R.id.inputOpenrouterKey)
        val orModel = findViewById<EditText>(R.id.inputOrModel)
        val zenModel = findViewById<EditText>(R.id.inputZenModel)

        googleKey.setText(EnginePrefs.googleApiKey(this))
        googleKey.setOnFocusChangeListener { _, has -> if (!has) EnginePrefs.setGoogleApiKey(this, googleKey.text.toString()) }
        googleModel.setText(EnginePrefs.googleModel(this))
        googleModel.setOnFocusChangeListener { _, has -> if (!has) EnginePrefs.setGoogleModel(this, googleModel.text.toString()) }
        orKey.setText(EnginePrefs.openrouterKey(this))
        orKey.setOnFocusChangeListener { _, has -> if (!has) EnginePrefs.setOpenrouterKey(this, orKey.text.toString()) }
        orModel.setText(EnginePrefs.orModel(this))
        orModel.setOnFocusChangeListener { _, has -> if (!has) EnginePrefs.setOrModel(this, orModel.text.toString()) }
        zenModel.setText(EnginePrefs.zenModel(this))
        zenModel.setOnFocusChangeListener { _, has -> if (!has) EnginePrefs.setZenModel(this, zenModel.text.toString()) }

        refreshTokens()
        findViewById<Button>(R.id.btnTokenReset).setOnClickListener {
            EnginePrefs.setTokenUsageCount(this, 0L)
            refreshTokens()
        }
        applyModeEnabled()
    }

    override fun onResume() {
        super.onResume()
        refreshTokens()
    }

    private fun refreshTokens() {
        findViewById<TextView>(R.id.tokenUsage).text =
            "Токенов израсходовано онлайн: ${EnginePrefs.tokenUsageCount(this)}"
    }

    private fun bindMode() {
        val values = arrayOf(
            EnginePrefs.MODE_OFFLINE to "Оффлайн",
            EnginePrefs.MODE_MIXED to "Смешанный",
            EnginePrefs.MODE_COMBO to "Комбинированный",
            EnginePrefs.MODE_ONLINE to "Полностью онлайн",
        )
        val selected = values.indexOfFirst { it.first == EnginePrefs.netMode(this) }.coerceAtLeast(0)
        spMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values.map { it.second })
        spMode.setSelection(selected)
        spMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val key = values[position].first
                if (EnginePrefs.netMode(this@SettingsActivity) != key) {
                    EnginePrefs.setNetMode(this@SettingsActivity, key)
                    applyModeEnabled()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun applyModeEnabled() {
        val mode = EnginePrefs.netMode(this)
        modeDesc.text = when (mode) {
            EnginePrefs.MODE_OFFLINE -> "Только устройство: локальный PP-OCR, локальный словарь, системный голос. Никаких сетевых запросов."
            EnginePrefs.MODE_MIXED -> "Локальный OCR по умолчанию, перевод и голос по выбору (онлайн допустимо)."
            EnginePrefs.MODE_COMBO -> "Локальный PP-OCR первым, при пустом результате — бесплатный онлайн-проход."
            else -> "Максимум сети: OCR, перевод и голос по выбранным онлайн-движкам."
        }
        val offline = mode == EnginePrefs.MODE_OFFLINE
        spOcr.isEnabled = !offline
        spTr.isEnabled = !offline
        spTts.isEnabled = !offline
    }

    private fun bindOcr() {
        val values = arrayOf(
            EnginePrefs.OCR_LOCAL to "Локальный PP-OCR (cyrillica)",
            EnginePrefs.OCR_GLENS to "Glens (Google, бесплатно)",
            EnginePrefs.OCR_ZEN to "Zen Free",
            EnginePrefs.OCR_GOOGLE_AI to "Google AI / Gemini (нужен ключ)",
            EnginePrefs.OCR_OPENROUTER to "OpenRouter (нужен ключ)",
        )
        val selected = values.indexOfFirst { it.first == EnginePrefs.ocr(this) }.coerceAtLeast(0)
        spOcr.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values.map { it.second })
        spOcr.setSelection(selected)
        spOcr.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val key = values[position].first
                if (EnginePrefs.ocr(this@SettingsActivity) != key) EnginePrefs.setOcr(this@SettingsActivity, key)
                ocrDesc.text = when (key) {
                    EnginePrefs.OCR_GLENS -> "Бесплатный Google-движок, на устройстве не требуется ключ."
                    EnginePrefs.OCR_ZEN -> "Бесплатный онлайн-движок Zen Vision."
                    EnginePrefs.OCR_GOOGLE_AI -> "Gemini Vision через Google AI. Введите ключ ниже."
                    EnginePrefs.OCR_OPENROUTER -> "Любая визion-модель через OpenRouter. Введите ключ и модель."
                    else -> "Встроенные Cyrillic PP-OCR v3/v5; полностью локально."
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun bindTr() {
        val values = arrayOf(
            EnginePrefs.TR_DICT to "Локальный словарь",
            EnginePrefs.TR_GOOGLE to "Google Translate (бесплатно)",
            EnginePrefs.TR_GOOGLE_AI to "Gemini",
            EnginePrefs.TR_ZEN to "Zen Vision (бесплатно)",
            EnginePrefs.TR_OPENROUTER to "OpenRouter",
            EnginePrefs.TR_MYMEMORY to "MyMemory (бесплатно)",
            EnginePrefs.TR_AUTO to "Авто (Zen → Google → MyMemory)",
        )
        val selected = values.indexOfFirst { it.first == EnginePrefs.tr(this) }.coerceAtLeast(0)
        spTr.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values.map { it.second })
        spTr.setSelection(selected)
        spTr.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val key = values[position].first
                if (EnginePrefs.tr(this@SettingsActivity) != key) EnginePrefs.setTr(this@SettingsActivity, key)
                trDesc.text = when (key) {
                    EnginePrefs.TR_GOOGLE -> "Бесплатный web-переводчик Google."
                    EnginePrefs.TR_GOOGLE_AI -> "Перевод через Gemini. Нужен ключ Google AI."
                    EnginePrefs.TR_ZEN -> "Бесплатный LLM-перевод Zen Vision."
                    EnginePrefs.TR_OPENROUTER -> "Перевод через любую модель OpenRouter. Нужен ключ и модель."
                    EnginePrefs.TR_MYMEMORY -> "Бесплатный онлайн-перевод MyMemory."
                    EnginePrefs.TR_AUTO -> "Пробует Zen, затем Google Free, затем MyMemory."
                    else -> "Офлайн-словарь EN→RU, вшит в APK."
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun bindTts() {
        val values = arrayOf(
            EnginePrefs.TTS_AUTO to "Авто (по ролям)",
            EnginePrefs.TTS_SYSTEM to "Системный голос",
            EnginePrefs.TTS_EDGE to "Edge TTS (сеть)",
        )
        val selected = values.indexOfFirst { it.first == EnginePrefs.ttsSource(this) }.coerceAtLeast(0)
        spTts.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values.map { it.second })
        spTts.setSelection(selected)
        spTts.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val key = values[position].first
                if (EnginePrefs.ttsSource(this@SettingsActivity) != key) EnginePrefs.setTtsSource(this@SettingsActivity, key)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun bindScanLang() {
        val values = arrayOf("AUTO" to "Авто (RU + EN)", "RU" to "Русский", "EN" to "Английский")
        val selected = values.indexOfFirst { it.first == EnginePrefs.scanLang(this) }.coerceAtLeast(0)
        spScanLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values.map { it.second })
        spScanLang.setSelection(selected)
        spScanLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val key = values[position].first
                if (EnginePrefs.scanLang(this@SettingsActivity) != key) EnginePrefs.setScanLang(this@SettingsActivity, key)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
}