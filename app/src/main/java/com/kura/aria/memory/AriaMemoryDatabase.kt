package com.kura.aria.memory

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

internal class AriaMemoryDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "aria_memory.db", null, SCHEMA_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE memories(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            type TEXT NOT NULL,
            content TEXT NOT NULL,
            normalized_content TEXT NOT NULL,
            normalized_key TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            last_used_at INTEGER NOT NULL DEFAULT 0,
            importance INTEGER NOT NULL DEFAULT 1,
            confidence REAL NOT NULL DEFAULT 1.0,
            source TEXT NOT NULL,
            tags TEXT NOT NULL DEFAULT '[]',
            entities TEXT NOT NULL DEFAULT '[]',
            conversation_id TEXT,
            schema_version INTEGER NOT NULL DEFAULT 1,
            archived INTEGER NOT NULL DEFAULT 0,
            access_count INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("CREATE INDEX idx_memory_type_active ON memories(type, archived)")
        db.execSQL("CREATE INDEX idx_memory_updated ON memories(updated_at DESC)")
        db.execSQL("CREATE INDEX idx_memory_normalized ON memories(normalized_content)")
        db.execSQL("CREATE INDEX idx_memory_key ON memories(normalized_key) WHERE normalized_key IS NOT NULL")
        db.execSQL("CREATE INDEX idx_memory_conversation ON memories(conversation_id) WHERE conversation_id IS NOT NULL")
        db.execSQL("CREATE VIRTUAL TABLE memory_fts USING fts4(content, tags, content='memories')")
        db.execSQL("CREATE TRIGGER memory_ai AFTER INSERT ON memories BEGIN INSERT INTO memory_fts(docid,content,tags) VALUES(new.id,new.content,new.tags); END")
        db.execSQL("CREATE TRIGGER memory_ad AFTER DELETE ON memories BEGIN DELETE FROM memory_fts WHERE docid=old.id; END")
        db.execSQL("CREATE TRIGGER memory_au AFTER UPDATE ON memories BEGIN DELETE FROM memory_fts WHERE docid=old.id; INSERT INTO memory_fts(docid,content,tags) VALUES(new.id,new.content,new.tags); END")
        db.execSQL("""CREATE TABLE memory_relations(
            from_memory_id INTEGER NOT NULL,
            to_memory_id INTEGER NOT NULL,
            relation TEXT NOT NULL,
            confidence REAL NOT NULL DEFAULT 1.0,
            PRIMARY KEY(from_memory_id,to_memory_id,relation),
            FOREIGN KEY(from_memory_id) REFERENCES memories(id) ON DELETE CASCADE,
            FOREIGN KEY(to_memory_id) REFERENCES memories(id) ON DELETE CASCADE
        )""")
        db.execSQL("""CREATE TABLE memory_summaries(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            content TEXT NOT NULL,
            source_count INTEGER NOT NULL,
            range_start INTEGER NOT NULL,
            range_end INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            schema_version INTEGER NOT NULL DEFAULT 1
        )""")
        db.execSQL("CREATE INDEX idx_summary_range ON memory_summaries(range_end DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future versions add forward-only migrations here. Never drop user memory.
        if (oldVersion < 2) db.execSQL("ALTER TABLE memories ADD COLUMN reserved TEXT")
    }

    fun insert(memory: Memory, normalizedKey: String?): Memory {
        val id = writableDatabase.insertOrThrow("memories", null, values(memory, normalizedKey, false))
        return memory.copy(id = id)
    }

    fun update(memory: Memory, normalizedKey: String?): Boolean = writableDatabase.update(
        "memories", values(memory, normalizedKey, true), "id=?", arrayOf(memory.id.toString())) > 0

    fun archive(id: Long): Boolean = writableDatabase.update("memories",
        ContentValues().apply { put("archived", 1); put("updated_at", System.currentTimeMillis()) },
        "id=?", arrayOf(id.toString())) > 0

    fun findById(id: Long): Memory? = readableDatabase.query("memories", null, "id=?",
        arrayOf(id.toString()), null, null, null, "1").use { if (it.moveToFirst()) it.memory() else null }

    fun findDuplicate(normalizedKey: String?, normalizedContent: String): Memory? {
        if (normalizedKey != null) readableDatabase.query("memories", null,
            "normalized_key=? AND archived=0", arrayOf(normalizedKey), null, null,
            "updated_at DESC", "1").use { if (it.moveToFirst()) return it.memory() }
        return readableDatabase.query("memories", null, "normalized_content=? AND archived=0", arrayOf(normalizedContent),
            null, null, "updated_at DESC", "1").use { if (it.moveToFirst()) it.memory() else null }
    }

    fun candidates(terms: Set<String>, limit: Int = MemoryRetrievalPolicy.MAX_CANDIDATES): List<Memory> {
        if (terms.isEmpty()) return emptyList()
        val query = terms.take(MemoryRetrievalPolicy.MAX_QUERY_TERMS)
            .joinToString(" OR ") { "\"${it.replace("\"", "") }\"" }
        return readableDatabase.rawQuery("""SELECT m.* FROM memory_fts f
            JOIN memories m ON m.id=f.docid WHERE memory_fts MATCH ? AND m.archived=0
            ORDER BY m.importance DESC,m.updated_at DESC LIMIT ?""",
            arrayOf(query, MemoryRetrievalPolicy.clampCandidateLimit(limit).toString())).use { cursor -> cursor.toMemories() }
    }

    fun activeByTypes(types: Set<String>, limit: Int = 100): List<Memory> {
        if (types.isEmpty()) return emptyList()
        val marks = types.joinToString(",") { "?" }
        return readableDatabase.query("memories", null, "archived=0 AND type IN ($marks)",
            types.toTypedArray(), null, null, "updated_at DESC", limit.toString()).use { it.toMemories() }
    }

    fun page(offset: Int, limit: Int): List<Memory> = readableDatabase.query("memories", null,
        "archived=0", null, null, null, "id ASC", "${limit.coerceIn(1, 500)},${offset.coerceAtLeast(0)}")
        .use { it.toMemories() }

    fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM memories WHERE archived=0", null)
        .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun markUsed(ids: List<Long>, now: Long) {
        if (ids.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            ids.forEach { id -> writableDatabase.execSQL(
                "UPDATE memories SET last_used_at=?,access_count=MIN(access_count+1,1000000) WHERE id=?",
                arrayOf(now, id)) }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun addSummary(content: String, sourceCount: Int, start: Long, end: Long) {
        writableDatabase.insertOrThrow("memory_summaries", null, ContentValues().apply {
            put("content", content); put("source_count", sourceCount); put("range_start", start)
            put("range_end", end); put("created_at", System.currentTimeMillis()); put("schema_version", 1)
        })
    }

    private fun values(memory: Memory, normalizedKey: String?, update: Boolean) = ContentValues().apply {
        put("type", memory.category); put("content", memory.content)
        put("normalized_content", MemoryFacts.normalize(memory.content)); put("normalized_key", normalizedKey)
        if (!update) put("created_at", memory.timestamp)
        put("updated_at", memory.updatedAt); put("last_used_at", memory.lastUsed)
        put("importance", memory.importance); put("confidence", memory.confidence.toDouble())
        put("source", memory.source); put("tags", JSONArray(memory.tags).toString())
        put("entities", JSONArray(memory.entities).toString()); put("conversation_id", memory.conversationId)
        put("schema_version", memory.schemaVersion); put("archived", if (memory.active) 0 else 1)
        put("access_count", memory.accessCount)
    }

    private fun Cursor.memory() = Memory(getLong(getColumnIndexOrThrow("id")),
        getString(getColumnIndexOrThrow("content")), getString(getColumnIndexOrThrow("type")),
        getInt(getColumnIndexOrThrow("importance")), getLong(getColumnIndexOrThrow("created_at")),
        getString(getColumnIndexOrThrow("source")), jsonList(getString(getColumnIndexOrThrow("tags"))),
        getLong(getColumnIndexOrThrow("updated_at")), getInt(getColumnIndexOrThrow("archived")) == 0,
        getLong(getColumnIndexOrThrow("last_used_at")), getInt(getColumnIndexOrThrow("access_count")),
        getFloat(getColumnIndexOrThrow("confidence")), jsonList(getString(getColumnIndexOrThrow("entities"))),
        getString(getColumnIndexOrThrow("conversation_id")), getInt(getColumnIndexOrThrow("schema_version")))

    private fun Cursor.toMemories(): List<Memory> = buildList { while (moveToNext()) add(memory()) }
    private fun jsonList(raw: String): List<String> = runCatching { JSONArray(raw).let { a ->
        (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } } }.getOrDefault(emptyList())

    companion object { const val SCHEMA_VERSION = 1 }
}
