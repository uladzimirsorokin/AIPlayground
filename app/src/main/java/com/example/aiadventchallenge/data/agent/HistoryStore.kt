package com.example.aiadventchallenge.data.agent

import android.content.Context
import com.example.aiadventchallenge.data.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Краткосрочная память (текущий диалог конкретного агента).
 * Хранит только сообщения; скоуп по id агента, чтобы у разных агентов
 * были свои независимые диалоги.
 */
interface HistoryStore {
    fun load(): List<ChatMessage>
    fun save(messages: List<ChatMessage>)
    fun clear()
}

/**
 * JSON-реализация в SharedPreferences (файл agent_history_<scope>),
 * так диалог переживает перезапуск приложения и изолирован по агенту.
 */
class JsonHistoryStore(context: Context, scope: String) : HistoryStore {

    private val prefs = context.getSharedPreferences("agent_history_$scope", Context.MODE_PRIVATE)

    override fun load(): List<ChatMessage> {
        val raw = prefs.getString("messages", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(ChatMessage(obj.getString("role"), obj.getString("content")))
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun save(messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.forEach { m ->
            array.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        prefs.edit().putString("messages", array.toString()).apply()
    }

    override fun clear() {
        prefs.edit().remove("messages").apply()
    }
}