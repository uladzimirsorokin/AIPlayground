package com.example.aiadventchallenge.data.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.aiadventchallenge.data.ChatMessage

/**
 * Persists the agent's conversation history in a local SQLite database,
 * so the dialogue survives app restarts.
 */
class DatabaseHistoryStore(context: Context) : HistoryStore {

    private val helper = HistoryDbHelper(context)

    override fun load(): List<ChatMessage> {
        val db = helper.readableDatabase
        val result = mutableListOf<ChatMessage>()
        db.query(TABLE, arrayOf(COL_ROLE, COL_CONTENT), null, null, null, null, "$COL_ID ASC")
            .use { cursor ->
                val roleIdx = cursor.getColumnIndexOrThrow(COL_ROLE)
                val contentIdx = cursor.getColumnIndexOrThrow(COL_CONTENT)
                while (cursor.moveToNext()) {
                    result.add(ChatMessage(cursor.getString(roleIdx), cursor.getString(contentIdx)))
                }
            }
        return result
    }

    override fun save(messages: List<ChatMessage>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE, null, null)
            messages.forEach { m ->
                db.insert(
                    TABLE,
                    null,
                    ContentValues().apply {
                        put(COL_ROLE, m.role)
                        put(COL_CONTENT, m.content)
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

    private class HistoryDbHelper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$COL_ROLE TEXT NOT NULL, " +
                    "$COL_CONTENT TEXT NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    private companion object {
        const val DB_NAME = "agent_history.db"
        const val DB_VERSION = 1
        const val TABLE = "messages"
        const val COL_ID = "id"
        const val COL_ROLE = "role"
        const val COL_CONTENT = "content"
    }
}