package com.example.aiadventchallenge.data

import com.example.aiadventchallenge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class CompletionResult(
    val content: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val costUsd: Double,
    val latencyMs: Long
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
    ): String = completeCore(
        prompt, apiKey, model, systemPrompt, maxTokens, stop, responseFormat, formatInstruction, temperature
    ).content

    suspend fun completeDetailed(
        prompt: String,
        apiKey: String,
        model: String,
        systemPrompt: String? = null
    ): CompletionResult = completeCore(prompt, apiKey, model, systemPrompt, null, null, null, null, null)

    private suspend fun completeCore(
        prompt: String,
        apiKey: String,
        model: String,
        systemPrompt: String?,
        maxTokens: Int?,
        stop: List<String>?,
        responseFormat: String?,
        formatInstruction: String?,
        temperature: Double?
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

            val userContent = if (formatInstruction != null) "$prompt\n\n$formatInstruction" else prompt
            val messages = JSONArray().apply {
                systemPrompt?.takeIf { it.isNotBlank() }?.let {
                    put(JSONObject().put("role", "system").put("content", it))
                }
                put(JSONObject().put("role", "user").put("content", userContent))
            }
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
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
            val content = json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
            val usage = json.optJSONObject("usage")
            CompletionResult(
                content = content,
                promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
                completionTokens = usage?.optInt("completion_tokens", 0) ?: 0,
                totalTokens = usage?.optInt("total_tokens", 0) ?: 0,
                costUsd = usage?.optDouble("cost", 0.0) ?: 0.0,
                latencyMs = System.currentTimeMillis() - start
            )
        } finally {
            connection.disconnect()
        }
    }
}