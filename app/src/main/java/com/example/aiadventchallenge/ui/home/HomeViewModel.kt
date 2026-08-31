package com.example.aiadventchallenge.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiadventchallenge.data.KeyStorage
import com.example.aiadventchallenge.data.LlmClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UiState {
    data object Idle : UiState
    data object Loading : UiState
    data class Success(val response: String) : UiState
    data class Error(val message: String) : UiState
}

class HomeViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val client = LlmClient()

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
                val response = client.complete(trimmed, apiKey)
                Log.d("LLM", "Response: $response")
                _uiState.value = UiState.Success(response)
            } catch (e: Exception) {
                Log.e("LLM", "Request failed", e)
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }
}