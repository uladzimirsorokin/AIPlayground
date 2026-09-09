package com.example.aiadventchallenge.data.agent

import android.util.Log
import com.example.aiadventchallenge.BuildConfig
import com.example.aiadventchallenge.data.ChatMessage
import com.example.aiadventchallenge.data.LlmClient

/**
 * Simple conversational agent with dialogue memory.
 * Encapsulates the full request/response cycle: keeps conversation history,
 * sends it to the LLM and appends the assistant's reply back to the history.
 * Tracks token usage (per request and cumulative) and guards the model's context limit:
 * on overflow the older history is compressed into a summary instead of erroring.
 */
class ChatAgent(
    private val client: LlmClient,
    private val systemPrompt: () -> String,
    private val apiKey: () -> String?,
    private val model: () -> String,
    private val temperature: () -> Double?,
    private val jsonFormat: () -> Boolean,
    private val compactContext: () -> Boolean,
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

    val historyEstimateTokens: Int get() = _history.sumOf { it.content.length } / 3

    private var stats = AgentStats()

    override suspend fun send(userMessage: String): AgentResponse {
        val key = apiKey() ?: throw IllegalStateException("API key is not set")
        Log.d(
            "AGENT",
            "send: history=${_history.size}msgs chars=${_history.sumOf { it.content.length }} " +
                "model=${model()} compactOn=${compactContext()} limit=$contextLimit"
        )

        val compacted = if (compactContext()) ensureFits(key, userMessage) else false

        val json = jsonFormat()
        val content = if (json) "$userMessage\n\n$FORMAT_DESCRIPTION" else userMessage
        _history.add(ChatMessage("user", content))

        val messages = buildList {
            systemPrompt().takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", it)) }
            addAll(_history)
        }
        val sentChars = messages.sumOf { it.content.length }
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
        Log.d(
            "AGENT",
            "done: model=${result.model} in=${result.promptTokens} out=${result.completionTokens} " +
                "history=${_history.size}msgs compacted=$compacted"
        )

        stats = AgentStats(
            requests = stats.requests + 1,
            inputTokens = stats.inputTokens + result.promptTokens,
            outputTokens = stats.outputTokens + result.completionTokens,
            totalTokens = stats.totalTokens + result.totalTokens,
            costUsd = stats.costUsd + result.costUsd
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
        historyStore.clear()
        stats = AgentStats()
    }

    /**
     * If the dialogue is about to exceed the context limit, compress the older
     * messages into a summary (or drop them) so the dialogue can continue.
     */
    private suspend fun ensureFits(key: String, pending: String): Boolean {
        if (estimatedTokens(_history) + pending.length / 3 <= contextLimit) return false

        val old = _history.dropLast(KEEP_RECENT)
        val recent = _history.takeLast(KEEP_RECENT)
        _history.clear()
        if (old.isNotEmpty()) {
            val summary = try {
                client.completeChat(
                    buildList {
                        add(ChatMessage("system", SUMMARY_PROMPT))
                        addAll(old)
                    },
                    key,
                    model = model()
                ).content.trim()
            } catch (e: Exception) {
                null
            }
            if (summary != null && summary.isNotEmpty()) {
                _history.add(ChatMessage("system", "Сжатый контекст предыдущего диалога: $summary"))
            }
        }
        _history.addAll(recent)

        while (estimatedTokens(_history) + pending.length / 3 > contextLimit && _history.isNotEmpty()) {
            _history.removeAt(0)
        }
        historyStore.save(_history)
        return true
    }

    private fun estimatedTokens(messages: List<ChatMessage>): Int =
        messages.sumOf { it.content.length } / 3
}