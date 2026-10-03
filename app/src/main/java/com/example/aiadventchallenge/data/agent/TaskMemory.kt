package com.example.aiadventchallenge.data.agent

import android.content.Context
import android.util.Log
import com.example.aiadventchallenge.data.ChatMessage
import com.example.aiadventchallenge.data.LlmClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Память задачи (День 25): что уточнил пользователь, какие ограничения/термины зафиксированы и
 * какова цель диалога. Обновляется отдельным LLM-вызовом после каждого ответа, подмешивается
 * в каждый запрос системным сообщением и хранится отдельно (скоуп на команду).
 */
data class TaskMemory(
    val goal: String = "",
    val clarified: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val terms: List<String> = emptyList()
) {
    val isEmpty: Boolean
        get() = goal.isBlank() && clarified.isEmpty() && constraints.isEmpty() && terms.isEmpty()

    /** Блок, который уходит моделью как системное сообщение. */
    val text: String
        get() = buildString {
            append("Память задачи:\n")
            append("- Цель диалога: ").append(goal.ifBlank { "—" }).append("\n")
            append("- Уточнено: ").append(listOrDash(clarified)).append("\n")
            append("- Ограничения: ").append(listOrDash(constraints)).append("\n")
            append("- Термины: ").append(listOrDash(terms)).append("\n")
        }

    private fun listOrDash(items: List<String>): String =
        if (items.isEmpty()) "—" else items.joinToString("; ")
}

interface TaskMemoryStore {
    fun load(): TaskMemory
    fun save(memory: TaskMemory)
    fun clear()
}

/** SharedPreferences-реализация (файл agent_task_memory, ключ memory_<team>). */
class PrefsTaskMemoryStore(
    context: Context,
    private val team: () -> String
) : TaskMemoryStore {

    private val prefs = context.getSharedPreferences("agent_task_memory", Context.MODE_PRIVATE)

    override fun load(): TaskMemory {
        val raw = prefs.getString("memory_${team()}", null) ?: return TaskMemory()
        return runCatching {
            val o = JSONObject(raw)
            TaskMemory(
                goal = o.optString("goal", ""),
                clarified = o.optJSONArray("clarified").toStringList(),
                constraints = o.optJSONArray("constraints").toStringList(),
                terms = o.optJSONArray("terms").toStringList()
            )
        }.getOrDefault(TaskMemory())
    }

    override fun save(memory: TaskMemory) {
        prefs.edit()
            .putString(
                "memory_${team()}",
                JSONObject()
                    .put("goal", memory.goal)
                    .put("clarified", JSONArray(memory.clarified))
                    .put("constraints", JSONArray(memory.constraints))
                    .put("terms", JSONArray(memory.terms))
                    .toString()
            )
            .apply()
    }

    override fun clear() {
        prefs.edit().remove("memory_${team()}").apply()
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
    }
}

/** Обновляет память задачи одним LLM-вызовом после каждого ответа и хранит её отдельно. */
class TaskMemoryManager(
    private val client: LlmClient,
    private val model: () -> String,
    private val store: TaskMemoryStore
) {

    private companion object {
        const val UPDATE_PROMPT =
            "Ты ведёшь ПАМЯТЬ ЗАДАЧИ диалога. По последнему обмену сообщениями обнови память.\n" +
                "Верни ТОЛЬКО JSON (без markdown): " +
                "{\"goal\":\"цель диалога\",\"clarified\":[\"что пользователь уже уточнил\"]," +
                "\"constraints\":[\"ограничения\"],\"terms\":[\"зафиксированные термины/названия\"]}\n" +
                "- Сохраняй уже накопленное, добавляй новое, убирай устаревшее, не выдумывай.\n" +
                "- goal — сформулированная цель всего диалога (кратко)."
    }

    var memory: TaskMemory = store.load()
        private set

    val text: String get() = memory.text
    val isEmpty: Boolean get() = memory.isEmpty

    /** Перечитать память из хранилища (например, при смене команды). */
    fun reload() {
        memory = store.load()
        Log.d("AGENT", "taskmem: reloaded (goal=\"${memory.goal}\")")
    }

    fun clear() {
        memory = TaskMemory()
        store.clear()
        Log.d("AGENT", "taskmem: cleared")
    }

    suspend fun update(key: String, userMessage: String, assistantReply: String) {
        val parsed = try {
            val result = client.completeChat(
                listOf(
                    ChatMessage("system", UPDATE_PROMPT),
                    ChatMessage(
                        "user",
                        "Текущая память: ${json(memory)}\n\n" +
                            "Пользователь: $userMessage\nАссистент: $assistantReply"
                    )
                ),
                key,
                model = model(),
                responseFormat = "json_object",
                temperature = 0.2
            )
            parse(result.content)
        } catch (e: Exception) {
            Log.d("AGENT", "taskmem: update failed: ${e.message}")
            null
        } ?: return

        memory = TaskMemory(
            goal = parsed.goal.ifBlank { memory.goal },
            clarified = parsed.clarified.ifEmpty { memory.clarified },
            constraints = parsed.constraints.ifEmpty { memory.constraints },
            terms = parsed.terms.ifEmpty { memory.terms }
        )
        store.save(memory)
        Log.d(
            "AGENT",
            "taskmem: goal=\"${memory.goal}\" clarified=${memory.clarified.size} " +
                "constraints=${memory.constraints.size} terms=${memory.terms.size}"
        )
    }

    private fun parse(raw: String): TaskMemory {
        val s = raw.trim().removePrefix("```json").removeSuffix("```").trim()
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        val obj = if (a in 0 until b) JSONObject(s.substring(a, b + 1)) else JSONObject(s)
        return TaskMemory(
            goal = obj.optString("goal", ""),
            clarified = obj.optJSONArray("clarified").toStringList(),
            constraints = obj.optJSONArray("constraints").toStringList(),
            terms = obj.optJSONArray("terms").toStringList()
        )
    }

    private fun json(m: TaskMemory): String = JSONObject()
        .put("goal", m.goal)
        .put("clarified", JSONArray(m.clarified))
        .put("constraints", JSONArray(m.constraints))
        .put("terms", JSONArray(m.terms))
        .toString()
}