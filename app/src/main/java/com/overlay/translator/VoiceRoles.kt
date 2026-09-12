package com.overlay.translator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class RoleGender(val json: String) {
    AUTO("auto"), MALE("male"), FEMALE("female"), NEUTRAL("neutral");

    fun toKind(): VoiceKind? = when (this) {
        MALE -> VoiceKind.MALE
        FEMALE -> VoiceKind.FEMALE
        else -> null
    }

    companion object {
        fun from(s: String?): RoleGender = entries.firstOrNull { it.json == s } ?: AUTO
    }
}

/**
 * Роль озвучки, привязанная к конкретному голосу.
 * [voiceKey] — ключ из [VoiceCatalog]: `sys:<имя>` (системный TTS-голос),
 * `edge:<ShortName>` (голос Edge TTS) или пустая строка (автоподбор).
 */
data class VoiceRole(
    val id: String,
    val name: String,
    var gender: RoleGender,
    var voiceKey: String,
    val pitch: Float,
    val rate: Float,
    val markers: List<String>,
)

object VoiceRoles {
    private const val PREFS = "voice_roles"
    private const val KEY = "voice_role_dict"

    val presets: List<VoiceRole> = listOf(
        VoiceRole("narrator", "Рассказчик", RoleGender.NEUTRAL, "", 1.0f, 0.96f, emptyList()),
        VoiceRole("male", "Мужская реплика", RoleGender.MALE, "", 1.0f, 0.96f, emptyList()),
        VoiceRole("female", "Женская реплика", RoleGender.FEMALE, "", 1.0f, 0.96f, emptyList()),
        VoiceRole("child", "Ребёнок", RoleGender.AUTO, "", 1.16f, 1.0f, emptyList()),
    )

    fun load(ctx: Context): List<VoiceRole> {
        val raw = try {
            ctx.getSharedPreferences(PREFS, 0).getString(KEY, null)
        } catch (e: Exception) {
            null
        } ?: return presets
        return runCatching {
            val arr = JSONArray(raw)
            val roles = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    fromJson(o)?.let { add(it) }
                }
            }
            if (roles.isEmpty()) presets else roles
        }.getOrElse { presets }
    }

    fun save(ctx: Context, roles: List<VoiceRole>) {
        val arr = JSONArray()
        roles.forEach { arr.put(toJson(it)) }
        runCatching {
            ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY, arr.toString()).apply()
        }.onFailure { android.util.Log.w("VoiceRoles", "save failed", it) }
    }

    private fun toJson(r: VoiceRole): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("name", r.name)
        put("gender", r.gender.json)
        put("voice", r.voiceKey)
        put("pitch", r.pitch.toDouble())
        put("rate", r.rate.toDouble())
        put("markers", JSONArray(r.markers))
    }

    private fun fromJson(o: JSONObject): VoiceRole? {
        val id = o.optString("id")
        if (id.isBlank()) return null
        return VoiceRole(
            id = id,
            name = o.optString("name", id),
            gender = RoleGender.from(o.optString("gender")),
            voiceKey = o.optString("voice", ""),
            pitch = o.optDouble("pitch", 1.0).toFloat(),
            rate = o.optDouble("rate", 0.96).toFloat(),
            markers = buildList {
                val m = o.optJSONArray("markers")
                if (m != null) for (i in 0 until m.length()) m.optString(i)?.takeIf { it.isNotBlank() }?.let { add(it) }
            },
        )
    }

    /**
     * Выбор роли для текста: сначала явный маркер (имя персонажа, встречающееся
     * в тексте), иначе роль по полу, определённому [VoiceAssistant].
     */
    fun resolve(text: String, kind: VoiceKind, roles: List<VoiceRole>): VoiceRole {
        val lower = text.lowercase()
        roles.firstOrNull { role ->
            role.markers.isNotEmpty() && role.markers.any { lower.contains(it.lowercase()) }
        }?.let { return it }
        return when (kind) {
            VoiceKind.FEMALE -> roles.firstOrNull { it.id == "female" }
            VoiceKind.MALE -> roles.firstOrNull { it.id == "male" }
            VoiceKind.TEEN -> roles.firstOrNull { it.id == "child" }
            else -> roles.firstOrNull { it.id == "narrator" }
        } ?: roles.firstOrNull { it.id == "narrator" } ?: roles.firstOrNull()
        ?: presets.first()
    }

    fun voiceLabel(role: VoiceRole, systemNames: Set<String>, edgeNames: Set<String>): String {
        if (role.voiceKey.isBlank()) return "Авто (подбор по тексту)"
        return when {
            role.voiceKey.startsWith("edge:") -> {
                val short = role.voiceKey.removePrefix("edge:")
                "Edge: $short"
            }
            role.voiceKey.startsWith("sys:") -> {
                val name = role.voiceKey.removePrefix("sys:")
                if (name in systemNames) name else "$name (не установлен)"
            }
            else -> role.voiceKey
        }
    }
}