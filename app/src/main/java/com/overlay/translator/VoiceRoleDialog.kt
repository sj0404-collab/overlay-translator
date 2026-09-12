package com.overlay.translator

import android.content.Context
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.runBlocking

/**
 * Диалог голосовых ролей: каждая роль (рассказчик, мужская/женская реплика,
 * ребёнок) привязывается к конкретному голосу — системному TTS или голосу
 * Edge TTS. Вложенный пикер показывает полный каталог голосов.
 */
object VoiceRoleDialog {
    private const val TAG = "VoiceRoleDialog"
    @Volatile private var token = 0

    fun show(ctx: Context, wm: WindowManager, mainHandler: Handler, tts: TextToSpeech?) {
        token++
        val roles = VoiceRoles.load(ctx).toMutableList()
        render(ctx, wm, mainHandler, tts, roles)
    }

    private fun render(
        ctx: Context,
        wm: WindowManager,
        mainHandler: Handler,
        tts: TextToSpeech?,
        roles: MutableList<VoiceRole>,
    ) {
        val systemNames = VoiceHelper.russianVoices(tts).map { it.name }.toSet()
        val edgeCache = EdgeTts.cachedVoices(ctx)
        val edgeNames = edgeCache.map { it.shortName }.toSet()

        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        list.addView(DialogOverlay.title(ctx, "🎭 Роли и голоса"))
        list.addView(DialogOverlay.item(ctx, "Каждая роль озвучивается своим голосом. Нажмите на роль, чтобы выбрать голос (системный TTS или Edge TTS).", 10))
        list.addView(DialogOverlay.divider(ctx))

        roles.forEach { role ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(4, 6, 4, 6)
                setBackgroundColor(0xFF16243A.toInt())
            }
            val genderLabel = when (role.gender) {
                RoleGender.AUTO -> "пол: авто"
                RoleGender.MALE -> "пол: муж"
                RoleGender.FEMALE -> "пол: жен"
                RoleGender.NEUTRAL -> "пол: нейтр"
            }
            val label = TextView(ctx).apply {
                text = "${role.name} — ${VoiceRoles.voiceLabel(role, systemNames, edgeNames)}"
                setTextColor(0xFFE2E8F0.toInt())
                textSize = 14f
                setPadding(6, 4, 6, 4)
                isClickable = true
                setOnClickListener { pickVoice(ctx, wm, mainHandler, tts, role, roles) }
            }
            val gender = Button(ctx).apply {
                text = genderLabel
                textSize = 11f
                setOnClickListener {
                    role.gender = when (role.gender) {
                        RoleGender.AUTO -> RoleGender.NEUTRAL
                        RoleGender.NEUTRAL -> RoleGender.MALE
                        RoleGender.MALE -> RoleGender.FEMALE
                        RoleGender.FEMALE -> RoleGender.AUTO
                    }
                    show(ctx, wm, mainHandler, tts)
                }
            }
            val reset = Button(ctx).apply {
                text = "↺ авто"
                textSize = 11f
                setOnClickListener {
                    role.voiceKey = ""
                    show(ctx, wm, mainHandler, tts)
                }
            }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(gender)
            row.addView(reset)
            list.addView(row)
        }

        list.addView(DialogOverlay.divider(ctx))
        list.addView(DialogOverlay.item(
            ctx,
            if (edgeCache.isEmpty()) "Edge-голоса ещё не получены — подгрузятся при выборе голоса (нужен интернет)."
            else "Edge-голоса: ${edgeCache.size} в кэше.",
            12,
        ))

        val close = Button(ctx).apply {
            text = "✓ Готово"
            setOnClickListener {
                VoiceRoles.save(ctx, roles)
                DialogOverlay.dismiss()
                token++
            }
        }
        list.addView(close)

        DialogOverlay.show(ctx, wm, ScrollView(ctx).apply { addView(list) }, 720)
    }

    private fun pickVoice(
        ctx: Context,
        wm: WindowManager,
        mainHandler: Handler,
        tts: TextToSpeech?,
        role: VoiceRole,
        roles: MutableList<VoiceRole>,
    ) {
        token++
        val myToken = token
        DialogOverlay.dismiss()

        val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        rows.addView(DialogOverlay.title(ctx, "Голос для роли «${role.name}»"))
        rows.addView(DialogOverlay.divider(ctx))

        val auto = DialogOverlay.item(ctx, if (role.voiceKey.isBlank()) "● Авто (подбор по тексту)" else "○ Авто (подбор по тексту)")
        auto.setOnClickListener { role.voiceKey = ""; saveAndBack(ctx, wm, mainHandler, tts, roles) }
        rows.addView(auto)

        VoiceHelper.russianVoices(tts).forEach { v ->
            val tv = DialogOverlay.item(ctx, "${if ("sys:${v.name}" == role.voiceKey) "●" else "○"} ${v.name} · ${v.locale.toLanguageTag()}")
            tv.setOnClickListener { role.voiceKey = "sys:${v.name}"; saveAndBack(ctx, wm, mainHandler, tts, roles) }
            rows.addView(tv)
        }

        val edgeRows = EdgeTts.cachedVoices(ctx)
            .filter { it.locale.startsWith("ru-", true) || it.multilingual }
            .sortedWith(compareBy({ it.label.contains("Multilingual", true) }, { it.label }))
        if (edgeRows.isEmpty()) {
            val refresh = DialogOverlay.item(ctx, "⟳ Edge TTS: загрузить голоса (нужен интернет)")
            refresh.setOnClickListener { fetchEdge(ctx, wm, mainHandler, tts, role, roles) }
            rows.addView(refresh)
        } else {
            rows.addView(DialogOverlay.divider(ctx))
            edgeRows.forEach { v ->
                val tv = DialogOverlay.item(ctx, "${if ("edge:${v.shortName}" == role.voiceKey) "●" else "○"} ${v.label}")
                tv.setOnClickListener { role.voiceKey = "edge:${v.shortName}"; saveAndBack(ctx, wm, mainHandler, tts, roles) }
                rows.addView(tv)
            }
        }

        val cancel = Button(ctx).apply {
            text = "Отмена"
            setOnClickListener { show(ctx, wm, mainHandler, tts) }
        }
        rows.addView(cancel)

        DialogOverlay.show(ctx, wm, ScrollView(ctx).apply { addView(rows) }, 760)
    }

    private fun saveAndBack(
        ctx: Context,
        wm: WindowManager,
        mainHandler: Handler,
        tts: TextToSpeech?,
        roles: MutableList<VoiceRole>,
    ) {
        VoiceRoles.save(ctx, roles)
        show(ctx, wm, mainHandler, tts)
    }

    private fun fetchEdge(
        ctx: Context,
        wm: WindowManager,
        mainHandler: Handler,
        tts: TextToSpeech?,
        role: VoiceRole,
        roles: MutableList<VoiceRole>,
    ) {
        val myToken = token
        DialogOverlay.dismiss()
        val info = TextView(ctx).apply {
            text = "⟳ Загружаю голоса Edge TTS…"
            setTextColor(0xFFE2E8F0.toInt())
            textSize = 15f
            setPadding(20, 20, 20, 20)
        }
        DialogOverlay.show(ctx, wm, info)
        Thread {
            val fetched = runBlocking { EdgeTts.fetchVoices() }
            EdgeTts.cacheVoices(ctx, fetched)
            mainHandler.post {
                if (myToken != token) return@post
                show(ctx, wm, mainHandler, tts)
            }
        }.start()
    }
}