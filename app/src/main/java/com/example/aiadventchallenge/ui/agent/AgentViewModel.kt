package com.example.aiadventchallenge.ui.agent

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiadventchallenge.BuildConfig
import com.example.aiadventchallenge.R
import com.example.aiadventchallenge.data.ChatMessage
import com.example.aiadventchallenge.data.KeyStorage
import com.example.aiadventchallenge.data.LlmClient
import com.example.aiadventchallenge.data.agent.AgentStats
import com.example.aiadventchallenge.data.agent.ChatAgent
import com.example.aiadventchallenge.data.agent.ContextStrategy
import com.example.aiadventchallenge.data.agent.DatabaseHistoryStore
import com.example.aiadventchallenge.data.agent.LongTermCategory
import com.example.aiadventchallenge.data.agent.LongTermEntry
import com.example.aiadventchallenge.data.agent.Invariant
import com.example.aiadventchallenge.data.agent.InvariantCategory
import com.example.aiadventchallenge.data.agent.PrefsInvariantsStore
import com.example.aiadventchallenge.data.agent.PrefsProfileStore
import com.example.aiadventchallenge.data.agent.PrefsTaskStateStore
import com.example.aiadventchallenge.data.agent.PrefsWorkingStore
import com.example.aiadventchallenge.data.agent.SavedProfile
import com.example.aiadventchallenge.data.agent.SqliteLongTermStore
import com.example.aiadventchallenge.data.agent.TaskState
import com.example.aiadventchallenge.data.agent.TaskStage
import com.example.aiadventchallenge.data.agent.TransitionResult
import com.example.aiadventchallenge.data.agent.UserProfile
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
    val taskState: Boolean,
    val invariants: Boolean,
    val invariantGuard: Boolean,
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
            taskState = prefs.getBoolean("agent_task_state", true),
            invariants = prefs.getBoolean("agent_invariants", true),
            invariantGuard = prefs.getBoolean("agent_invariants_guard", true),
            team = prefs.getString("agent_team", "main") ?: "main"
        )
    )
    val settings: StateFlow<AgentSettings> = _settings.asStateFlow()

    // Краткосрочная — своя на агента; рабочая — общая на команду; долговременная — глобальная.
    private val shortTermStore = DatabaseHistoryStore(getApplication(), AGENT_ID)
    private val workingStore = PrefsWorkingStore(getApplication()) { _settings.value.team }
    private val longTermStore = SqliteLongTermStore(getApplication())
    private val profileStore = PrefsProfileStore(getApplication())
    private val taskStateStore = PrefsTaskStateStore(getApplication()) { _settings.value.team }
    private val invariantsStore = PrefsInvariantsStore(getApplication())

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
        longTermEnabled = { _settings.value.longTerm },
        profileStore = profileStore,
        taskStateStore = taskStateStore,
        taskStateEnabled = { _settings.value.taskState },
        invariantsStore = invariantsStore,
        invariantsEnabled = { _settings.value.invariants },
        invariantGuardEnabled = { _settings.value.invariantGuard }
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

    private val _profile = MutableStateFlow(agent.currentProfile)
    val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    private val _savedProfiles = MutableStateFlow(agent.savedProfiles)
    val savedProfiles: StateFlow<List<SavedProfile>> = _savedProfiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow(agent.activeProfileId)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    private val _buildingProfile = MutableStateFlow(false)
    val buildingProfile: StateFlow<Boolean> = _buildingProfile.asStateFlow()

    fun createProfile(label: String) {
        agent.createProfile(label)
        _profile.value = agent.currentProfile
        _savedProfiles.value = agent.savedProfiles
        _activeProfileId.value = agent.activeProfileId
    }

    fun selectProfile(id: String) {
        agent.selectProfile(id)
        _profile.value = agent.currentProfile
        _savedProfiles.value = agent.savedProfiles
        _activeProfileId.value = agent.activeProfileId
    }

    fun updateProfile(new: UserProfile, label: String) {
        agent.updateProfile(new, label)
        _profile.value = new
        _savedProfiles.value = agent.savedProfiles
    }

    fun deleteProfile(id: String) {
        agent.deleteProfile(id)
        _profile.value = agent.currentProfile
        _savedProfiles.value = agent.savedProfiles
        _activeProfileId.value = agent.activeProfileId
    }

    fun clearProfile() {
        agent.clearProfile()
        _profile.value = agent.currentProfile
        _savedProfiles.value = agent.savedProfiles
    }

    fun buildProfile() {
        if (_buildingProfile.value) return
        _buildingProfile.value = true
        viewModelScope.launch {
            try {
                val built = agent.buildProfileFromConversation()
                _profile.value = built
                _savedProfiles.value = agent.savedProfiles
            } catch (e: Exception) {
                Log.e("AGENT", "Profile build failed", e)
            } finally {
                _buildingProfile.value = false
            }
        }
    }

    private val _taskState = MutableStateFlow(agent.taskState)
    val taskState: StateFlow<TaskState> = _taskState.asStateFlow()

    private val _taskPaused = MutableStateFlow(agent.taskPaused)
    val taskPaused: StateFlow<Boolean> = _taskPaused.asStateFlow()

    fun pauseTask() {
        agent.pauseTask()
        _taskPaused.value = agent.taskPaused
    }

    fun resumeTask() {
        agent.resumeTask()
        _taskPaused.value = agent.taskPaused
    }

    fun resetTask() {
        agent.resetTask()
        _taskState.value = agent.taskState
        _taskPaused.value = agent.taskPaused
    }

    /** Явный запрос перехода на этап; недопустимый отклоняется с причиной и списком разрешённых целей. */
    fun requestStage(stage: TaskStage) {
        val result = agent.requestTaskTransition(stage)
        _taskState.value = agent.taskState
        _taskPaused.value = agent.taskPaused
        _messages.value = _messages.value + when (result) {
            is TransitionResult.Ok -> ChatMessage(
                role = "system",
                content = getApplication<Application>().getString(R.string.task_transition_ok, result.target.name)
            )
            is TransitionResult.Rejected -> ChatMessage(
                role = "system",
                content = getApplication<Application>().getString(
                    R.string.task_transition_rejected,
                    result.from.name,
                    result.target.name,
                    result.reason,
                    if (result.allowed.isEmpty()) "—" else result.allowed.joinToString(", ") { it.name }
                )
            )
        }
    }

    private val _verifying = MutableStateFlow(false)
    val verifying: StateFlow<Boolean> = _verifying.asStateFlow()

    fun verifyTaskResult() {
        if (_verifying.value || _sending.value) return
        _verifying.value = true
        viewModelScope.launch {
            try {
                val verdict = agent.verifyTaskResult()
                if (verdict != null) {
                    _messages.value = _messages.value + ChatMessage(
                        role = "system",
                        content = if (verdict.passed) {
                            getApplication<Application>().getString(R.string.task_verified_ok)
                        } else {
                            getApplication<Application>().getString(
                                R.string.task_verified_fail,
                                verdict.comments.ifBlank { "—" }
                            )
                        }
                    )
                }
                _taskState.value = agent.taskState
                _taskPaused.value = agent.taskPaused
            } catch (e: Exception) {
                Log.e("AGENT", "Verify failed", e)
                _messages.value = _messages.value + ChatMessage(
                    role = "assistant",
                    content = "Ошибка верификации: ${e.message ?: "неизвестная"}"
                )
            } finally {
                _verifying.value = false
            }
        }
    }

    private val _invariants = MutableStateFlow(agent.invariantList)
    val invariants: StateFlow<List<Invariant>> = _invariants.asStateFlow()

    fun addInvariant(category: InvariantCategory, content: String) {
        agent.addInvariant(category, content)
        _invariants.value = agent.invariantList
    }

    fun removeInvariant(id: Long) {
        agent.removeInvariant(id)
        _invariants.value = agent.invariantList
    }

    fun clearInvariants() {
        agent.clearInvariants()
        _invariants.value = agent.invariantList
    }

    /** Юзерский system prompt + всё, что агент подмешивает из памяти (итоговый промпт). */
    val effectiveSystemPrompt: String
        get() = buildString {
            append(_settings.value.systemPrompt)
            agent.currentProfile.text.takeIf { it.isNotBlank() }?.let {
                append("\n\n").append(it)
            }
            if (_settings.value.invariants) {
                agent.invariantBlock.takeIf { it.isNotBlank() }?.let {
                    append("\n\n").append(it)
                }
            }
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
            if (_settings.value.taskState) {
                agent.taskSummaryText.takeIf { it.isNotBlank() }?.let {
                    append("\n\n").append(it)
                }
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
                _taskState.value = agent.taskState
                _taskPaused.value = agent.taskPaused
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
            .putBoolean("agent_task_state", new.taskState)
            .putBoolean("agent_invariants", new.invariants)
            .putBoolean("agent_invariants_guard", new.invariantGuard)
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