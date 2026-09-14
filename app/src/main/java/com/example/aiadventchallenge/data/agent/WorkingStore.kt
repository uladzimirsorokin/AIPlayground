package com.example.aiadventchallenge.data.agent

import android.content.Context

/**
 * Рабочая память (данные текущей задачи): роллинг-summary + блок фактов.
 * Это «team layer» — общая для всех агентов одной команды: один агент кладёт
 * резюме/факты, другой из той же команды их читает. Скоуп по id команды,
 * поэтому разные команды не смешивают рабочую память.
 */
interface WorkingStore {
    fun loadSummary(): String
    fun saveSummary(summary: String)
    fun loadFacts(): String
    fun saveFacts(facts: String)
    fun clear()
}

/**
 * JSON-реализация в SharedPreferences (файл agent_working, ключи summary_<team>/facts_<team>).
 * Команда читается лямбдой, чтобы смена команды в настройках сразу меняла скоуп.
 */
class PrefsWorkingStore(
    context: Context,
    private val team: () -> String
) : WorkingStore {

    private val prefs = context.getSharedPreferences("agent_working", Context.MODE_PRIVATE)

    override fun loadSummary(): String = prefs.getString("summary_${team()}", "") ?: ""

    override fun saveSummary(summary: String) {
        prefs.edit().putString("summary_${team()}", summary).apply()
    }

    override fun loadFacts(): String = prefs.getString("facts_${team()}", "") ?: ""

    override fun saveFacts(facts: String) {
        prefs.edit().putString("facts_${team()}", facts).apply()
    }

    override fun clear() {
        prefs.edit()
            .remove("summary_${team()}")
            .remove("facts_${team()}")
            .apply()
    }
}