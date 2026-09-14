package com.example.aiadventchallenge.data.agent

import android.util.Log
import com.example.aiadventchallenge.BuildConfig
import com.example.aiadventchallenge.data.ChatMessage
import com.example.aiadventchallenge.data.LlmClient

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
    val contextLimit: Int = BuildConfig.LLM_CONTEXT_LIMIT
) : Agent {

    private companion object {
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
        const val KEEP_RECENT = 6
    }

    // Явная трёхслойная модель памяти.
    private val memory = AgentMemory(
        client = client,
        model = model,
        longTermEnabled = longTermEnabled,
        longTermStore = longTermStore
    )

    // Краткосрочный слой = активная ветка диалога; ветки — живые списки.
    private var _history: MutableList<ChatMessage>
        get() = memory.shortTerm
        set(value) {
            memory.shortTerm = value
        }

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
            // Долговременный слой подмешивается в каждый запрос независимо от стратегии.
            memory.longTermText.takeIf { it.isNotBlank() && longTermEnabled() }?.let {
                add(ChatMessage("system", "Долговременная память:\n$it"))
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

        val sentChars = messages.sumOf { it.content.length }
        savedTokens += (estimate(_history) - estimate(messages)).coerceAtLeast(0)

        val result = try {
            client.completeChat(
                messages,
                key,
                model = model(),
                responseFormat = if (json) "json_object" else null,
                temperature = temperature()
            )
        } catch (e: Exception) {
            _history.removeAt(_history.size - 1)
            throw e
        }
        val truncated = result.promptTokens > 0 &&
            result.promptTokens < (sentChars / 3.0) * 0.6

        _history.add(ChatMessage("assistant", result.content))
        shortTermStore.save(_history)
        workingStore.saveSummary(memory.summary)
        memory.consolidate(key, userMessage, result.content)
        Log.d(
            "AGENT",
            "done: model=${result.model} in=${result.promptTokens} out=${result.completionTokens} " +
                "total=${result.totalTokens} cost=${result.costUsd} history=${_history.size}msgs " +
                "summary=${memory.summary.length} layers=${memory.info} " +
                "sentEst=${estimate(messages)} fullEst=${estimate(_history)} " +
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
            reply = result.content,
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
}