package com.kura.aria.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

data class Memory(
    val id: Long,
    val content: String,
    val category: String = "personal",
    val importance: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = "Kura"
)

/** Explicit, private on-device memories. Conversation history remains separate. */
class AriaMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("aria_memories_v2", Context.MODE_PRIVATE)

    @Synchronized fun recallAll(): List<Memory> = read()

    @Synchronized fun remember(text: String): Memory {
        val content = text.trim().replace(Regex("\\s+"), " ")
        require(content.length in 3..240) { "El recuerdo debe tener entre 3 y 240 caracteres." }
        val current = read()
        require(current.size < 50) { "Llegué al límite de 50 recuerdos. Borra uno antes de añadir otro." }
        val item = Memory(prefs.getLong("next_id", 1L), content)
        persist(current + item, item.id + 1)
        return item
    }

    @Synchronized fun forget(id: Long): Boolean {
        val current = read()
        if (current.none { it.id == id }) return false
        persist(current.filterNot { it.id == id })
        return true
    }

    @Synchronized fun relevantTo(message: String): List<Memory> {
        val words = keywords(message)
        if (words.isEmpty()) return emptyList()
        return read().map { item -> item to keywords(item.content).intersect(words).size }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Memory, Int>> { it.second }.thenByDescending { it.first.timestamp })
            .take(3).map { it.first }
    }

    private fun persist(items: List<Memory>, nextId: Long = prefs.getLong("next_id", 1L)) {
        val data = JSONArray()
        items.forEach { data.put(JSONObject().put("id", it.id).put("content", it.content)
            .put("category", it.category).put("importance", it.importance)
            .put("timestamp", it.timestamp).put("source", it.source)) }
        check(prefs.edit().putString("items", data.toString()).putLong("next_id", nextId).commit()) {
            "No pude guardar la memoria local."
        }
    }

    private fun read(): List<Memory> {
        val data = try { JSONArray(prefs.getString("items", "[]")) }
            catch (e: Exception) { throw IllegalStateException("La memoria local no se pudo leer.", e) }
        return (0 until data.length()).mapNotNull { index ->
            val obj = data.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optLong("id")
            val content = obj.optString("content")
            if (id <= 0 || content.isBlank()) null else Memory(id, content,
                obj.optString("category", "personal"), obj.optInt("importance", 1),
                obj.optLong("timestamp"), obj.optString("source", "Kura"))
        }
    }

    companion object {
        private val ignored = setOf("que", "como", "cual", "para", "con", "por", "una", "uno", "los", "las", "del", "esta", "este", "tengo", "sabes", "recuerdas")

        private fun keywords(text: String): Set<String> = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in ignored }.toSet()

        /** Only explicit commands change permanent memory. */
        fun command(message: String): MemoryCommand? {
            val text = message.trim().removePrefix("¿").replaceFirst(Regex("^aria\\s*[,.:]?\\s*", RegexOption.IGNORE_CASE), "")
            Regex("^recuerda\\s+(?:que\\s+|esto\\s*[:：]\\s*)(.+)$", RegexOption.IGNORE_CASE)
                .matchEntire(text)?.let { return MemoryCommand.Save(it.groupValues[1].trim()) }
            Regex("^olvida\\s+(?:el\\s+)?recuerdo\\s+#?(\\d+)\\s*[.!]?$", RegexOption.IGNORE_CASE)
                .matchEntire(text)?.let { return MemoryCommand.Delete(it.groupValues[1].toLongOrNull() ?: return null) }
            if (text.matches(Regex("^(?:que|qué)\\s+recuerdas(?:\\s+de\\s+mi|\\s+de\\s+mí)?\\s*\\??$", RegexOption.IGNORE_CASE)))
                return MemoryCommand.ListAll
            return null
        }
    }
}

sealed class MemoryCommand {
    data class Save(val text: String) : MemoryCommand()
    data class Delete(val id: Long) : MemoryCommand()
    data object ListAll : MemoryCommand()
}
