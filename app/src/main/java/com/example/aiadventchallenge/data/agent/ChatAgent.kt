package com.example.aiadventchallenge.data.agent

import android.util.Log
import com.example.aiadventchallenge.BuildConfig
import com.example.aiadventchallenge.data.ChatMessage
import com.example.aiadventchallenge.data.LlmClient

/**
 * Conversational agent with pluggable context-management strategies:
 *
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
    private val historyStore: HistoryStore,
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

    private var _history = mutableListOf<ChatMessage>().apply { addAll(historyStore.load()) }
    override val history: List<ChatMessage> get() = _history.toList()

    private var summary = historyStore.loadSummary()
    val summaryText: String get() = summary

    private var facts = historyStore.loadFacts()
    val factsText: String get() = facts

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
            "send: history=${_history.size}msgs chars=${_history.sumOf { it.content.length }} " +
                "summary=${summary.length} facts=${facts.length} branch=$activeBranchName " +
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
            facts = updateFacts(key, facts, userMessage)
            historyStore.saveFacts(facts)
        }

        val messages = buildList {
            systemPrompt().takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", it)) }
            when (strat) {
                ContextStrategy.SLIDING_WINDOW ->
                    _history.takeLast(window).forEach { add(it) }

                ContextStrategy.FACTS -> {
                    facts.takeIf { it.isNotBlank() }?.let {
                        add(ChatMessage("system", "Факты о диалоге:\n$it"))
                    }
                    _history.takeLast(window).forEach { add(it) }
                }

                ContextStrategy.BRANCHING ->
                    _history.forEach { add(it) }

                ContextStrategy.SUMMARY -> {
                    summary.takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", summary)) }
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
        historyStore.save(_history)
        historyStore.saveSummary(summary)
        Log.d(
            "AGENT",
            "done: model=${result.model} in=${result.promptTokens} out=${result.completionTokens} " +
                "total=${result.totalTokens} cost=${result.costUsd} history=${_history.size}msgs " +
                "summary=${summary.length} sentEst=${estimate(messages)} fullEst=${estimate(_history)} " +
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
        _history = mutableListOf()
        summary = ""
        facts = ""
        historyStore.clear()
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
        historyStore.save(_history)
    }

    fun switchBranch(name: String) {
        val target = branches[name] ?: return
        _history = target
        activeBranchName = name
        historyStore.save(_history)
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
            summary.takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", summary)) }
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
            summary
        }
        if (newSummary.isNotBlank()) summary = newSummary
        compactCount++
        Log.d("AGENT", "fold: folded=${old.size}msgs summary=${summary.length}")
        historyStore.saveSummary(summary)
    }

    private suspend fun ensureFits(key: String): Boolean {
        if (estimate(_history.takeLast(KEEP_RECENT)) + summary.length / 3 <= contextLimit) return false
        if (_history.size > KEEP_RECENT) foldIntoSummary(key, keep = KEEP_RECENT)
        return true
    }

    private fun estimate(messages: List<ChatMessage>): Int = messages.sumOf { it.content.length } / 3
}