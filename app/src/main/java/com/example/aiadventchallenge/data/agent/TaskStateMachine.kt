package com.example.aiadventchallenge.data.agent

import android.content.Context
import android.util.Log
import com.example.aiadventchallenge.data.ChatMessage
import com.example.aiadventchallenge.data.LlmClient
import org.json.JSONObject

/**
 * Формализованное состояние задачи (День 13) — конечный автомат:
 * этап (PLANNING → EXECUTION → VALIDATION → DONE), текущий шаг и ожидаемое действие.
 * Состояние хранится отдельно (скоуп на команду) и подмешивается в каждый запрос,
 * поэтому пауза на любом этапе и продолжение работают без повторных объяснений.
 */
enum class TaskStage { PLANNING, EXECUTION, VALIDATION, DONE }

data class TaskState(
    val stage: TaskStage = TaskStage.PLANNING,
    val step: Int = 1,
    val stepLabel: String = "",
    val expectedAction: String = ""
) {
    /** Блок, который уходит моделью как системное сообщение. */
    val text: String
        get() = buildString {
            append("Состояние задачи:\n")
            append("- Этап: ").append(stage.name).append("\n")
            append("- Шаг: ").append(step)
            stepLabel.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
            append("\n")
            expectedAction.takeIf { it.isNotBlank() }?.let {
                append("- Ожидаемое действие: ").append(it).append("\n")
            }
        }
}

/** Хранилище состояния задачи (на команду — как рабочий слой памяти). */
interface TaskStateStore {
    fun loadState(): TaskState
    fun saveState(state: TaskState)
    fun loadPaused(): Boolean
    fun savePaused(paused: Boolean)
}

/** SharedPreferences-реализация (файл agent_task_state, ключи state_<team>/paused_<team>). */
class PrefsTaskStateStore(
    context: Context,
    private val team: () -> String
) : TaskStateStore {

    private val prefs = context.getSharedPreferences("agent_task_state", Context.MODE_PRIVATE)

    override fun loadState(): TaskState {
        val raw = prefs.getString("state_${team()}", null) ?: return TaskState()
        return try {
            val o = JSONObject(raw)
            TaskState(
                stage = runCatching { TaskStage.valueOf(o.getString("stage")) }
                    .getOrDefault(TaskStage.PLANNING),
                step = o.optInt("step", 1),
                stepLabel = o.optString("stepLabel", ""),
                expectedAction = o.optString("expectedAction", "")
            )
        } catch (e: Exception) {
            TaskState()
        }
    }

    override fun saveState(state: TaskState) {
        prefs.edit()
            .putString(
                "state_${team()}",
                JSONObject()
                    .put("stage", state.stage.name)
                    .put("step", state.step)
                    .put("stepLabel", state.stepLabel)
                    .put("expectedAction", state.expectedAction)
                    .toString()
            )
            .apply()
    }

    override fun loadPaused(): Boolean = prefs.getBoolean("paused_${team()}", false)

    override fun savePaused(paused: Boolean) {
        prefs.edit().putBoolean("paused_${team()}", paused).apply()
    }
}

class TaskStateMachine(
    private val client: LlmClient,
    private val model: () -> String,
    private val store: TaskStateStore
) {

    private companion object {
        const val UPDATE_PROMPT =
            "Ты — менеджер состояния задачи. По последнему обмену сообщениями определи формальное состояние задачи.\n" +
                "Этапы: PLANNING (планирование), EXECUTION (выполнение), VALIDATION (проверка), DONE (задача завершена).\n" +
                "Верни ТОЛЬКО JSON (без markdown): " +
                "{\"stage\":\"EXECUTION\",\"step\":2,\"stepLabel\":\"пишем код\",\"expectedAction\":\"что ожидается от пользователя дальше\"}\n" +
                "- stage: текущий этап задачи\n" +
                "- step: номер текущего шага внутри этапа (целое)\n" +
                "- stepLabel: короткое название шага\n" +
                "- expectedAction: что пользователь должен сделать дальше\n" +
                "Переход на DONE — только если задача реально завершена."
        const val VERIFY_PROMPT =
            "Ты — верификатор результата задачи. Ниже контекст задачи (диалог) и проверяемый результат.\n" +
                "Оцени, соответствует ли результат требованиям задачи и готов ли он.\n" +
                "Верни ТОЛЬКО JSON (без markdown): {\"passed\":true|false,\"comments\":\"...\"}\n" +
                "- passed: true, если результат готов и соответствует задаче\n" +
                "- comments: конкретные замечания на доработку (если passed=false); при passed=true — пустая строка"
    }

    var state: TaskState = store.loadState()
        private set

    var paused: Boolean = store.loadPaused()
        private set

    /** Блок для инъекции: состояние + правило этапа + признак паузы. */
    val summaryText: String
        get() = buildString {
            append(state.text)
            if (state.stage == TaskStage.PLANNING) {
                append(
                    "\n- Правило этапа: на PLANNING по кодовым задачам не приводи конкретные " +
                        "примеры кода — только абстракции (план, шаги, архитектура), " +
                        "чтобы не распылять внимание.\n"
                )
            }
            if (paused) append("- Статус: задача на паузе\n")
        }

    fun pause() {
        paused = true
        store.savePaused(true)
        Log.d("AGENT", "task: paused ${state.stage}/${state.step}")
    }

    fun resume() {
        paused = false
        store.savePaused(false)
        Log.d("AGENT", "task: resumed ${state.stage}/${state.step}")
    }

    fun reset() {
        state = TaskState()
        paused = false
        store.saveState(state)
        store.savePaused(false)
        Log.d("AGENT", "task: reset")
    }

    /** Единственный LLM-вызов, который явно переводит автомат в новое состояние. */
    suspend fun updateState(key: String, userMessage: String, assistantReply: String) {
        if (paused) return
        val parsed = try {
            val result = client.completeChat(
                buildList {
                    add(ChatMessage("system", UPDATE_PROMPT))
                    add(
                        ChatMessage(
                            "user",
                            "Сообщение пользователя:\n$userMessage\n\nОтвет ассистента:\n$assistantReply"
                        )
                    )
                },
                key,
                model = model(),
                responseFormat = "json_object",
                temperature = 0.2
            )
            parseState(result.content)
        } catch (e: Exception) {
            Log.d("AGENT", "task: update failed: ${e.message}")
            null
        } ?: return
        if (parsed != state) {
            state = parsed
            store.saveState(parsed)
            Log.d(
                "AGENT",
                "task: -> ${parsed.stage} step=${parsed.step} " +
                    "label=\"${parsed.stepLabel}\" expected=\"${parsed.expectedAction}\""
            )
        }
    }

    /** Верификация результата на этапе VALIDATION: DONE или возврат в EXECUTION с замечаниями. */
    suspend fun verifyResult(
        key: String,
        context: List<ChatMessage>,
        resultText: String
    ): VerificationResult? {
        val verdict = try {
            val result = client.completeChat(
                listOf(
                    ChatMessage("system", VERIFY_PROMPT),
                    ChatMessage(
                        "user",
                        "Контекст задачи (диалог, последние 30 сообщений):\n" +
                            context.takeLast(30).joinToString("\n") { "${it.role}: ${it.content}" } +
                            "\n\nРезультат на проверку:\n$resultText"
                    )
                ),
                key,
                model = model(),
                responseFormat = "json_object",
                temperature = 0.2
            )
            parseVerdict(result.content)
        } catch (e: Exception) {
            Log.d("AGENT", "task: verify failed: ${e.message}")
            null
        } ?: return null

        if (verdict.passed) {
            // Пройдено → DONE.
            state = TaskState(
                stage = TaskStage.DONE,
                step = 1,
                stepLabel = "Задача завершена",
                expectedAction = ""
            )
            Log.d("AGENT", "task: verify passed -> DONE")
        } else {
            // Не пройдено → назад в EXECUTION с замечаниями на доработку.
            state = TaskState(
                stage = TaskStage.EXECUTION,
                step = state.step,
                stepLabel = "Доработка по замечаниям",
                expectedAction = verdict.comments.ifBlank { "Устранить замечания верификатора" }
            )
            Log.d("AGENT", "task: verify failed -> EXECUTION (${verdict.comments})")
        }
        store.saveState(state)
        return verdict
    }

    private fun parseState(raw: String): TaskState? = try {
        val clean = raw.trim().removePrefix("```json").removeSuffix("```").trim()
        val o = JSONObject(clean)
        val stage = runCatching { TaskStage.valueOf(o.getString("stage").uppercase()) }
            .getOrNull() ?: return null
        TaskState(
            stage = stage,
            step = o.optInt("step", state.step).coerceAtLeast(1),
            stepLabel = o.optString("stepLabel", ""),
            expectedAction = o.optString("expectedAction", "")
        )
    } catch (e: Exception) {
        null
    }
}

/** Итог верификации результата на этапе VALIDATION. */
data class VerificationResult(
    val passed: Boolean,
    val comments: String
)

private fun parseVerdict(raw: String): VerificationResult? = try {
    val clean = raw.trim().removePrefix("```json").removeSuffix("```").trim()
    val o = JSONObject(clean)
    VerificationResult(
        passed = o.optBoolean("passed", false),
        comments = o.optString("comments", "").trim()
    )
} catch (e: Exception) {
    null
}