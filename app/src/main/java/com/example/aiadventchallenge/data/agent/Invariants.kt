package com.example.aiadventchallenge.data.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Инварианты проекта (День 14) — правила, которые ассистент не имеет права нарушать:
 * выбранная архитектура, принятые технические решения, ограничения по стеку, бизнес-правила.
 * Хранятся отдельно от диалога (свой стор), подмешиваются в каждый запрос.
 */
enum class InvariantCategory { ARCHITECTURE, DECISIONS, STACK, BUSINESS }

data class Invariant(
    val id: Long,
    val category: InvariantCategory,
    val content: String
)

/** Отдельное хранилище инвариантов (глобальный скоуп, как профиль). */
interface InvariantsStore {
    fun load(): List<Invariant>
    fun save(invariants: List<Invariant>)
    fun clear()
}

/** SharedPreferences-реализация (файл agent_invariants, JSON-массив). */
class PrefsInvariantsStore(context: Context) : InvariantsStore {

    private val prefs = context.getSharedPreferences("agent_invariants", Context.MODE_PRIVATE)

    override fun load(): List<Invariant> {
        val raw = prefs.getString("invariants", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val cat = runCatching { InvariantCategory.valueOf(obj.getString("category")) }
                        .getOrNull() ?: continue
                    add(
                        Invariant(
                            id = obj.optLong("id", 0),
                            category = cat,
                            content = obj.getString("content")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun save(invariants: List<Invariant>) {
        val array = JSONArray()
        invariants.forEach { inv ->
            array.put(
                JSONObject()
                    .put("id", inv.id)
                    .put("category", inv.category.name)
                    .put("content", inv.content)
            )
        }
        prefs.edit().putString("invariants", array.toString()).apply()
    }

    override fun clear() {
        prefs.edit().remove("invariants").apply()
    }
}

/** Итог guard-проверки решения на соответствие инвариантам. */
data class GuardVerdict(
    val violates: Boolean,
    val category: String,
    val refusal: String
)