package com.example.aiadventchallenge.data.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Отдельное хранилище долговременной памяти (профиль/решения/знания).
 * Держится отдельно от краткосрочной истории, чтобы слои памяти не смешивались.
 */
interface LongTermStore {
    fun load(): List<LongTermEntry>
    fun save(entries: List<LongTermEntry>)
    fun clear()
}

/** JSON-реализация в SharedPreferences (файл agent_longterm). */
class PrefsLongTermStore(context: Context) : LongTermStore {

    private val prefs = context.getSharedPreferences("agent_longterm", Context.MODE_PRIVATE)

    override fun load(): List<LongTermEntry> {
        val raw = prefs.getString("entries", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val cat = runCatching { LongTermCategory.valueOf(obj.getString("category")) }
                        .getOrNull() ?: continue
                    add(
                        LongTermEntry(
                            id = obj.optLong("id", 0),
                            category = cat,
                            content = obj.getString("content"),
                            createdAt = obj.optLong("created_at", 0),
                            updatedAt = obj.optLong("updated_at", 0)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun save(entries: List<LongTermEntry>) {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject()
                    .put("id", e.id)
                    .put("category", e.category.name)
                    .put("content", e.content)
                    .put("created_at", e.createdAt)
                    .put("updated_at", e.updatedAt)
            )
        }
        prefs.edit().putString("entries", array.toString()).apply()
    }

    override fun clear() {
        prefs.edit().remove("entries").apply()
    }
}

/** SQLite-реализация (отдельная таблица long_term в своём файле БД). */
class SqliteLongTermStore(context: Context) : LongTermStore {

    private val helper = LongTermDbHelper(context)

    override fun load(): List<LongTermEntry> {
        val db = helper.readableDatabase
        val result = mutableListOf<LongTermEntry>()
        db.query(TABLE, null, null, null, null, null, "$COL_UPDATED_AT DESC")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val cat = runCatching {
                        LongTermCategory.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(COL_CATEGORY)))
                    }.getOrNull() ?: continue
                    result.add(
                        LongTermEntry(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                            category = cat,
                            content = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT)),
                            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
                            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT))
                        )
                    )
                }
            }
        return result
    }

    override fun save(entries: List<LongTermEntry>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE, null, null)
            entries.forEach { e ->
                db.insert(
                    TABLE,
                    null,
                    ContentValues().apply {
                        put(COL_ID, e.id)
                        put(COL_CATEGORY, e.category.name)
                        put(COL_CONTENT, e.content)
                        put(COL_CREATED_AT, e.createdAt)
                        put(COL_UPDATED_AT, e.updatedAt)
                    }
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun clear() {
        helper.writableDatabase.delete(TABLE, null, null)
    }

    private class LongTermDbHelper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$COL_CATEGORY TEXT NOT NULL, " +
                    "$COL_CONTENT TEXT NOT NULL, " +
                    "$COL_CREATED_AT INTEGER NOT NULL, " +
                    "$COL_UPDATED_AT INTEGER NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    private companion object {
        const val DB_NAME = "agent_long_term.db"
        const val DB_VERSION = 1
        const val TABLE = "long_term"
        const val COL_ID = "id"
        const val COL_CATEGORY = "category"
        const val COL_CONTENT = "content"
        const val COL_CREATED_AT = "created_at"
        const val COL_UPDATED_AT = "updated_at"
    }
}