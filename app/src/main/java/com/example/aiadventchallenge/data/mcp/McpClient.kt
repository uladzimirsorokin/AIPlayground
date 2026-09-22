package com.example.aiadventchallenge.data.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Минимальный MCP-клиент (День 16) поверх Streamable HTTP транспорта.
 * Реализован на HttpURLConnection + org.json — без сторонних зависимостей,
 * как и весь проект. Умеет: установить соединение (initialize) и получить
 * список доступных инструментов (tools/list).
 */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: String?
)

data class McpConnection(
    val protocolVersion: String,
    val tools: List<McpTool>
)

class McpClient(private val endpoint: String) {

    private var sessionId: String? = null
    private var nextId = 0

    suspend fun connectAndListTools(): McpConnection = withContext(Dispatchers.IO) {
        val init = post(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", ++nextId)
                .put("method", "initialize")
                .put(
                    "params",
                    JSONObject()
                        .put("protocolVersion", "2025-06-18")
                        .put("capabilities", JSONObject())
                        .put("clientInfo", JSONObject().put("name", "aiadvent").put("version", "1.0.0"))
                )
        ) ?: throw IllegalStateException("initialize: пустой ответ")

        val protocolVersion = init.getJSONObject("result").optString("protocolVersion", "")

        // Нотификация initialized — ответ не ожидается.
        post(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/initialized")
                .put("params", JSONObject())
        )

        val toolsResp = post(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", ++nextId)
                .put("method", "tools/list")
                .put("params", JSONObject())
        ) ?: throw IllegalStateException("tools/list: пустой ответ")

        val tools = toolsResp.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
        McpConnection(
            protocolVersion = protocolVersion,
            tools = buildList {
                for (i in 0 until tools.length()) {
                    val tool = tools.getJSONObject(i)
                    add(
                        McpTool(
                            name = tool.optString("name", ""),
                            description = tool.optString("description", ""),
                            inputSchema = tool.optJSONObject("inputSchema")?.toString()
                        )
                    )
                }
            }
        )
    }

    /** Вызывает инструмент и возвращает текстовый результат. */
    suspend fun callTool(name: String, arguments: String): String = withContext(Dispatchers.IO) {
        val resp = post(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", ++nextId)
                .put("method", "tools/call")
                .put(
                    "params",
                    JSONObject()
                        .put("name", name)
                        .put("arguments", runCatching { JSONObject(arguments) }.getOrElse { JSONObject() })
                )
        ) ?: throw IllegalStateException("tools/call: пустой ответ")

        val content = resp.optJSONObject("result")?.optJSONArray("content") ?: JSONArray()
        buildString {
            for (i in 0 until content.length()) {
                val item = content.getJSONObject(i)
                item.optString("text", "").takeIf { it.isNotBlank() }?.let { append(it) }
                if (item.has("json")) append(item.get("json").toString())
            }
        }.ifBlank {
            val err = resp.optJSONObject("result")?.optJSONObject("isError") ?: return@withContext "ошибка инструмента"
            err.toString()
        }
    }

    private fun post(body: JSONObject): JSONObject? {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json, text/event-stream")
            sessionId?.takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty("Mcp-Session-Id", it)
            }
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code: $text")

            connection.getHeaderField("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let {
                sessionId = it
            }
            if (text.isBlank()) return null
            parseJson(text)
        } finally {
            connection.disconnect()
        }
    }

    /** Разбирает и plain JSON, и SSE (text/event-stream) вида "data: {…}". */
    private fun parseJson(text: String): JSONObject {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) {
            val dataLine = trimmed.lineSequence()
                .firstOrNull { it.trimStart().startsWith("data:") }
                ?.trimStart()?.removePrefix("data:")?.trim()
                ?: return JSONObject()
            return JSONObject(dataLine)
        }
        return JSONObject(trimmed)
    }
}