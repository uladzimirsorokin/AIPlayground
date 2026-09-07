package com.example.aiadventchallenge.ui.agent

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiadventchallenge.BuildConfig
import com.example.aiadventchallenge.data.ChatMessage
import com.example.aiadventchallenge.data.KeyStorage
import com.example.aiadventchallenge.data.LlmClient
import com.example.aiadventchallenge.data.agent.ChatAgent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AgentSettings(
    val systemPrompt: String,
    val temperature: Float,
    val model: String,
    val jsonFormat: Boolean
)

class AgentViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val prefs =
        getApplication<Application>().getSharedPreferences("settings", Context.MODE_PRIVATE)

    val availableModels: List<String> = listOf(
        BuildConfig.LLM_MODEL,
        BuildConfig.LLM_MODEL_WEAK,
        BuildConfig.LLM_MODEL_MEDIUM,
        BuildConfig.LLM_MODEL_STRONG
    ).distinct()

    private val _settings = MutableStateFlow(
        AgentSettings(
            systemPrompt = prefs.getString("system_prompt", null)
                ?: "Ты — полезный и краткий ассистент. Отвечай по делу, без лишней воды.",
            temperature = prefs.getFloat("agent_temperature", 0.7f),
            model = prefs.getString("agent_model", BuildConfig.LLM_MODEL)
                ?: BuildConfig.LLM_MODEL,
            jsonFormat = prefs.getBoolean("agent_json_format", false)
        )
    )
    val settings: StateFlow<AgentSettings> = _settings.asStateFlow()

    private val agent = ChatAgent(
        client = LlmClient(),
        systemPrompt = { _settings.value.systemPrompt },
        apiKey = { KeyStorage.load(getApplication()) },
        model = { _settings.value.model },
        temperature = { _settings.value.temperature.toDouble() },
        jsonFormat = { _settings.value.jsonFormat }
    )

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        val new = transform(_settings.value)
        prefs.edit()
            .putString("system_prompt", new.systemPrompt)
            .putFloat("agent_temperature", new.temperature)
            .putString("agent_model", new.model)
            .putBoolean("agent_json_format", new.jsonFormat)
            .apply()
        _settings.value = new
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _sending.value) return

        _messages.value = _messages.value + ChatMessage("user", trimmed)
        _sending.value = true
        viewModelScope.launch {
            try {
                val response = agent.send(trimmed)
                _messages.value = _messages.value + ChatMessage("assistant", response.reply)
            } catch (e: Exception) {
                Log.e("AGENT", "Agent failed", e)
                _messages.value =
                    _messages.value + ChatMessage("assistant", "Ошибка: ${e.message ?: "неизвестная"}")
            } finally {
                _sending.value = false
            }
        }
    }

    fun clearChat() {
        agent.clearHistory()
        _messages.value = emptyList()
    }
}