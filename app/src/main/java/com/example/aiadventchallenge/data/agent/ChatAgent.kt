package com.example.aiadventchallenge.data.agent

import com.example.aiadventchallenge.data.ChatMessage
import com.example.aiadventchallenge.data.LlmClient

/**
 * Simple conversational agent with dialogue memory.
 * Encapsulates the full request/response cycle: keeps conversation history,
 * sends it to the LLM and appends the assistant's reply back to the history.
 */
class ChatAgent(
    private val client: LlmClient,
    private val systemPrompt: () -> String,
    private val apiKey: () -> String?,
    private val model: () -> String,
    private val temperature: () -> Double?,
    private val jsonFormat: () -> Boolean,
    private val historyStore: HistoryStore
) : Agent {

    private companion object {
        const val FORMAT_DESCRIPTION =
            "Отвечай строго в JSON без markdown и пояснений."
    }

    private val _history = mutableListOf<ChatMessage>().apply { addAll(historyStore.load()) }
    override val history: List<ChatMessage> get() = _history.toList()

    override suspend fun send(userMessage: String): AgentResponse {
        val key = apiKey() ?: throw IllegalStateException("API key is not set")
        val json = jsonFormat()
        val content = if (json) "$userMessage\n\n$FORMAT_DESCRIPTION" else userMessage
        _history.add(ChatMessage("user", content))

        val messages = buildList {
            systemPrompt().takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", it)) }
            addAll(_history)
        }
        val result = client.completeChat(
            messages,
            key,
            model = model(),
            responseFormat = if (json) "json_object" else null,
            temperature = temperature()
        )

        _history.add(ChatMessage("assistant", result.content))
        historyStore.save(_history)
        return AgentResponse(reply = result.content, tokensUsed = result.totalTokens)
    }

    override fun clearHistory() {
        _history.clear()
        historyStore.clear()
    }
}