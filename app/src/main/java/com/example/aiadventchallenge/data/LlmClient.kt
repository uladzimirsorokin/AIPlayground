package com.example.aiadventchallenge.data

import com.example.aiadventchallenge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ChatMessage(
    val role: String,
    val content: String,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val model: String? = null,
    val compacted: Boolean = false,
    val truncated: Boolean = false,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null
)

/** Вызов функции, запрошенный моделью (function calling). */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

/** Определение инструмента для function calling (OpenAI-совместимый формат). */
data class ChatTool(
    val name: String,
    val description: String,
    val parameters: JSONObject?
)

data class CompletionResult(
    val content: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val costUsd: Double,
    val latencyMs: Long,
    val model: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val finishReason: String? = null
)

/**
 * Minimal OpenAI-compatible chat completions client.
 * Sends a POST to {baseUrl}/v1/chat/completions and returns the assistant message.
 */
class LlmClient(
    private val baseUrl: String = BuildConfig.LLM_BASE_URL,
    private val endpoint: String = BuildConfig.LLM_ENDPOINT,
    private val model: String = BuildConfig.LLM_MODEL
) {

    suspend fun complete(
        prompt: String,
        apiKey: String,
        systemPrompt: String? = null,
        maxTokens: Int? = null,
        stop: List<String>? = null,
        responseFormat: String? = null,
        formatInstruction: String? = null,
        temperature: Double? = null
    ): String {
        val userContent = if (formatInstruction != null) "$prompt\n\n$formatInstruction" else prompt
        val messages = buildList {
            systemPrompt?.takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", it)) }
            add(ChatMessage("user", userContent))
        }
        return postChat(messages, apiKey, model, maxTokens, stop, responseFormat, temperature, null).content
    }

    suspend fun completeDetailed(
        prompt: String,
        apiKey: String,
        model: String,
        systemPrompt: String? = null
    ): CompletionResult {
        val messages = buildList {
            systemPrompt?.takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", it)) }
            add(ChatMessage("user", prompt))
        }
        return postChat(messages, apiKey, model, null, null, null, null, null)
    }

    suspend fun completeChat(
        messages: List<ChatMessage>,
        apiKey: String,
        model: String = this.model,
        maxTokens: Int? = null,
        stop: List<String>? = null,
        responseFormat: String? = null,
        temperature: Double? = null,
        tools: List<ChatTool>? = null
    ): CompletionResult = postChat(
        messages, apiKey, model, maxTokens, stop, responseFormat, temperature, tools
    )

    private suspend fun postChat(
        messages: List<ChatMessage>,
        apiKey: String,
        model: String,
        maxTokens: Int?,
        stop: List<String>?,
        responseFormat: String?,
        temperature: Double?,
        tools: List<ChatTool>?
    ): CompletionResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val url = URL(endpoint.ifBlank { "$baseUrl/v1/chat/completions" })
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.doOutput = true

            val messagesArray = JSONArray()
            messages.forEach { m ->
                val obj = JSONObject().put("role", m.role).put("content", m.content)
                if (m.toolCalls.isNotEmpty()) {
                    obj.put(
                        "tool_calls",
                        JSONArray().apply {
                            m.toolCalls.forEach { tc ->
                                put(
                                    JSONObject()
                                        .put("id", tc.id)
                                        .put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject().put("name", tc.name).put("arguments", tc.arguments)
                                        )
                                )
                            }
                        }
                    )
                }
                m.toolCallId?.let { obj.put("tool_call_id", it) }
                messagesArray.put(obj)
            }
            val body = JSONObject()
                .put("model", model)
                .put("messages", messagesArray)
                .put(
                    "plugins",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "context-compression")
                            .put("enabled", false)
                    )
                )
            tools?.takeIf { it.isNotEmpty() }?.let { tools ->
                body.put(
                    "tools",
                    JSONArray().apply {
                        tools.forEach { t ->
                            put(
                                JSONObject()
                                    .put("type", "function")
                                    .put(
                                        "function",
                                        JSONObject()
                                            .put("name", t.name)
                                            .put("description", t.description)
                                            .put(
                                                "parameters",
                                                t.parameters ?: JSONObject().put("type", "object")
                                            )
                                    )
                            )
                        }
                    }
                )
            }
            maxTokens?.let { body.put("max_tokens", it) }
            stop?.let { body.put("stop", JSONArray().apply { it.forEach(::put) }) }
            responseFormat?.let { body.put("response_format", JSONObject().put("type", it)) }
            temperature?.let { body.put("temperature", it) }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: $text")
            }

            val json = JSONObject(text)
            val choice = json.getJSONArray("choices").getJSONObject(0)
            val message = choice.getJSONObject("message")
            val content = message.optString("content", "").trim()
            val toolCalls = message.optJSONArray("tool_calls")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        val tc = arr.getJSONObject(i)
                        val fn = tc.optJSONObject("function") ?: continue
                        add(
                            ToolCall(
                                id = tc.optString("id", ""),
                                name = fn.optString("name", ""),
                                arguments = fn.optString("arguments", "")
                            )
                        )
                    }
                }
            } ?: emptyList()
            val usage = json.optJSONObject("usage")
            CompletionResult(
                content = content,
                promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
                completionTokens = usage?.optInt("completion_tokens", 0) ?: 0,
                totalTokens = usage?.optInt("total_tokens", 0) ?: 0,
                costUsd = usage?.optDouble("cost", 0.0) ?: 0.0,
                latencyMs = System.currentTimeMillis() - start,
                model = json.optString("model", null)?.takeIf { it.isNotBlank() },
                toolCalls = toolCalls,
                finishReason = choice.optString("finish_reason", null)?.takeIf { it.isNotBlank() }
            )
        } finally {
            connection.disconnect()
        }
    }
}