package com.example.aiadventchallenge.ui.mcp

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiadventchallenge.BuildConfig
import com.example.aiadventchallenge.data.mcp.McpClient
import com.example.aiadventchallenge.data.mcp.McpConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class McpViewModel(
    application: Application
) : AndroidViewModel(application) {

    companion object {
        const val PUBLIC_ENDPOINT = "https://mcp-http-demo.arcade.dev/mcp"
        const val LOCAL_ENDPOINT = "http://10.0.2.2:8000/mcp"
        const val LOCAL_ENDPOINT2 = "http://10.0.2.2:8001/mcp"
    }

    private val prefs =
        getApplication<Application>().getSharedPreferences("mcp", Context.MODE_PRIVATE)

    private val _endpoint = MutableStateFlow(
        prefs.getString("endpoint", null)
            ?: BuildConfig.MCP_ENDPOINT.ifBlank { PUBLIC_ENDPOINT }
    )
    val endpoint: StateFlow<String> = _endpoint.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    private val _connection = MutableStateFlow<McpConnection?>(null)
    val connection: StateFlow<McpConnection?> = _connection.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun setEndpoint(value: String) {
        _endpoint.value = value
        prefs.edit().putString("endpoint", value).apply()
    }

    fun connect() {
        val url = _endpoint.value.trim()
        if (url.isEmpty() || _connecting.value) return
        _connecting.value = true
        _error.value = null
        _connection.value = null
        viewModelScope.launch {
            try {
                val connection = McpClient(url).connectAndListTools()
                _connection.value = connection
                Log.d("MCP", "connected: protocol=${connection.protocolVersion} tools=${connection.tools.size}")
            } catch (e: Exception) {
                Log.e("MCP", "connect failed", e)
                _error.value = e.message ?: "Ошибка подключения"
            } finally {
                _connecting.value = false
            }
        }
    }
}