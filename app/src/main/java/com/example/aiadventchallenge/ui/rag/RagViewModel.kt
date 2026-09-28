package com.example.aiadventchallenge.ui.rag

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiadventchallenge.data.mcp.McpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class RagSearchHit(
    val score: Double,
    val source: String,
    val section: String,
    val snippet: String
)

/**
 * RAG (День 21): построение локального индекса документов через MCP-инструменты
 * index_build / index_status / index_search (эмбеддинги — локальный ollama nomic-embed-text).
 */
class RagViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val prefs =
        getApplication<Application>().getSharedPreferences("rag", Context.MODE_PRIVATE)

    private val _endpoint = MutableStateFlow(
        prefs.getString("endpoint", null) ?: "http://10.0.2.2:8000/mcp"
    )
    val endpoint: StateFlow<String> = _endpoint.asStateFlow()

    private val _source = MutableStateFlow(prefs.getString("source", null) ?: "docs")
    val source: StateFlow<String> = _source.asStateFlow()

    private val _strategy = MutableStateFlow(prefs.getString("strategy", null) ?: "structure")
    val strategy: StateFlow<String> = _strategy.asStateFlow()

    private val _chunkSize = MutableStateFlow(prefs.getString("chunk_size", null) ?: "150")
    val chunkSize: StateFlow<String> = _chunkSize.asStateFlow()

    private val _overlap = MutableStateFlow(prefs.getString("overlap", null) ?: "30")
    val overlap: StateFlow<String> = _overlap.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    private val _indexes = MutableStateFlow<List<String>>(emptyList())
    val indexes: StateFlow<List<String>> = _indexes.asStateFlow()

    private val _hits = MutableStateFlow<List<RagSearchHit>>(emptyList())
    val hits: StateFlow<List<RagSearchHit>> = _hits.asStateFlow()

    private var mcp: McpClient? = null

    private suspend fun ensureMcp(): McpClient {
        val ep = _endpoint.value.trim()
        if (mcp == null || mcp?.endpoint != ep) {
            val c = McpClient(ep)
            c.connectAndListTools()
            mcp = c
        }
        return mcp!!
    }

    fun setEndpoint(value: String) {
        _endpoint.value = value
        prefs.edit().putString("endpoint", value).apply()
    }

    fun setSource(value: String) {
        _source.value = value.trim()
        prefs.edit().putString("source", value.trim()).apply()
    }

    fun setStrategy(value: String) {
        _strategy.value = value
        prefs.edit().putString("strategy", value).apply()
    }

    fun setChunkSize(value: String) {
        _chunkSize.value = value.filter { it.isDigit() }.take(4)
        prefs.edit().putString("chunk_size", _chunkSize.value).apply()
    }

    fun setOverlap(value: String) {
        _overlap.value = value.filter { it.isDigit() }.take(3)
        prefs.edit().putString("overlap", _overlap.value).apply()
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun buildIndex() {
        if (_busy.value) return
        _busy.value = true
        _message.value = ""
        viewModelScope.launch {
            try {
                val args = JSONObject()
                    .put("source", _source.value.ifBlank { "all" })
                    .put("strategy", _strategy.value)
                    .put("embedding", "ollama")
                    .put("chunk_size", _chunkSize.value.toIntOrNull() ?: 150)
                    .put("overlap", _overlap.value.toIntOrNull() ?: 30)
                    .toString()
                val raw = ensureMcp().callTool("index_build", args)
                val j = JSONObject(raw)
                _message.value = if (j.optBoolean("ok", false)) {
                    "Индекс сохранён:\n${j.optString("path")}\n" +
                        "${j.optInt("documents")} док · ${j.optInt("chunks")} чанков · " +
                        "${j.optString("embedding")} (dim ${j.optInt("dim")})"
                } else {
                    "Ошибка: ${j.optString("reason", raw)}"
                }
            } catch (e: Exception) {
                Log.e("RAG", "build failed", e)
                _message.value = "Ошибка индексации: ${e.message ?: "неизвестная"}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun loadStatus() {
        if (_busy.value) return
        _busy.value = true
        _message.value = ""
        viewModelScope.launch {
            try {
                val raw = ensureMcp().callTool("index_status", "{}")
                val arr = JSONArray(raw)
                _indexes.value = buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(
                            "${o.optString("file")}\n  " +
                                "source=${o.optString("source")} strategy=${o.optString("strategy")} " +
                                "· ${o.optInt("chunks")} чанков · ${o.optString("embedding")}"
                        )
                    }
                }
                if (arr.length() == 0) _indexes.value = listOf("Индексов пока нет.")
            } catch (e: Exception) {
                Log.e("RAG", "status failed", e)
                _message.value = "Ошибка: ${e.message ?: "неизвестная"}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun search() {
        if (_busy.value) return
        val q = _query.value.trim()
        if (q.isEmpty()) return
        _busy.value = true
        _message.value = ""
        _hits.value = emptyList()
        viewModelScope.launch {
            try {
                val args = JSONObject()
                    .put("query", q)
                    .put("source", _source.value.ifBlank { "all" })
                    .put("strategy", _strategy.value)
                    .toString()
                val raw = ensureMcp().callTool("index_search", args)
                val j = JSONObject(raw)
                if (!j.optBoolean("ok", false)) {
                    _message.value = j.optString("reason", raw)
                    return@launch
                }
                val results = j.optJSONArray("results") ?: JSONArray()
                _hits.value = buildList {
                    for (i in 0 until results.length()) {
                        val o = results.getJSONObject(i)
                        add(
                            RagSearchHit(
                                score = o.optDouble("score", 0.0),
                                source = o.optString("source", ""),
                                section = o.optString("section", ""),
                                snippet = o.optString("snippet", "")
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("RAG", "search failed", e)
                _message.value = "Ошибка поиска: ${e.message ?: "неизвестная"}"
            } finally {
                _busy.value = false
            }
        }
    }
}