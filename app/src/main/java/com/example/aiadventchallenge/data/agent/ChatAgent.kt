package com.example.aiadventchallenge.data.agent

import android.util.Log
import com.example.aiadventchallenge.BuildConfig
import com.example.aiadventchallenge.data.ChatMessage
import com.example.aiadventchallenge.data.ChatTool
import com.example.aiadventchallenge.data.CompletionResult
import com.example.aiadventchallenge.data.LlmClient
import com.example.aiadventchallenge.data.mcp.McpClient
import com.example.aiadventchallenge.data.mcp.McpTool
import org.json.JSONObject

/**
 * Conversational agent with pluggable context-management strategies and an explicit
 * three-layer memory model (День 11):
 *
 * - SHORT_TERM: the current dialogue (active branch), isolated per agent (HistoryStore scoped by agent id).
 * - WORKING: current-task data (rolling summary / facts), SHARED per team — every agent of the
 *   same team reads/writes the same summary and facts (WorkingStore scoped by team id).
 * - LONG_TERM: profile, decisions and knowledge, extracted by an explicit LLM call and
 *   stored in a global LongTermStore; injected into every request as a system message.
 *
 * Strategies:
 * - SLIDING_WINDOW: only the last N messages are sent, the rest is dropped from the request.
 * - FACTS: a key-value "facts" block is updated after every user message (an extra LLM call)
 *   and sent together with the last N messages.
 * - BRANCHING: the dialogue can be checkpointed and forked into independent branches,
 *   the active branch's full history is sent.
 * - SUMMARY: the last N messages are kept verbatim, older messages are folded into a
 *   rolling summary (an extra LLM call) which is sent instead of the full history.
 *
 * The full history is always retained in storage (except for strategy-based trimming
 * of what is sent); with no compression everything is sent.
 */
class ChatAgent(
    private val client: LlmClient,
    private val systemPrompt: () -> String,
    private val apiKey: () -> String?,
    private val model: () -> String,
    private val temperature: () -> Double?,
    private val jsonFormat: () -> Boolean,
    private val strategy: () -> ContextStrategy,
    private val historyWindow: () -> Int,
    private val shortTermStore: HistoryStore,
    private val workingStore: WorkingStore,
    private val longTermStore: LongTermStore,
    private val longTermEnabled: () -> Boolean,
    private val profileStore: ProfileStore,
    private val taskStateStore: TaskStateStore,
    private val taskStateEnabled: () -> Boolean,
    private val invariantsStore: InvariantsStore,
    private val invariantsEnabled: () -> Boolean,
    private val invariantGuardEnabled: () -> Boolean,
    private val mcpEnabled: () -> Boolean,
    private val mcpEndpoints: () -> List<String>,
    val contextLimit: Int = BuildConfig.LLM_CONTEXT_LIMIT
) : Agent {

    private companion object {
        const val MAX_TOOL_ROUNDS = 10
        const val FORMAT_DESCRIPTION =
            "Отвечай строго в JSON без markdown и пояснений."
        const val SUMMARY_PROMPT =
            "Сожми следующие сообщения диалога в краткое резюме на русском, " +
                "сохранив ключевые факты, цифры и договорённости. Без лишнего."
        const val FACTS_PROMPT =
            "Ниже блок фактов о пользователе и диалоге (ключ: значение). " +
                "Обнови его по новому сообщению: добавь новые факты, исправь изменившиеся, " +
                "удали устаревшие. Верни ТОЛЬКО список фактов, каждый на новой строке " +
                "в формате \"ключ: значение\". Если новых фактов нет — верни исходный блок."
        const val GUARD_PROMPT =
            "Ты — страж инвариантов проекта. Ниже инварианты, которые нельзя нарушать, и предлагаемое решение.\n" +
                "Если решение нарушает хотя бы один инвариант — откажись и объясни.\n" +
                "Верни ТОЛЬКО JSON (без markdown): " +
                "{\"violates\":true,\"category\":\"STACK\",\"refusal\":\"Отказ с объяснением, какой инвариант нарушен и почему\"}\n" +
                "- violates: true/false\n" +
                "- category: категория нарушенного инварианта (ARCHITECTURE/DECISIONS/STACK/BUSINESS) или \"\"\n" +
                "- refusal: текст отказа с объяснением; пустая строка, если violations=false"
        const val PROFILE_PROMPT =
            "Ты — модуль построения профиля пользователя. По диалогу определи устойчивые предпочтения.\n" +
                "Верни ТОЛЬКО JSON (без markdown и пояснений) вида: " +
                "{\"name\":\"...\",\"style\":\"...\",\"format\":\"...\",\"constraints\":\"...\",\"extra\":\"...\"}\n" +
                "- name: как обращаться к пользователю, его роль\n" +
                "- style: стиль общения (формальный/неформальный, краткий/развёрнутый, на «ты»/«вы»)\n" +
                "- format: предпочитаемый формат ответов (списки, таблицы, код, примеры)\n" +
                "- constraints: ограничения и запреты (чего избегать, лимиты)\n" +
                "- extra: прочие устойчивые предпочтения\n" +
                "Пустые поля — пустые строки. Не выдумывай того, чего нет в диалоге."
        const val KEEP_RECENT = 6
    }

    // Явная трёхслойная модель памяти.
    private val memory = AgentMemory(
        client = client,
        model = model,
        longTermEnabled = longTermEnabled,
        longTermStore = longTermStore
    )

    // Профиль пользователя (День 12): библиотека профилей + активный, подмешивается в каждый запрос.
    private var profiles: List<SavedProfile> = profileStore.loadProfiles()
    private var _activeProfileId: String? = profileStore.loadActiveId()
        .takeIf { id -> id != null && profiles.any { it.id == id } }
    private var profile: UserProfile =
        profiles.firstOrNull { it.id == _activeProfileId }?.profile ?: UserProfile()

    val currentProfile: UserProfile get() = profile

    val savedProfiles: List<SavedProfile> get() = profiles.toList()

    val activeProfileId: String? get() = _activeProfileId

    // Формализованное состояние задачи (День 13): конечный автомат этап/шаг/ожидаемое действие.
    private val taskMachine = TaskStateMachine(
        client = client,
        model = model,
        store = taskStateStore
    )

    val taskState: TaskState get() = taskMachine.state

    val taskPaused: Boolean get() = taskMachine.paused

    val taskSummaryText: String get() = taskMachine.summaryText

    fun pauseTask() = taskMachine.pause()

    fun resumeTask() = taskMachine.resume()

    fun resetTask() = taskMachine.reset()

    /** Явный запрос перехода на этап: допустимый выполняется, недопустимый отклоняется. */
    fun requestTaskTransition(stage: TaskStage): TransitionResult = taskMachine.requestTransition(stage)

    /** Отправляет последний ответ ассистента на верификацию; автомат сам решает DONE или доработка. */
    suspend fun verifyTaskResult(): VerificationResult? {
        val key = apiKey() ?: throw IllegalStateException("API key is not set")
        val resultText = _history.lastOrNull { it.role == "assistant" }?.content ?: return null
        return taskMachine.verifyResult(key, _history, resultText)
    }

    // Инварианты проекта (День 14): правила, которые ассистент не имеет права нарушать.
    private var invariants: List<Invariant> = invariantsStore.load()

    val invariantList: List<Invariant> get() = invariants.toList()

    /** Блок для инъекции в каждый запрос. */
    val invariantBlock: String
        get() = if (invariants.isEmpty()) "" else buildString {
            append("Инварианты (не нарушать):\n")
            invariants.forEach { inv ->
                append("- [").append(inv.category.name.lowercase()).append("] ").append(inv.content).append("\n")
            }
        }

    fun addInvariant(category: InvariantCategory, content: String) {
        if (content.isBlank()) return
        invariants = invariants + Invariant(id = System.nanoTime(), category = category, content = content.trim())
        invariantsStore.save(invariants)
        Log.d("AGENT", "invariant: added [${category}] total=${invariants.size}")
    }

    fun removeInvariant(id: Long) {
        invariants = invariants.filterNot { it.id == id }
        invariantsStore.save(invariants)
        Log.d("AGENT", "invariant: removed id=$id total=${invariants.size}")
    }

    fun clearInvariants() {
        invariants = emptyList()
        invariantsStore.clear()
        Log.d("AGENT", "invariant: cleared")
    }

    /** Страж: отдельный LLM-вызов проверяет предложенное решение по инвариантам. */
    private suspend fun checkInvariants(key: String, reply: String): GuardVerdict? = try {
        val result = client.completeChat(
            listOf(
                ChatMessage("system", GUARD_PROMPT),
                ChatMessage("user", "Инварианты:\n$invariantBlock\n\nПредлагаемое решение:\n$reply")
            ),
            key,
            model = model(),
            responseFormat = "json_object",
            temperature = 0.2
        )
        parseGuardVerdict(result.content)
    } catch (e: Exception) {
        Log.d("AGENT", "invariant: guard failed: ${e.message}")
        null
    }

    private fun parseGuardVerdict(raw: String): GuardVerdict? = try {
        val clean = raw.trim().removePrefix("```json").removeSuffix("```").trim()
        val o = JSONObject(clean)
        GuardVerdict(
            violates = o.optBoolean("violates", false),
            category = o.optString("category", ""),
            refusal = o.optString("refusal", "").trim()
        )
    } catch (e: Exception) {
        null
    }

    // Краткосрочный слой = активная ветка диалога; ветки — живые списки.
    private var _history: MutableList<ChatMessage>
        get() = memory.shortTerm
        set(value) {
            memory.shortTerm = value
        }

    // Поллер сработавших напоминаний: переиспользует сессии между тиками по каждому серверу.
    private val pollerClients = mutableMapOf<String, McpClient>()
    private val pollerTools = mutableMapOf<String, Set<String>>()

    /** Нормализованный список эндпоинтов MCP (без пустых и дублей). */
    private fun mcpEndpointsList(): List<String> = mcpEndpoints().filter { it.isNotBlank() }.distinct()

    init {
        _history.addAll(shortTermStore.load())
        memory.summary = workingStore.loadSummary()
        memory.facts = workingStore.loadFacts()
    }

    override val history: List<ChatMessage> get() = _history.toList()

    val summaryText: String get() = memory.summary

    val factsText: String get() = memory.facts

    val longTermCount: Int get() = memory.longTermCount

    val longTermText: String get() = memory.longTermText

    val longTermEntries: List<LongTermEntry> get() = memory.longTerm.toList()

    /** Принудительное добавление записи в долговременный слой (вручную). */
    fun addLongTermEntry(category: LongTermCategory, content: String) {
        memory.addManual(category, content)
    }

    fun removeLongTermEntry(id: Long) {
        memory.remove(id)
    }

    fun clearLongTerm() {
        memory.clearLongTerm()
    }

    /** Перечитывает рабочую память из стора — нужно при смене команды (скоупа). */
    fun reloadWorkingMemory() {
        memory.summary = workingStore.loadSummary()
        memory.facts = workingStore.loadFacts()
        Log.d("AGENT", "memory: working reloaded ${memory.info}")
    }

    fun createProfile(label: String): UserProfile {
        val id = System.nanoTime().toString()
        val sp = SavedProfile(id, label.ifBlank { "Профиль ${profiles.size + 1}" }, UserProfile())
        profiles = profiles + sp
        _activeProfileId = id
        profile = sp.profile
        profileStore.saveProfiles(profiles)
        profileStore.saveActiveId(id)
        Log.d("AGENT", "profile: created \"${sp.label}\" id=$id")
        return profile
    }

    fun selectProfile(id: String) {
        val sp = profiles.firstOrNull { it.id == id } ?: return
        _activeProfileId = id
        profile = sp.profile
        profileStore.saveActiveId(id)
        Log.d("AGENT", "profile: selected \"${sp.label}\"")
    }

    fun updateProfile(p: UserProfile, label: String? = null) {
        profile = p
        profiles = profiles.map { sp ->
            if (sp.id == _activeProfileId) sp.copy(label = label ?: sp.label, profile = p) else sp
        }
        profileStore.saveProfiles(profiles)
        Log.d("AGENT", "profile: saved \"${label ?: ""}\" ${p.text.length}chars")
    }

    fun deleteProfile(id: String) {
        profiles = profiles.filterNot { it.id == id }
        if (_activeProfileId == id) {
            _activeProfileId = profiles.firstOrNull()?.id
            profile = profiles.firstOrNull()?.profile ?: UserProfile()
            profileStore.saveActiveId(_activeProfileId)
        }
        profileStore.saveProfiles(profiles)
        Log.d("AGENT", "profile: deleted id=$id active=${_activeProfileId}")
    }

    fun clearProfile() {
        updateProfile(UserProfile())
    }

    /** Собирает/дополняет активный профиль из последних сообщений диалога (доп. LLM-вызов). */
    suspend fun buildProfileFromConversation(): UserProfile {
        val key = apiKey() ?: throw IllegalStateException("API key is not set")
        if (_history.isEmpty()) return profile
        val result = client.completeChat(
            buildList {
                add(ChatMessage("system", PROFILE_PROMPT))
                add(
                    ChatMessage(
                        "user",
                        "Диалог (последние 30 сообщений):\n" +
                            _history.takeLast(30).joinToString("\n") { "${it.role}: ${it.content}" }
                    )
                )
            },
            key,
            model = model(),
            responseFormat = "json_object",
            temperature = 0.2
        )
        val parsed = parseProfile(result.content)
        val merged = profile.copy(
            name = parsed.name.ifBlank { profile.name },
            style = parsed.style.ifBlank { profile.style },
            format = parsed.format.ifBlank { profile.format },
            constraints = parsed.constraints.ifBlank { profile.constraints },
            extra = parsed.extra.ifBlank { profile.extra }
        )
        updateProfile(merged)
        Log.d("AGENT", "profile: built from ${_history.size}msgs ${merged.text.length}chars")
        return merged
    }

    private fun parseProfile(raw: String): UserProfile = try {
        val clean = raw.trim().removePrefix("```json").removeSuffix("```").trim()
        val obj = JSONObject(clean)
        UserProfile(
            name = obj.optString("name", "").trim(),
            style = obj.optString("style", "").trim(),
            format = obj.optString("format", "").trim(),
            constraints = obj.optString("constraints", "").trim(),
            extra = obj.optString("extra", "").trim()
        )
    } catch (e: Exception) {
        UserProfile()
    }

    // Each branch is a live MutableList; the active branch is the same object as _history,
    // so messages added while a branch is active are preserved when switching.
    private val branches = mutableMapOf<String, MutableList<ChatMessage>>()
    private var activeBranchName = "основная"
        set(value) {
            field = value
            branches[value] = _history
        }
    val activeBranch: String get() = activeBranchName
    private var checkpoint: List<ChatMessage>? = null
    val branchNames: List<String> get() = branches.keys.toList()

    init {
        branches[activeBranchName] = _history
    }

    val historyEstimateTokens: Int get() = estimate(_history)

    private var stats = AgentStats()
    private var compactCount = 0
    private var savedTokens = 0L

    override suspend fun send(userMessage: String): AgentResponse {
        val command = userMessage.trim()
        if (command.equals("mcplist", ignoreCase = true)) {
            return handleMcpListCommand(command)
        }

        val key = apiKey() ?: throw IllegalStateException("API key is not set")
        val strat = strategy()
        val window = historyWindow()
        Log.d(
            "AGENT",
            "send: layers=${memory.info} branch=$activeBranchName " +
                "strategy=$strat window=$window limit=$contextLimit est=${estimate(_history)}"
        )

        var compacted = false
        if (strat == ContextStrategy.SUMMARY) {
            if (_history.size > window + window) {
                foldIntoSummary(key, keep = window)
                compacted = true
            }
            if (ensureFits(key)) compacted = true
        }

        val json = jsonFormat()
        val content = if (json) "$userMessage\n\n$FORMAT_DESCRIPTION" else userMessage
        _history.add(ChatMessage("user", content))

        if (strat == ContextStrategy.FACTS) {
            memory.facts = updateFacts(key, memory.facts, userMessage)
            workingStore.saveFacts(memory.facts)
        }

        val messages = buildList {
            systemPrompt().takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", it)) }
            // Профиль пользователя подмешивается в каждый запрос.
            profile.text.takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", it)) }
            // Инварианты проекта — правила, которые нельзя нарушать.
            if (invariantsEnabled()) {
                invariantBlock.takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", it)) }
            }
            // Долговременный слой подмешивается в каждый запрос независимо от стратегии.
            memory.longTermText.takeIf { it.isNotBlank() && longTermEnabled() }?.let {
                add(ChatMessage("system", "Долговременная память:\n$it"))
            }
            // Формализованное состояние задачи.
            if (taskStateEnabled()) {
                taskMachine.summaryText.takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", it)) }
            }
            when (strat) {
                ContextStrategy.SLIDING_WINDOW ->
                    _history.takeLast(window).forEach { add(it) }

                ContextStrategy.FACTS -> {
                    memory.facts.takeIf { it.isNotBlank() }?.let {
                        add(ChatMessage("system", "Факты о диалоге:\n$it"))
                    }
                    _history.takeLast(window).forEach { add(it) }
                }

                ContextStrategy.BRANCHING ->
                    _history.forEach { add(it) }

                ContextStrategy.SUMMARY -> {
                    memory.summary.takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", it)) }
                    _history.takeLast(window).forEach { add(it) }
                }
            }
        }

        var sentMessages = messages
        val result = try {
            if (mcpEnabled() && mcpEndpointsList().isNotEmpty()) {
                try {
                    runWithMcpTools(messages, key, json) { finalMessages -> sentMessages = finalMessages }
                } catch (e: Exception) {
                    Log.d("AGENT", "mcp: error, fallback to plain call: ${e.message}")
                    callModel(messages, key, json, null)
                }
            } else {
                callModel(messages, key, json, null)
            }
        } catch (e: Exception) {
            _history.removeAt(_history.size - 1)
            throw e
        }
        val sentChars = sentMessages.sumOf { it.content.length }
        savedTokens += (estimate(_history) - estimate(sentMessages)).coerceAtLeast(0)
        val truncated = result.promptTokens > 0 &&
            result.promptTokens < (sentChars / 3.0) * 0.6

        // Страж инвариантов: если включён и есть правила — решение проверяется,
        // при нарушении показываем отказ с объяснением вместо ответа.
        var reply = result.content
        if (invariantsEnabled() && invariantGuardEnabled() && invariantBlock.isNotBlank()) {
            checkInvariants(key, result.content)?.takeIf { it.violates }?.let { verdict ->
                reply = verdict.refusal.ifBlank { "Отказ: решение нарушает инварианты проекта." }
                Log.d("AGENT", "invariant: refused category=${verdict.category} \"${reply.take(140)}\"")
            }
        }

        _history.add(ChatMessage("assistant", reply))
        shortTermStore.save(_history)
        workingStore.saveSummary(memory.summary)
        memory.consolidate(key, userMessage, reply)
        if (taskStateEnabled()) taskMachine.updateState(key, userMessage, reply)
        Log.d(
            "AGENT",
            "done: model=${result.model} in=${result.promptTokens} out=${result.completionTokens} " +
                "total=${result.totalTokens} cost=${result.costUsd} history=${_history.size}msgs " +
                "summary=${memory.summary.length} profile=${profile.text.length} layers=${memory.info} " +
                "invariants=${invariants.size} task=${taskMachine.state.stage}/${taskMachine.state.step} paused=${taskMachine.paused} " +
                "sentEst=${estimate(sentMessages)} fullEst=${estimate(_history)} " +
                "saved=${stats.savedTokens} compactions=${stats.compactions} requests=${stats.requests} " +
                "compacted=$compacted truncated=$truncated"
        )

        stats = AgentStats(
            requests = stats.requests + 1,
            inputTokens = stats.inputTokens + result.promptTokens,
            outputTokens = stats.outputTokens + result.completionTokens,
            totalTokens = stats.totalTokens + result.totalTokens,
            costUsd = stats.costUsd + result.costUsd,
            compactions = compactCount,
            savedTokens = savedTokens
        )

        return AgentResponse(
            reply = reply,
            promptTokens = result.promptTokens,
            completionTokens = result.completionTokens,
            totalTokens = result.totalTokens,
            costUsd = result.costUsd,
            stats = stats,
            compacted = compacted,
            model = result.model,
            truncated = truncated
        )
    }

    override fun clearHistory() {
        memory.clear()
        shortTermStore.clear()
        workingStore.clear()
        stats = AgentStats()
        compactCount = 0
        savedTokens = 0L
        branches.clear()
        activeBranchName = "основная"
        checkpoint = null
    }

    fun saveCheckpoint() {
        checkpoint = _history.toList()
    }

    fun forkFromCheckpoint() {
        val cp = checkpoint ?: return
        val newName = "ветка_${branches.size + 1}"
        val fork = cp.toMutableList()
        branches[newName] = fork
        _history = fork
        activeBranchName = newName
        shortTermStore.save(_history)
    }

    fun switchBranch(name: String) {
        val target = branches[name] ?: return
        _history = target
        activeBranchName = name
        shortTermStore.save(_history)
    }

    private suspend fun updateFacts(key: String, current: String, message: String): String = try {
        client.completeChat(
            buildList {
                add(ChatMessage("system", FACTS_PROMPT))
                add(
                    ChatMessage(
                        "user",
                        "Текущие факты:\n${current.ifBlank { "(нет)" }}\n\nНовое сообщение:\n$message"
                    )
                )
            },
            key,
            model = model()
        ).content.trim()
    } catch (e: Exception) {
        current
    }

    private suspend fun foldIntoSummary(key: String, keep: Int) {
        val recent = _history.takeLast(keep)
        val old = _history.dropLast(keep)
        if (old.isEmpty()) return

        val merged = buildList {
            memory.summary.takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", it)) }
            addAll(old)
        }
        val newSummary = try {
            client.completeChat(
                buildList {
                    add(ChatMessage("system", SUMMARY_PROMPT))
                    addAll(merged)
                },
                key,
                model = model()
            ).content.trim()
        } catch (e: Exception) {
            memory.summary
        }
        if (newSummary.isNotBlank()) memory.summary = newSummary
        compactCount++
        Log.d("AGENT", "fold: folded=${old.size}msgs summary=${memory.summary.length}")
        workingStore.saveSummary(memory.summary)
    }

    private suspend fun ensureFits(key: String): Boolean {
        if (estimate(_history.takeLast(KEEP_RECENT)) + memory.summary.length / 3 <= contextLimit) return false
        if (_history.size > KEEP_RECENT) foldIntoSummary(key, keep = KEEP_RECENT)
        return true
    }

    private fun estimate(messages: List<ChatMessage>): Int = messages.sumOf { it.content.length } / 3

    /** Команда mcplist: список инструментов всех подключённых MCP-серверов (без LLM-вызова). */
    private suspend fun handleMcpListCommand(command: String): AgentResponse {
        _history.add(ChatMessage("user", command))
        val reply = try {
            val endpoints = mcpEndpointsList()
            if (endpoints.isEmpty()) {
                "MCP не настроен: список эндпоинтов пуст (настройки агента / local.properties)."
            } else {
                buildString {
                    endpoints.forEachIndexed { i, ep ->
                        if (i > 0) append("\n\n")
                        val conn = McpClient(ep).connectAndListTools()
                        if (conn.tools.isEmpty()) {
                            append("MCP [$ep]: сервер не отдал инструментов.")
                        } else {
                            append("MCP [").append(ep).append("] (протокол ")
                                .append(conn.protocolVersion.ifBlank { "?" })
                                .append("):\n")
                            conn.tools.forEach { t ->
                                append("- ").append(t.name)
                                if (t.description.isNotBlank()) append(" — ").append(t.description)
                                append("\n")
                            }
                        }
                    }
                    if (!mcpEnabled()) {
                        append("\nАвтовызов инструментов в чате выключен (настройки → MCP-инструменты).")
                    }
                }.trimEnd()
            }
        } catch (e: Exception) {
            Log.d("AGENT", "mcp: mcplist failed: ${e.message}")
            "MCP недоступен: ${e.message ?: "ошибка"}"
        }
        _history.add(ChatMessage("assistant", reply))
        shortTermStore.save(_history)
        Log.d("AGENT", "mcp: mcplist -> ${reply.take(120)}")
        return AgentResponse(
            reply = reply,
            promptTokens = 0,
            completionTokens = 0,
            totalTokens = 0,
            costUsd = 0.0,
            stats = stats
        )
    }

    /** Один вызов модели (с опциональными инструментами). */
    private suspend fun callModel(
        messages: List<ChatMessage>,
        key: String,
        json: Boolean,
        tools: List<ChatTool>?
    ): CompletionResult = client.completeChat(
        messages,
        key,
        model = model(),
        responseFormat = if (json) "json_object" else null,
        temperature = temperature(),
        tools = tools
    )

    /** function-calling цикл через несколько MCP-серверов (День 20): подключаемся ко всем
     *  эндпоинтам, агрегируем инструменты (имя → сервер) и маршрутизируем вызовы по имени. */
    private suspend fun runWithMcpTools(
        messages: List<ChatMessage>,
        key: String,
        json: Boolean,
        onFinalMessages: (List<ChatMessage>) -> Unit
    ): CompletionResult {
        val endpoints = mcpEndpointsList()
        if (endpoints.isEmpty()) return callModel(messages, key, json, null)

        // Агрегация: имя инструмента → клиент и сервер, с которого он пришёл.
        val clientOf = mutableMapOf<String, McpClient>()
        val serverOf = mutableMapOf<String, String>()
        val tools = mutableListOf<ChatTool>()
        for (ep in endpoints) {
            val mcp = McpClient(ep)
            val conn = runCatching { mcp.connectAndListTools() }.getOrNull()
            if (conn == null || conn.tools.isEmpty()) continue
            for (t in conn.tools) {
                val ct = t.toChatTool() ?: continue
                if (clientOf.containsKey(ct.name)) continue // дубль имени — берём первый сервер
                clientOf[ct.name] = mcp
                serverOf[ct.name] = ep
                tools.add(ct)
            }
        }
        if (tools.isEmpty()) return callModel(messages, key, json, null)
        Log.d("AGENT", "mcp: servers=${endpoints} tools=${tools.map { it.name }}")

        // Сработавшие напоминания доставляются в чат принудительно: check_due_reminders
        // вызывается в начале каждого запроса у каждого сервера, где такой инструмент есть,
        // и блок вставляется в контекст системным сообщением (once-delivery не даст задвоения).
        var baseMessages = messages
        for ((name, mcp) in clientOf) {
            if (name == "check_due_reminders") {
                runCatching {
                    dueRemindersText(mcp.callTool("check_due_reminders", "{}"))?.let {
                        baseMessages = baseMessages + ChatMessage("system", it)
                    }
                }
            }
        }
        // Напоминание модели: после цепочки MCP-вызовов она иногда отделывается кратким
        // «готово» (out=~20 токенов) и не показывает результат. Просим полный ответ.
        baseMessages = baseMessages + ChatMessage(
            "system",
            "Тебе доступны MCP-инструменты с нескольких серверов; для задачи может понадобиться " +
                "цепочка вызовов. В конце ОБЯЗАТЕЛЬНО дай пользователю полный ответ на русском со всеми " +
                "результатами инструментов (текст/содержимое, id, пути), а не просто «готово»."
        )

        var loopMessages = baseMessages
        var result = callModel(loopMessages, key, json, tools)
        var rounds = 1
        while (result.toolCalls.isNotEmpty() && rounds < MAX_TOOL_ROUNDS) {
            loopMessages = loopMessages + ChatMessage("assistant", result.content, toolCalls = result.toolCalls)
            for (tc in result.toolCalls) {
                val mcp = clientOf[tc.name]
                val toolResult = if (mcp != null) {
                    mcp.callTool(tc.name, tc.arguments)
                } else {
                    "инструмент не найден ни на одном MCP-сервере: ${tc.name}"
                }
                Log.d("AGENT", "mcp: [${serverOf[tc.name]}] call ${tc.name}(${tc.arguments}) -> ${toolResult.take(140)}")
                loopMessages = loopMessages + ChatMessage("tool", toolResult, toolCallId = tc.id)
            }
            result = callModel(loopMessages, key, json, tools)
            rounds++
        }
        // Модель могла упереться в лимит раундов и закончить tool-call-ом без текста —
        // последний запрос без tools, чтобы получить финальный ответ в чат.
        if (result.content.isBlank()) {
            result = callModel(loopMessages, key, json, null)
        }
        // Если модель так и не дала текстовый ответ — показываем хотя бы последний
        // результат инструмента, чтобы в чат не ушёл пустой пузырь.
        if (result.content.isBlank()) {
            val lastTool = loopMessages.lastOrNull { it.role == "tool" }?.content
            val fallback = lastTool?.takeIf { it.isNotBlank() }
                ?.let { "Инструменты отработали. Результат последнего вызова:\n$it" }
                ?: "Инструменты MCP отработали, но модель не дала текстовый ответ."
            result = result.copy(content = fallback)
        }
        onFinalMessages(loopMessages)
        return result
    }

    private fun dueRemindersText(raw: String): String? {
        val fired = runCatching { JSONObject(raw).optJSONArray("fired") }.getOrNull() ?: return null
        if (fired.length() == 0) return null
        val lines = buildList {
            for (i in 0 until fired.length()) {
                val r = fired.getJSONObject(i)
                add("- ${r.optString("text", "")} (${r.optString("due_at", "")})")
            }
        }
        return "Сработавшие напоминания:\n" + lines.joinToString("\n")
    }

    /** Проактивная доставка напоминаний (День 18): опрашивает все подключённые серверы и,
     *  если что-то сработало, добавляет блок в историю системным сообщением. Возвращает текст
     *  для UI. Никакого LLM-вызова — только check_due_reminders через переиспользуемые сессии. */
    suspend fun pollDueReminders(): String? {
        if (!mcpEnabled()) return null
        val endpoints = mcpEndpointsList()
        if (endpoints.isEmpty()) return null
        val delivered = StringBuilder()
        for (ep in endpoints) {
            try {
                val mcp = pollerClients[ep] ?: McpClient(ep).also { pollerClients[ep] = it }
                if (pollerTools[ep] == null) {
                    pollerTools[ep] = mcp.connectAndListTools().tools.map { it.name }.toSet()
                }
                if ("check_due_reminders" !in (pollerTools[ep] ?: emptySet())) continue
                dueRemindersText(mcp.callTool("check_due_reminders", "{}"))?.let {
                    if (delivered.isNotEmpty()) delivered.append("\n")
                    delivered.append(it)
                }
            } catch (e: Exception) {
                pollerClients.remove(ep)
                pollerTools.remove(ep)
                Log.d("AGENT", "mcp: poll [$ep] failed: ${e.message}")
            }
        }
        val text = delivered.toString().takeIf { it.isNotBlank() } ?: return null
        _history.add(ChatMessage("system", text))
        shortTermStore.save(_history)
        Log.d("AGENT", "mcp: delivered due reminders: ${text.take(120)}")
        return text
    }

    private fun McpTool.toChatTool(): ChatTool? {
        if (name.isBlank()) return null
        return ChatTool(
            name = name,
            description = description,
            parameters = runCatching { inputSchema?.let { JSONObject(it) } }.getOrNull()
        )
    }
}