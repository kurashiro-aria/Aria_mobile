package com.kura.aria.chat

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File

data class ChatMessage(
    val role: String,
    val text: String,
    val timestamp: Long
)

/**
 * Append-only local conversation history.
 * It is deliberately separate from the LLM context: storing messages does not feed
 * the whole history back into Qwen or consume its context window.
 */
class ChatHistory(context: Context) {
    private val file = File(context.filesDir, "history/chat.jsonl")
    private val database = HistoryDatabase(context.applicationContext)

    init { migrateLegacyOnce(context.applicationContext) }

    @Synchronized
    fun append(role: String, text: String, timestamp: Long = System.currentTimeMillis()) {
        require(role == "Kura" || role == "ARIA")
        require(text.isNotBlank())
        database.writableDatabase.insertOrThrow("messages", null, ContentValues().apply {
            put("role", role); put("text", text); put("timestamp", timestamp)
        })
    }

    @Synchronized
    fun readAll(): List<ChatMessage> {
        return database.readableDatabase.query("messages", arrayOf("role","text","timestamp"),
            null, null, null, null, "id ASC").use { cursor -> buildList {
            while (cursor.moveToNext()) add(ChatMessage(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
        } }
    }

    @Synchronized fun readRecent(limit: Int = 40): List<ChatMessage> = database.readableDatabase.rawQuery(
        "SELECT role,text,timestamp FROM messages ORDER BY id DESC LIMIT ?",
        arrayOf(limit.coerceIn(1, 500).toString())).use { cursor -> buildList {
        while (cursor.moveToNext()) add(ChatMessage(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
    }.asReversed() }

    @Synchronized fun count(): Long = database.readableDatabase.rawQuery("SELECT COUNT(*) FROM messages", null)
        .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    @Synchronized fun page(offset: Int, limit: Int = 500): List<ChatMessage> = database.readableDatabase.query(
        "messages", arrayOf("role","text","timestamp"), null, null, null, null, "id ASC",
        "${limit.coerceIn(1, 500)},${offset.coerceAtLeast(0)}").use { cursor -> buildList {
        while (cursor.moveToNext()) add(ChatMessage(cursor.getString(0),cursor.getString(1),cursor.getLong(2)))
    } }

    @Synchronized fun appendIfMissing(message: ChatMessage) {
        val exists = database.readableDatabase.rawQuery(
            "SELECT 1 FROM messages WHERE role=? AND text=? AND timestamp=? LIMIT 1",
            arrayOf(message.role,message.text,message.timestamp.toString())).use { it.moveToFirst() }
        if (!exists) append(message.role,message.text,message.timestamp)
    }

    private fun migrateLegacyOnce(context: Context) {
        val prefs = context.getSharedPreferences("aria_history_migration", Context.MODE_PRIVATE)
        if (prefs.getBoolean("jsonl_to_sqlite_v1", false)) return
        if (file.isFile && count() == 0L) file.useLines(Charsets.UTF_8) { lines ->
            database.writableDatabase.beginTransaction()
            try {
                lines.forEach { line -> runCatching { JSONObject(line) }.getOrNull()?.let { json ->
                    val role=json.optString("role"); val text=json.optString("text")
                    if ((role=="Kura"||role=="ARIA")&&text.isNotBlank()) append(role,text,json.optLong("timestamp",0L))
                } }
                database.writableDatabase.setTransactionSuccessful()
            } finally { database.writableDatabase.endTransaction() }
        }
        check(prefs.edit().putBoolean("jsonl_to_sqlite_v1", true).commit())
    }

    private class HistoryDatabase(context: Context) : SQLiteOpenHelper(context, "aria_history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL CHECK(role IN ('Kura','ARIA')), text TEXT NOT NULL, timestamp INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX idx_messages_timestamp ON messages(timestamp DESC)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
