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
 *
 * День 15 — контролируемые переходы: смена этапа только по таблице [TASK_TRANSITIONS]
 * через единый шлюз [TaskStateMachine.requestTransition] с предусловиями.
 */
enum class TaskStage { PLANNING, EXECUTION, VALIDATION, DONE }

/** Таблица разрешённых переходов между этапами. Перепрыгнуть этап нельзя. */
val TASK_TRANSITIONS: Map<TaskStage, Set<TaskStage>> = mapOf(
    TaskStage.PLANNING to setOf(TaskStage.EXECUTION),
    TaskStage.EXECUTION to setOf(TaskStage.VALIDATION, TaskStage.PLANNING),
    TaskStage.VALIDATION to setOf(TaskStage.DONE, TaskStage.EXECUTION),
    TaskStage.DONE to emptySet()
)

/** Куда можно перейти из текущего этапа. */
fun TaskStage.allowedTransitions(): Set<TaskStage> = TASK_TRANSITIONS[this] ?: emptySet()

/** Итог попытки перехода: выполнен или отклонён с причиной и списком разрешённых целей. */
sealed class TransitionResult {
    data class Ok(val target: TaskStage) : TransitionResult()

    data class Rejected(
        val from: TaskStage,
        val target: TaskStage,
        val reason: String,
        val allowed: Set<TaskStage>
    ) : TransitionResult()
}

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
                "Смена этапа допускается только по цепочке PLANNING→EXECUTION→VALIDATION→DONE и только по одному шагу:\n" +
                "перепрыгивать этапы нельзя (EXECUTION→DONE или PLANNING→VALIDATION запрещены), DONE — только после VALIDATION.\n" +
                "Верни ТОЛЬКО JSON (без markdown): " +
                "{\"stage\":\"EXECUTION\",\"step\":2,\"stepLabel\":\"пишем код\",\"expectedAction\":\"что ожидается от пользователя дальше\"}\n" +
                "- stage: текущий этап задачи (допустимый по цепочке)\n" +
                "- step: номер текущего шага внутри этапа (целое)\n" +
                "- stepLabel: короткое название шага\n" +
                "- expectedAction: что пользователь должен сделать дальше\n" +
                "Переход на DONE — только если задача реально завершена после валидации."
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

    /** Блок для инъекции: состояние + разрешённые переходы + правило этапа + признак паузы. */
    val summaryText: String
        get() = buildString {
            append(state.text)
            val allowed = state.stage.allowedTransitions()
            append("- Разрешённые переходы: ")
                .append(
                    if (allowed.isEmpty()) "нет (задача завершена)"
                    else allowed.joinToString(", ") { it.name }
                )
                .append("\n")
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

    /** Единственный LLM-вызов, который предлагает новое состояние; применяется через шлюз переходов. */
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
        if (parsed == state) return

        val target = parsed.stage
        if (target == state.stage) {
            // Тот же этап — обновляем шаг/ожидание без смены этапа.
            state = parsed
            store.saveState(state)
            Log.d(
                "AGENT",
                "task: ${target} step=${parsed.step} label=\"${parsed.stepLabel}\" expected=\"${parsed.expectedAction}\""
            )
            return
        }

        when (val result = requestTransition(target)) {
            is TransitionResult.Ok -> {
                state = parsed
                store.saveState(state)
                Log.d(
                    "AGENT",
                    "task: -> ${target} step=${parsed.step} label=\"${parsed.stepLabel}\" expected=\"${parsed.expectedAction}\""
                )
            }
            is TransitionResult.Rejected -> {
                // Остаёмся на текущем этапе; фиксируем шаг/ожидание, чтобы агент знал, что делать дальше.
                state = parsed.copy(stage = state.stage)
                store.saveState(state)
                Log.d(
                    "AGENT",
                    "task: transition rejected ${result.from}->${result.target}: ${result.reason}"
                )
            }
        }
    }

    /** Единый шлюз смены этапа: таблица разрешённых переходов + предусловия этапа. */
    fun requestTransition(target: TaskStage): TransitionResult {
        val from = state.stage
        if (target == from) {
            return TransitionResult.Rejected(
                from, target,
                "Задача уже на этапе ${from.name}.",
                from.allowedTransitions()
            )
        }
        if (target !in from.allowedTransitions()) {
            return TransitionResult.Rejected(
                from, target,
                "Переход ${from.name} → ${target.name} запрещён таблицей переходов (нельзя перепрыгнуть этап).",
                from.allowedTransitions()
            )
        }
        preconditionError(from, target)?.let { reason ->
            return TransitionResult.Rejected(from, target, reason, from.allowedTransitions())
        }
        state = state.copy(stage = target)
        store.saveState(state)
        Log.d("AGENT", "task: transition ${from.name} -> ${target.name}")
        return TransitionResult.Ok(target)
    }

    /** Предусловия этапа: защита от «перепрыгивания» смысла, а не только таблицы. */
    private fun preconditionError(
        from: TaskStage,
        to: TaskStage
    ): String? {
        if (from == TaskStage.PLANNING && to == TaskStage.EXECUTION) {
            // Нельзя реализацию до утверждённого плана: план должен быть хотя бы описан (шаг с названием).
            // Проверку «незакрытых правок» по ключевым словам убрали: LLM пишет в expectedAction
            // «Подтвердить/правки» и в штатном потоке — она давала ложные срабатывания.
            if (state.stepLabel.isBlank()) {
                return "Нельзя приступать к реализации до утверждённого плана: план ещё не описан."
            }
        }
        if (to == TaskStage.DONE && from != TaskStage.VALIDATION) {
            return "Нельзя завершить задачу без валидации: финал доступен только с этапа VALIDATION."
        }
        return null
    }

    /** Верификация результата на этапе VALIDATION: DONE или возврат в EXECUTION с замечаниями. */
    suspend fun verifyResult(
        key: String,
        context: List<ChatMessage>,
        resultText: String
    ): VerificationResult? {
        val verdict = try {
            Log.d(
                "AGENT",
                "task: verify request context=${context.size}msgs result=${resultText.length}chars"
            )
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
            parseVerdict(result.content).also { v ->
                Log.d(
                    "AGENT",
                    "task: verify response passed=${v?.passed} comments=\"${v?.comments ?: "parse-fail"}\""
                )
            }
        } catch (e: Exception) {
            Log.d("AGENT", "task: verify failed: ${e.message}")
            null
        } ?: return null

        if (verdict.passed) {
            // Пройдено → DONE (валидация подтвердила; переход разрешён таблицей).
            requestTransition(TaskStage.DONE)
            state = TaskState(
                stage = TaskStage.DONE,
                step = 1,
                stepLabel = "Задача завершена",
                expectedAction = ""
            )
            store.saveState(state)
            Log.d("AGENT", "task: verify passed -> DONE")
        } else {
            // Не пройдено → назад в EXECUTION с замечаниями на доработку (переход разрешён таблицей).
            requestTransition(TaskStage.EXECUTION)
            state = TaskState(
                stage = TaskStage.EXECUTION,
                step = state.step,
                stepLabel = "Доработка по замечаниям",
                expectedAction = verdict.comments.ifBlank { "Устранить замечания верификатора" }
            )
            store.saveState(state)
            Log.d("AGENT", "task: verify failed -> EXECUTION (${verdict.comments})")
        }
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