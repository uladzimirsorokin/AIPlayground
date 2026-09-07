package com.example.aiadventchallenge.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.aiadventchallenge.data.KeyStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HomeViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val _hasKey = MutableStateFlow(KeyStorage.load(application) != null)
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    fun saveKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        KeyStorage.save(getApplication(), trimmed)
        _hasKey.value = true
    }
}