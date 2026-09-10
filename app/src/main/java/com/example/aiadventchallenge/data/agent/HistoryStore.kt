package com.example.aiadventchallenge.data.agent

import android.content.Context
import com.example.aiadventchallenge.data.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

interface HistoryStore {
    fun load(): List<ChatMessage>
    fun save(messages: List<ChatMessage>)
    fun clear()
    fun loadSummary(): String
    fun saveSummary(summary: String)
}

/**
 * Persists the agent's conversation history as JSON in SharedPreferences,
 * so the dialogue survives app restarts.
 */
class JsonHistoryStore(context: Context) : HistoryStore {

    private val prefs = context.getSharedPreferences("agent_history", Context.MODE_PRIVATE)

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

    override fun loadSummary(): String = prefs.getString("summary", "") ?: ""

    override fun saveSummary(summary: String) {
        prefs.edit().putString("summary", summary).apply()
    }

    override fun clear() {
        prefs.edit().remove("messages").remove("summary").apply()
    }
}