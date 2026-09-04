package com.example.aiadventchallenge.ui.home

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiadventchallenge.BuildConfig
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
    data class ReasoningSuccess(
        val direct: String,
        val stepByStep: String,
        val composedPrompt: String,
        val promptComposed: String,
        val experts: String,
        val best: String
    ) : UiState
    data class TemperatureSuccess(
        val temp0: String,
        val temp07: String,
        val temp12: String
    ) : UiState
    data class ModelResult(
        val label: String,
        val model: String,
        val content: String,
        val timeMs: Long,
        val totalTokens: Int,
        val costUsd: Double
    )
    data class ModelSuccess(
        val weak: ModelResult,
        val medium: ModelResult,
        val strong: ModelResult
    ) : UiState
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

        const val STEP_BY_STEP =
            "Решай пошагово: распиши ход рассуждений и каждый шаг решения."

        const val PROMPT_COMPOSE =
            "Составь оптимальный промпт для решения следующей задачи. " +
                "Верни только текст готового промпта, без пояснений."

        const val EXPERTS =
            "Ты — группа из трёх экспертов: аналитик, инженер и критик. " +
                "Каждый предложи своё решение задачи отдельным блоком. " +
                "В конце критик сравни решения и укажи, какое точнее и почему."

        const val JUDGE_PROMPT =
            "Ниже — 4 решения одной и той же задачи, полученные разными способами. " +
                "Сравни их и определи лучшее. Кратко укажи: какое решение лучшее и почему, " +
                "и в чём его преимущество."
    }

    private data class ReasoningResults(
        val direct: String,
        val stepByStep: String,
        val composedPrompt: String,
        val promptAnswer: String,
        val experts: String
    )

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

    fun reasoning(prompt: String) {
        val task = prompt.trim()
        if (task.isEmpty()) return

        val apiKey = KeyStorage.load(getApplication())
        if (apiKey == null) {
            _uiState.value = UiState.Error("API key is not set")
            return
        }

        val system = systemPrompt.value
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            try {
                val results = coroutineScope {
                    val directD = async { client.complete(task, apiKey, systemPrompt = system) }
                    val stepD = async {
                        client.complete("$task\n\n$STEP_BY_STEP", apiKey, systemPrompt = system)
                    }
                    val expertsD = async {
                        client.complete("$task\n\n$EXPERTS", apiKey, systemPrompt = system)
                    }
                    val composedD = async {
                        val composedPrompt = client.complete(
                            "$PROMPT_COMPOSE\n\nЗадача:\n$task",
                            apiKey,
                            systemPrompt = system
                        )
                        composedPrompt to client.complete(composedPrompt, apiKey, systemPrompt = system)
                    }

                    val composed = composedD.await()
                    ReasoningResults(
                        direct = directD.await(),
                        stepByStep = stepD.await(),
                        composedPrompt = composed.first,
                        promptAnswer = composed.second,
                        experts = expertsD.await()
                    )
                }

                val best = client.complete(
                    "$JUDGE_PROMPT\n\n${buildResultsText(task, results)}",
                    apiKey,
                    systemPrompt = system
                )

                _uiState.value = UiState.ReasoningSuccess(
                    direct = results.direct,
                    stepByStep = results.stepByStep,
                    composedPrompt = results.composedPrompt,
                    promptComposed = results.promptAnswer,
                    experts = results.experts,
                    best = best
                )
            } catch (e: Exception) {
                Log.e("LLM", "Reasoning failed", e)
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun buildResultsText(task: String, r: ReasoningResults): String = buildString {
        append("\n\nЗадача:\n").append(task)
        append("\n\nРешение 1 (прямой ответ):\n").append(r.direct)
        append("\n\nРешение 2 (пошагово):\n").append(r.stepByStep)
        append("\n\nРешение 3 (составленный промпт):\n").append(r.promptAnswer)
        append("\n\nРешение 4 (группа экспертов):\n").append(r.experts)
    }

    fun temperature(prompt: String) {
        val task = prompt.trim()
        if (task.isEmpty()) return

        val apiKey = KeyStorage.load(getApplication())
        if (apiKey == null) {
            _uiState.value = UiState.Error("API key is not set")
            return
        }

        val system = systemPrompt.value
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            try {
                coroutineScope {
                    val t0 = async {
                        client.complete(task, apiKey, systemPrompt = system, temperature = 0.0)
                    }
                    val t07 = async {
                        client.complete(task, apiKey, systemPrompt = system, temperature = 0.7)
                    }
                    val t12 = async {
                        client.complete(task, apiKey, systemPrompt = system, temperature = 1.2)
                    }
                    _uiState.value = UiState.TemperatureSuccess(
                        temp0 = t0.await(),
                        temp07 = t07.await(),
                        temp12 = t12.await()
                    )
                }
            } catch (e: Exception) {
                Log.e("LLM", "Temperature failed", e)
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun models(prompt: String) {
        val task = prompt.trim()
        if (task.isEmpty()) return

        val apiKey = KeyStorage.load(getApplication())
        if (apiKey == null) {
            _uiState.value = UiState.Error("API key is not set")
            return
        }

        val system = systemPrompt.value
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            try {
                coroutineScope {
                    val weak = async {
                        val r = client.completeDetailed(
                            task, apiKey, model = BuildConfig.LLM_MODEL_WEAK, systemPrompt = system
                        )
                        UiState.ModelResult(
                            label = "Слабая", model = BuildConfig.LLM_MODEL_WEAK,
                            content = r.content, timeMs = r.latencyMs,
                            totalTokens = r.totalTokens, costUsd = r.costUsd
                        )
                    }
                    val medium = async {
                        val r = client.completeDetailed(
                            task, apiKey, model = BuildConfig.LLM_MODEL_MEDIUM, systemPrompt = system
                        )
                        UiState.ModelResult(
                            label = "Средняя", model = BuildConfig.LLM_MODEL_MEDIUM,
                            content = r.content, timeMs = r.latencyMs,
                            totalTokens = r.totalTokens, costUsd = r.costUsd
                        )
                    }
                    val strong = async {
                        val r = client.completeDetailed(
                            task, apiKey, model = BuildConfig.LLM_MODEL_STRONG, systemPrompt = system
                        )
                        UiState.ModelResult(
                            label = "Сильная", model = BuildConfig.LLM_MODEL_STRONG,
                            content = r.content, timeMs = r.latencyMs,
                            totalTokens = r.totalTokens, costUsd = r.costUsd
                        )
                    }
                    _uiState.value = UiState.ModelSuccess(
                        weak = weak.await(),
                        medium = medium.await(),
                        strong = strong.await()
                    )
                }
            } catch (e: Exception) {
                Log.e("LLM", "Models failed", e)
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }
}