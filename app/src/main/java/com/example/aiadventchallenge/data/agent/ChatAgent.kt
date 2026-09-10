package com.example.aiadventchallenge.data.agent

import android.util.Log
import com.example.aiadventchallenge.BuildConfig
import com.example.aiadventchallenge.data.ChatMessage
import com.example.aiadventchallenge.data.LlmClient

/**
 * Conversational agent with dialogue memory and history compression.
 *
 * The FULL history is always kept in storage. When compression is enabled,
 * only the last N messages are sent verbatim, older messages are folded into
 * a rolling summary (an extra LLM call) which is sent instead of the full
 * history — so tokens stay bounded. With compression disabled the whole
 * history is sent, so nothing is ever lost.
 */
class ChatAgent(
    private val client: LlmClient,
    private val systemPrompt: () -> String,
    private val apiKey: () -> String?,
    private val model: () -> String,
    private val temperature: () -> Double?,
    private val jsonFormat: () -> Boolean,
    private val compactContext: () -> Boolean,
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
        const val KEEP_RECENT = 6
    }

    private val _history = mutableListOf<ChatMessage>().apply { addAll(historyStore.load()) }
    override val history: List<ChatMessage> get() = _history.toList()

    private var summary = historyStore.loadSummary()
    val summaryText: String get() = summary

    val historyEstimateTokens: Int
        get() = if (compactContext()) {
            estimate(_history.takeLast(historyWindow())) + summary.length / 3
        } else {
            estimate(_history)
        }

    private var stats = AgentStats()
    private var compactCount = 0
    private var savedTokens = 0L

    override suspend fun send(userMessage: String): AgentResponse {
        val key = apiKey() ?: throw IllegalStateException("API key is not set")
        val compactOn = compactContext()
        val window = historyWindow()
        Log.d(
            "AGENT",
            "send: history=${_history.size}msgs chars=${_history.sumOf { it.content.length }} " +
                "summary=${summary.length} model=${model()} compactOn=$compactOn window=$window limit=$contextLimit"
        )

        var compacted = false
        if (compactOn) {
            if (_history.size > window + window) {
                foldIntoSummary(key, keep = window)
                compacted = true
            }
            if (ensureFits(key)) compacted = true
        }

        val json = jsonFormat()
        val content = if (json) "$userMessage\n\n$FORMAT_DESCRIPTION" else userMessage
        _history.add(ChatMessage("user", content))

        val recentCount = if (compactOn) {
            var n = window
            while (n > 1 && estimate(_history.takeLast(n)) + summary.length / 3 > contextLimit) n--
            n
        } else {
            _history.size
        }

        val messages = buildList {
            systemPrompt().takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", it)) }
            if (compactOn) {
                summary.takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", summary)) }
                _history.takeLast(recentCount).forEach { add(it) }
            } else {
                _history.forEach { add(it) }
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
                "history=${_history.size}msgs summary=${summary.length} compacted=$compacted"
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
        _history.clear()
        summary = ""
        historyStore.clear()
        stats = AgentStats()
        compactCount = 0
        savedTokens = 0L
    }

    /**
     * Folds all messages beyond the last [keep] into the rolling summary.
     * Messages stay in the full history; only the summary is updated.
     */
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

    /**
     * If the summary plus the last few messages would exceed the limit,
     * fold aggressively. The request builder reduces the window further if needed.
     */
    private suspend fun ensureFits(key: String): Boolean {
        if (estimate(_history.takeLast(KEEP_RECENT)) + summary.length / 3 <= contextLimit) return false
        if (_history.size > KEEP_RECENT) foldIntoSummary(key, keep = KEEP_RECENT)
        return true
    }

    private fun estimate(messages: List<ChatMessage>): Int = messages.sumOf { it.content.length } / 3
}