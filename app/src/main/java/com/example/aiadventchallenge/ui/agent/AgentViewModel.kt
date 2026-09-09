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
import com.example.aiadventchallenge.data.agent.AgentStats
import com.example.aiadventchallenge.data.agent.ChatAgent
import com.example.aiadventchallenge.data.agent.DatabaseHistoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AgentSettings(
    val systemPrompt: String,
    val temperature: Float,
    val model: String,
    val jsonFormat: Boolean,
    val compactContext: Boolean
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
        BuildConfig.LLM_MODEL_STRONG,
        BuildConfig.LLM_MODEL_TEST
    ).filter { it.isNotBlank() }.distinct()

    private val _settings = MutableStateFlow(
        AgentSettings(
            systemPrompt = prefs.getString("system_prompt", null)
                ?: "Ты — полезный и краткий ассистент. Отвечай по делу, без лишней воды.",
            temperature = prefs.getFloat("agent_temperature", 0.7f),
            model = prefs.getString("agent_model", BuildConfig.LLM_MODEL)
                ?: BuildConfig.LLM_MODEL,
            jsonFormat = prefs.getBoolean("agent_json_format", false),
            compactContext = prefs.getBoolean("agent_compact_context", true)
        )
    )
    val settings: StateFlow<AgentSettings> = _settings.asStateFlow()

    private val historyStore = DatabaseHistoryStore(getApplication())

    private val agent = ChatAgent(
        client = LlmClient(),
        systemPrompt = { _settings.value.systemPrompt },
        apiKey = { KeyStorage.load(getApplication()) },
        model = { _settings.value.model },
        temperature = { _settings.value.temperature.toDouble() },
        jsonFormat = { _settings.value.jsonFormat },
        compactContext = { _settings.value.compactContext },
        historyStore = historyStore
    )

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _hasSavedContext = MutableStateFlow(historyStore.load().isNotEmpty())
    val hasSavedContext: StateFlow<Boolean> = _hasSavedContext.asStateFlow()

    private val _stats = MutableStateFlow(AgentStats())
    val stats: StateFlow<AgentStats> = _stats.asStateFlow()

    private val _canRetry = MutableStateFlow(false)
    val canRetry: StateFlow<Boolean> = _canRetry.asStateFlow()

    private val _historyTokens = MutableStateFlow(agent.historyEstimateTokens)
    val historyTokens: StateFlow<Int> = _historyTokens.asStateFlow()

    private var pendingText: String? = null

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _sending.value) return
        pendingText = trimmed
        executeSend(addUserBubble = true)
    }

    fun retry() {
        if (_sending.value || pendingText == null) return
        _messages.value = _messages.value.filterNot { it.isErrorBubble() }
        executeSend(addUserBubble = false)
    }

    private fun executeSend(addUserBubble: Boolean) {
        val text = pendingText ?: return
        if (addUserBubble) {
            _messages.value = _messages.value + ChatMessage("user", text)
        }
        _sending.value = true
        _canRetry.value = false
        viewModelScope.launch {
            try {
                val response = agent.send(text)
                pendingText = null
                if (response.compacted) {
                    _messages.value = _messages.value + ChatMessage(
                        role = "system",
                        content = "Контекст диалога сжат: ранние сообщения свернуты в резюме."
                    )
                }
                _messages.value = _messages.value + ChatMessage(
                    role = "assistant",
                    content = response.reply,
                    inputTokens = response.promptTokens,
                    outputTokens = response.completionTokens,
                    model = response.model,
                    compacted = response.compacted,
                    truncated = response.truncated
                )
                _stats.value = response.stats
            } catch (e: Exception) {
                Log.e("AGENT", "Agent failed", e)
                _messages.value =
                    _messages.value + ChatMessage("assistant", "Ошибка: ${e.message ?: "неизвестная"}")
                _canRetry.value = true
            } finally {
                _sending.value = false
                _historyTokens.value = agent.historyEstimateTokens
            }
        }
    }

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        val new = transform(_settings.value)
        prefs.edit()
            .putString("system_prompt", new.systemPrompt)
            .putFloat("agent_temperature", new.temperature)
            .putString("agent_model", new.model)
            .putBoolean("agent_json_format", new.jsonFormat)
            .putBoolean("agent_compact_context", new.compactContext)
            .apply()
        _settings.value = new
    }

    fun clearChat() {
        agent.clearHistory()
        _messages.value = emptyList()
        _hasSavedContext.value = false
        _stats.value = AgentStats()
        _canRetry.value = false
        _historyTokens.value = 0
        pendingText = null
    }
}

private fun ChatMessage.isErrorBubble(): Boolean =
    role == "assistant" && content.startsWith("Ошибка")