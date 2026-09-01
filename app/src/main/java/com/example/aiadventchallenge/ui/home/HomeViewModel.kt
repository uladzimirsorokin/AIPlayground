package com.example.aiadventchallenge.ui.home

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiadventchallenge.data.KeyStorage
import com.example.aiadventchallenge.data.LlmClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed interface UiState {
    data object Idle : UiState
    data object Loading : UiState
    data class Success(val response: String) : UiState
    data class CompareSuccess(val plain: String, val constrained: String) : UiState
    data class Error(val message: String) : UiState
}

class HomeViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val client = LlmClient()

    private val prefs =
        getApplication<Application>().getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _systemPrompt = MutableStateFlow(
        prefs.getString("system_prompt", null)
            ?: "Ты — полезный и краткий ассистент. Отвечай по делу, без лишней воды."
    )
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    fun saveSystemPrompt(value: String) {
        prefs.edit().putString("system_prompt", value).apply()
        _systemPrompt.value = value
    }

    private val _stopSequences = MutableStateFlow(
        prefs.getString("stop_sequences", null)?.takeIf { it.isNotBlank() }
            ?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf("END")
    )
    val stopSequences: StateFlow<List<String>> = _stopSequences.asStateFlow()

    fun saveStopSequences(input: String) {
        val list = input.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("END") }
        prefs.edit().putString("stop_sequences", list.joinToString("|")).apply()
        _stopSequences.value = list
    }

    private companion object {
        const val FORMAT_DESCRIPTION =
            "Отвечай строго в JSON без markdown и пояснений, используй ключи: summary, facts."
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _hasKey = MutableStateFlow(KeyStorage.load(application) != null)
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    fun saveKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        KeyStorage.save(getApplication(), trimmed)
        _hasKey.value = true
    }

    fun send(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return

        val apiKey = KeyStorage.load(getApplication())
        if (apiKey == null) {
            _uiState.value = UiState.Error("API key is not set")
            return
        }

        _uiState.value = UiState.Loading
        viewModelScope.launch {
            try {
                val response = client.complete(trimmed, apiKey, systemPrompt = systemPrompt.value)
                Log.d("LLM", "Response: $response")
                _uiState.value = UiState.Success(response)
            } catch (e: Exception) {
                Log.e("LLM", "Request failed", e)
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun compare(prompt: String, maxTokens: Int, jsonFormat: Boolean) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return

        val apiKey = KeyStorage.load(getApplication())
        if (apiKey == null) {
            _uiState.value = UiState.Error("API key is not set")
            return
        }

        val constrainedFormat = if (jsonFormat) "json_object" else null
        val constrainedInstruction = if (jsonFormat) FORMAT_DESCRIPTION else null

        _uiState.value = UiState.Loading
        viewModelScope.launch {
            try {
                coroutineScope {
                    val plain = async { client.complete(trimmed, apiKey, systemPrompt = systemPrompt.value) }
                    val constrained = async {
                        client.complete(
                            prompt = trimmed,
                            apiKey = apiKey,
                            systemPrompt = systemPrompt.value,
                            maxTokens = maxTokens,
                            stop = stopSequences.value,
                            responseFormat = constrainedFormat,
                            formatInstruction = constrainedInstruction
                        )
                    }
                    val plainResponse = plain.await()
                    val constrainedResponse = constrained.await()

                    if (jsonFormat && !isValidJson(constrainedResponse)) {
                        _uiState.value = UiState.Error(
                            "Ответ не является валидным JSON (хотя тумблер JSON включён):\n\n$constrainedResponse"
                        )
                        return@coroutineScope
                    }

                    _uiState.value = UiState.CompareSuccess(plainResponse, constrainedResponse)
                }
            } catch (e: Exception) {
                Log.e("LLM", "Compare failed", e)
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun isValidJson(text: String): Boolean = try {
        JSONObject(text)
        true
    } catch (e: Exception) {
        false
    }
}