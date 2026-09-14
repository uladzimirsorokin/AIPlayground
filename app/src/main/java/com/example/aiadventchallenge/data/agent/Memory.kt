package com.example.aiadventchallenge.data.agent

import android.util.Log
import com.example.aiadventchallenge.data.ChatMessage
import com.example.aiadventchallenge.data.LlmClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit three-layer memory model (День 11).
 *
 * - SHORT_TERM (краткосрочная): текущий диалог — сообщения, которыми агент обменивается
 *   с пользователем. Летучая: окно задаётся стратегией контекста (Sliding Window / Summary),
 *   часть отбрасывается или сворачивается.
 * - WORKING (рабочая): данные текущей задачи — роллинг-summary и блок «факты»,
 *   которые пересобираются по мере развития диалога. Не переживают завершение задачи.
 * - LONG_TERM (долговременная): профиль пользователя, принятые решения и устойчивые знания.
 *   Извлекается явным LLM-вызовом и хранится отдельно — переживает перезапуск приложения.
 *
 * Слои хранятся раздельно:
 *  - краткосрочная  -> HistoryStore (SQLite/JSON, таблица messages)
 *  - рабочая        -> summary/facts в SharedPreferences (agent_history)
 *  - долговременная -> LongTermStore (отдельный файл agent_longterm / таблица long_term)
 *
 * «Что и куда сохраняется» решается явно: только результат [consolidate] попадает в
 * долговременный слой; всё остальное остаётся в краткосрочной/рабочей памяти.
 */
enum class MemoryLayer { SHORT_TERM, WORKING, LONG_TERM }

/** Категории долговременной памяти. */
enum class LongTermCategory { PROFILE, DECISION, KNOWLEDGE }

data class LongTermEntry(
    val id: Long,
    val category: LongTermCategory,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long
)

class AgentMemory(
    private val client: LlmClient,
    private val model: () -> String,
    private val longTermEnabled: () -> Boolean,
    private val longTermStore: LongTermStore
) {

    private companion object {
        const val MAX_ENTRIES = 50
        const val EXTRACT_PROMPT =
            "Ты — модуль долговременной памяти агента. Из одного обмена сообщениями извлеки " +
                "устойчивые факты, которые пригодятся в будущих диалогах.\n" +
                "Категории:\n" +
                "- PROFILE: постоянные данные о пользователе (роль, предпочтения, ограничения, цели)\n" +
                "- DECISION: решения и договорённости, достигнутые в этом обмене\n" +
                "- KNOWLEDGE: устойчивые знания и выводы, которые стоит запомнить\n" +
                "Верни только JSON-массив (без markdown и пояснений) вида: " +
                "[{\"category\":\"PROFILE\",\"content\":\"...\"},{\"category\":\"DECISION\",\"content\":\"...\"}].\n" +
                "Только новые или существенно изменившиеся факты. Если извлекать нечего — верни []."
    }

    /** Краткосрочная память — текущий диалог (активная ветка). */
    var shortTerm: MutableList<ChatMessage> = mutableListOf()

    /** Рабочая память — данные текущей задачи. */
    var summary: String = ""
    var facts: String = ""

    /** Долговременная память — профиль, решения, знания. */
    val longTerm: MutableList<LongTermEntry> = longTermStore.load().toMutableList()

    val longTermCount: Int get() = longTerm.size

    val longTermText: String
        get() = if (longTerm.isEmpty()) "" else longTerm.joinToString("\n") {
            "- [${it.category.name.lowercase()}] ${it.content}"
        }

    /** Краткое описание слоёв для логов и UI. */
    val info: String
        get() = "short=${shortTerm.size}msgs working=${summary.length + facts.length}chars " +
            "long=${longTerm.size}entries"

    /**
     * Явное продвижение знаний в долговременный слой: единственный LLM-вызов решает,
     * какие факты (профиль/решения/знания) стоят того, чтобы их запомнить.
     * Всё, что вернулось как JSON, попадает в LONG_TERM; остальное остаётся в других слоях.
     */
    suspend fun consolidate(key: String, userMessage: String, assistantReply: String) {
        if (!longTermEnabled()) {
            Log.d("AGENT", "memory: consolidate skipped (long-term disabled)")
            return
        }
        val candidates = extractCandidates(key, userMessage, assistantReply)
        if (candidates == null) {
            Log.d("AGENT", "memory: consolidate failed, longTerm=${longTerm.size} entries")
            return
        }
        if (candidates.isEmpty()) {
            Log.d("AGENT", "memory: consolidate ran, nothing extracted (longTerm=${longTerm.size} entries)")
            return
        }
        val now = System.currentTimeMillis()
        candidates.forEach { c ->
            val idx = longTerm.indexOfFirst { it.category == c.category && it.content == c.content }
            if (idx >= 0) {
                longTerm[idx] = longTerm[idx].copy(updatedAt = now)
            } else {
                longTerm.add(LongTermEntry(id = System.nanoTime(), category = c.category, content = c.content, createdAt = now, updatedAt = now))
            }
        }
        evictExcess()
        longTermStore.save(longTerm)
        Log.d("AGENT", "memory: consolidated +${candidates.size} longTerm=${longTerm.size} entries")
    }

    /** Принудительное добавление записи в долговременный слой (вручную, без LLM-вызова). */
    fun addManual(category: LongTermCategory, content: String) {
        if (content.isBlank()) return
        val now = System.currentTimeMillis()
        val trimmed = content.trim()
        val idx = longTerm.indexOfFirst { it.category == category && it.content == trimmed }
        if (idx >= 0) {
            longTerm[idx] = longTerm[idx].copy(updatedAt = now)
        } else {
            longTerm.add(LongTermEntry(id = System.nanoTime(), category = category, content = trimmed, createdAt = now, updatedAt = now))
        }
        evictExcess()
        longTermStore.save(longTerm)
        Log.d("AGENT", "memory: manual add [${category}] longTerm=${longTerm.size} entries")
    }

    /** Удаление записи из долговременного слоя по id. */
    fun remove(id: Long) {
        val idx = longTerm.indexOfFirst { it.id == id }
        if (idx >= 0) {
            longTerm.removeAt(idx)
            longTermStore.save(longTerm)
            Log.d("AGENT", "memory: removed id=$id longTerm=${longTerm.size} entries")
        }
    }

    /** Полная очистка долговременного слоя. */
    fun clearLongTerm() {
        longTerm.clear()
        longTermStore.clear()
        Log.d("AGENT", "memory: long-term cleared")
    }

    private fun evictExcess() {
        while (longTerm.size > MAX_ENTRIES) {
            val oldest = longTerm.minByOrNull { it.updatedAt } ?: break
            longTerm.remove(oldest)
        }
    }

    private suspend fun extractCandidates(
        key: String,
        userMessage: String,
        assistantReply: String
    ): List<LongTermEntry>? = try {
        val result = client.completeChat(
            buildList {
                add(ChatMessage("system", EXTRACT_PROMPT))
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
        parseCandidates(result.content)
    } catch (e: Exception) {
        Log.d("AGENT", "memory: extract failed: ${e.message}")
        null
    }

    private fun parseCandidates(raw: String): List<LongTermEntry> = try {
        val clean = raw.trim().removePrefix("```json").removeSuffix("```").trim()
        val array = JSONArray(clean)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val cat = runCatching { LongTermCategory.valueOf(obj.getString("category").uppercase()) }
                    .getOrNull() ?: continue
                val content = obj.getString("content").trim()
                if (content.isNotEmpty()) add(LongTermEntry(0, cat, content, 0, 0))
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun clear() {
        shortTerm = mutableListOf()
        summary = ""
        facts = ""
        longTerm.clear()
        longTermStore.clear()
    }
}