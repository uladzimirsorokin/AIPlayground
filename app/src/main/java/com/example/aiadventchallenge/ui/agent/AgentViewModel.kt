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
import com.example.aiadventchallenge.data.agent.ContextStrategy
import com.example.aiadventchallenge.data.agent.DatabaseHistoryStore
import com.example.aiadventchallenge.data.agent.LongTermCategory
import com.example.aiadventchallenge.data.agent.LongTermEntry
import com.example.aiadventchallenge.data.agent.PrefsWorkingStore
import com.example.aiadventchallenge.data.agent.SqliteLongTermStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AgentSettings(
    val systemPrompt: String,
    val temperature: Float,
    val model: String,
    val jsonFormat: Boolean,
    val strategy: ContextStrategy,
    val historyWindow: Int,
    val longTerm: Boolean,
    val team: String
)

class AgentViewModel(
    application: Application
) : AndroidViewModel(application) {

    private companion object {
        const val AGENT_ID = "main"
    }

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
            strategy = runCatching {
                ContextStrategy.valueOf(prefs.getString("agent_strategy", null) ?: "")
            }.getOrDefault(ContextStrategy.SUMMARY),
            historyWindow = prefs.getInt("agent_history_window", 10),
            longTerm = prefs.getBoolean("agent_longterm", true),
            team = prefs.getString("agent_team", "main") ?: "main"
        )
    )
    val settings: StateFlow<AgentSettings> = _settings.asStateFlow()

    // Краткосрочная — своя на агента; рабочая — общая на команду; долговременная — глобальная.
    private val shortTermStore = DatabaseHistoryStore(getApplication(), AGENT_ID)
    private val workingStore = PrefsWorkingStore(getApplication()) { _settings.value.team }
    private val longTermStore = SqliteLongTermStore(getApplication())

    private val agent = ChatAgent(
        client = LlmClient(),
        systemPrompt = { _settings.value.systemPrompt },
        apiKey = { KeyStorage.load(getApplication()) },
        model = { _settings.value.model },
        temperature = { _settings.value.temperature.toDouble() },
        jsonFormat = { _settings.value.jsonFormat },
        strategy = { _settings.value.strategy },
        historyWindow = { _settings.value.historyWindow },
        shortTermStore = shortTermStore,
        workingStore = workingStore,
        longTermStore = longTermStore,
        longTermEnabled = { _settings.value.longTerm }
    )

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _hasSavedContext = MutableStateFlow(
        shortTermStore.load().isNotEmpty() || workingStore.loadSummary().isNotEmpty() ||
            longTermStore.load().isNotEmpty()
    )
    val hasSavedContext: StateFlow<Boolean> = _hasSavedContext.asStateFlow()

    private val _stats = MutableStateFlow(AgentStats())
    val stats: StateFlow<AgentStats> = _stats.asStateFlow()

    private val _canRetry = MutableStateFlow(false)
    val canRetry: StateFlow<Boolean> = _canRetry.asStateFlow()

    private val _historyTokens = MutableStateFlow(agent.historyEstimateTokens)
    val historyTokens: StateFlow<Int> = _historyTokens.asStateFlow()

    val summaryLength: Int get() = agent.summaryText.length
    val factsLength: Int get() = agent.factsText.length
    val historySize: Int get() = agent.history.size
    val workingChars: Int get() = agent.summaryText.length + agent.factsText.length
    val longTermCount: Int get() = agent.longTermCount

    private val _longTerm = MutableStateFlow(agent.longTermEntries)
    val longTerm: StateFlow<List<LongTermEntry>> = _longTerm.asStateFlow()

    fun addLongTerm(category: LongTermCategory, content: String) {
        agent.addLongTermEntry(category, content)
        _longTerm.value = agent.longTermEntries
    }

    fun removeLongTerm(id: Long) {
        agent.removeLongTermEntry(id)
        _longTerm.value = agent.longTermEntries
    }

    fun clearLongTerm() {
        agent.clearLongTerm()
        _longTerm.value = agent.longTermEntries
    }

    /** Юзерский system prompt + всё, что агент подмешивает из памяти (итоговый промпт). */
    val effectiveSystemPrompt: String
        get() = buildString {
            append(_settings.value.systemPrompt)
            if (_settings.value.longTerm) {
                agent.longTermText.takeIf { it.isNotBlank() }?.let {
                    append("\n\nДолговременная память:\n").append(it)
                }
            }
            agent.summaryText.takeIf { it.isNotBlank() }?.let {
                append("\n\nРезюме диалога (рабочая память):\n").append(it)
            }
            agent.factsText.takeIf { it.isNotBlank() }?.let {
                append("\n\nФакты о диалоге (рабочая память):\n").append(it)
            }
        }

    private val _branchNames = MutableStateFlow(agent.branchNames)
    val branchNames: StateFlow<List<String>> = _branchNames.asStateFlow()

    private val _activeBranch = MutableStateFlow(agent.activeBranch)
    val activeBranch: StateFlow<String> = _activeBranch.asStateFlow()

    fun saveCheckpoint() {
        agent.saveCheckpoint()
        _branchNames.value = agent.branchNames
    }

    fun forkFromCheckpoint() {
        agent.forkFromCheckpoint()
        _branchNames.value = agent.branchNames
        _activeBranch.value = agent.activeBranch
        reloadMessages()
    }

    fun switchBranch(name: String) {
        agent.switchBranch(name)
        _activeBranch.value = agent.activeBranch
        reloadMessages()
    }

    private fun reloadMessages() {
        _messages.value = agent.history.toList()
        _historyTokens.value = agent.historyEstimateTokens
        _hasSavedContext.value = false
        _canRetry.value = false
        pendingText = null
    }

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
                _longTerm.value = agent.longTermEntries
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
        val teamChanged = new.team != _settings.value.team
        prefs.edit()
            .putString("system_prompt", new.systemPrompt)
            .putFloat("agent_temperature", new.temperature)
            .putString("agent_model", new.model)
            .putBoolean("agent_json_format", new.jsonFormat)
            .putString("agent_strategy", new.strategy.name)
            .putInt("agent_history_window", new.historyWindow)
            .putBoolean("agent_longterm", new.longTerm)
            .putString("agent_team", new.team)
            .apply()
        _settings.value = new
        // Смена команды = смена скоупа рабочей памяти: перечитываем её из стора новой команды.
        if (teamChanged) agent.reloadWorkingMemory()
    }

    fun clearChat() {
        agent.clearHistory()
        _messages.value = emptyList()
        _hasSavedContext.value = false
        _stats.value = AgentStats()
        _canRetry.value = false
        _historyTokens.value = 0
        pendingText = null
        _branchNames.value = agent.branchNames
        _activeBranch.value = agent.activeBranch
        _longTerm.value = agent.longTermEntries
    }
}

private fun ChatMessage.isErrorBubble(): Boolean =
    role == "assistant" && content.startsWith("Ошибка")