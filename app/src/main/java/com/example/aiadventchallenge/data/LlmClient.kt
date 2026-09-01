package com.example.aiadventchallenge.data

import com.example.aiadventchallenge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
        formatInstruction: String? = null
    ): String = withContext(Dispatchers.IO) {
        val url = URL(endpoint.ifBlank { "$baseUrl/v1/chat/completions" })
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
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
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: $text")
            }

            JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        } finally {
            connection.disconnect()
        }
    }
}