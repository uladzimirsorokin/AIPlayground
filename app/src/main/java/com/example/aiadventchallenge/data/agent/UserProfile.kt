package com.example.aiadventchallenge.data.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Профиль пользователя (День 12) — явные предпочтения поверх модели памяти:
 * стиль общения, формат ответов, ограничения. Подмешивается в каждый запрос
 * системным сообщением «Профиль пользователя: …», чтобы ассистент адаптировался.
 */
data class UserProfile(
    val name: String = "",
    val style: String = "",
    val format: String = "",
    val constraints: String = "",
    val extra: String = ""
) {
    val isBlank: Boolean
        get() = name.isBlank() && style.isBlank() && format.isBlank() &&
            constraints.isBlank() && extra.isBlank()

    /** Блок, который реально уходит моделью как системное сообщение. */
    val text: String
        get() {
            if (isBlank) return ""
            return buildString {
                append("Профиль пользователя:")
                name.takeIf { it.isNotBlank() }?.let { append("\n- Имя/роль: ").append(it) }
                style.takeIf { it.isNotBlank() }?.let { append("\n- Стиль общения: ").append(it) }
                format.takeIf { it.isNotBlank() }?.let { append("\n- Формат ответов: ").append(it) }
                constraints.takeIf { it.isNotBlank() }?.let { append("\n- Ограничения: ").append(it) }
                extra.takeIf { it.isNotBlank() }?.let { append("\n- Дополнительно: ").append(it) }
            }
        }
}

/** Сохранённый профиль в библиотеке (label — имя для выбора, profile — содержимое). */
data class SavedProfile(
    val id: String,
    val label: String,
    val profile: UserProfile
)

/** Библиотека профилей: список + какой профиль активен. */
interface ProfileStore {
    fun loadProfiles(): List<SavedProfile>
    fun saveProfiles(profiles: List<SavedProfile>)
    fun loadActiveId(): String?
    fun saveActiveId(id: String?)
}

/** SharedPreferences-реализация (файл agent_profile, JSON-массив профилей). */
class PrefsProfileStore(context: Context) : ProfileStore {

    private val prefs = context.getSharedPreferences("agent_profile", Context.MODE_PRIVATE)

    override fun loadProfiles(): List<SavedProfile> {
        val raw = prefs.getString("profiles", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val p = obj.getJSONObject("profile")
                    add(
                        SavedProfile(
                            id = obj.getString("id"),
                            label = obj.getString("label"),
                            profile = UserProfile(
                                name = p.optString("name", ""),
                                style = p.optString("style", ""),
                                format = p.optString("format", ""),
                                constraints = p.optString("constraints", ""),
                                extra = p.optString("extra", "")
                            )
                        )
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun saveProfiles(profiles: List<SavedProfile>) {
        val array = JSONArray()
        profiles.forEach { sp ->
            val p = sp.profile
            array.put(
                JSONObject()
                    .put("id", sp.id)
                    .put("label", sp.label)
                    .put(
                        "profile",
                        JSONObject()
                            .put("name", p.name)
                            .put("style", p.style)
                            .put("format", p.format)
                            .put("constraints", p.constraints)
                            .put("extra", p.extra)
                    )
            )
        }
        prefs.edit().putString("profiles", array.toString()).apply()
    }

    override fun loadActiveId(): String? = prefs.getString("active_id", null)

    override fun saveActiveId(id: String?) {
        prefs.edit().apply {
            if (id == null) remove("active_id") else putString("active_id", id)
        }.apply()
    }
}