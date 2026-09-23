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
    val source: String = "Kura",
    val tags: List<String> = emptyList(),
    val updatedAt: Long = timestamp,
    val active: Boolean = true
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
        val item = Memory(prefs.getLong("next_id", 1L), content,
            tags = MemorySelector.keywords(content).take(8))
        persist(current + item, item.id + 1)
        return item
    }

    @Synchronized fun correct(id: Long, text: String): Memory? {
        val content = text.trim().replace(Regex("\\s+"), " ")
        require(content.length in 3..240) { "El recuerdo debe tener entre 3 y 240 caracteres." }
        val current = read()
        val old = current.firstOrNull { it.id == id } ?: return null
        val changed = old.copy(content = content, tags = MemorySelector.keywords(content).take(8),
            updatedAt = System.currentTimeMillis(), active = true)
        persist(current.map { if (it.id == id) changed else it })
        return changed
    }

    @Synchronized fun forget(id: Long): Boolean {
        val current = read()
        if (current.none { it.id == id }) return false
        persist(current.filterNot { it.id == id })
        return true
    }

    @Synchronized fun relevantTo(message: String, previousUserMessages: List<String> = emptyList()): List<Memory> =
        MemorySelector.select(read(), message, previousUserMessages)

    private fun persist(items: List<Memory>, nextId: Long = prefs.getLong("next_id", 1L)) {
        val data = JSONArray()
        items.forEach { data.put(JSONObject().put("id", it.id).put("content", it.content)
            .put("category", it.category).put("importance", it.importance)
            .put("timestamp", it.timestamp).put("source", it.source)
            .put("tags", JSONArray(it.tags)).put("updatedAt", it.updatedAt).put("active", it.active)) }
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
            val tags = obj.optJSONArray("tags") ?: JSONArray()
            if (id <= 0 || content.isBlank()) null else Memory(id, content,
                obj.optString("category", "personal"), obj.optInt("importance", 1),
                obj.optLong("timestamp"), obj.optString("source", "Kura"),
                (0 until tags.length()).map { tags.optString(it) },
                obj.optLong("updatedAt", obj.optLong("timestamp")), obj.optBoolean("active", true))
        }
    }

    companion object {
        /** Only explicit commands change permanent memory. */
        fun command(message: String): MemoryCommand? {
            val text = message.trim().removePrefix("¿").replaceFirst(Regex("^aria\\s*[,.:]?\\s*", RegexOption.IGNORE_CASE), "")
            Regex("^recuerda\\s+(?:que\\s+|esto\\s*[:：]\\s*)(.+)$", RegexOption.IGNORE_CASE)
                .matchEntire(text)?.let { return MemoryCommand.Save(it.groupValues[1].trim()) }
            Regex("^olvida\\s+(?:el\\s+)?recuerdo\\s+#?(\\d+)\\s*[.!]?$", RegexOption.IGNORE_CASE)
                .matchEntire(text)?.let { return MemoryCommand.Delete(it.groupValues[1].toLongOrNull() ?: return null) }
            Regex("^corrige\\s+(?:el\\s+)?recuerdo\\s+#?(\\d+)\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE)
                .matchEntire(text)?.let { return MemoryCommand.Correct(it.groupValues[1].toLongOrNull() ?: return null,
                    it.groupValues[2].trim()) }
            if (text.matches(Regex("^(?:que|qué)\\s+recuerdas(?:\\s+de\\s+mi|\\s+de\\s+mí)?\\s*\\??$", RegexOption.IGNORE_CASE)))
                return MemoryCommand.ListAll
            return null
        }
    }
}

/** Recent user turns resolve follow-up references; the current turn has higher priority. */
internal object MemorySelector {
    private val ignored = setOf(
        "que", "como", "cual", "para", "con", "por", "una", "uno", "los", "las", "del",
        "esta", "este", "tengo", "sabes", "recuerdas", "aria", "kura", "hola", "bien",
        "eso", "esto", "ella", "ellos", "algo", "sobre", "porque", "cuando", "donde",
        "quien", "dime", "puedes", "quiero", "seria", "serian", "pero", "muy", "mas",
        "mensaje", "llama", "llamado", "llamada", "estas", "estoy", "estamos",
        "gusta", "gustan", "hacer", "hago", "hablar", "hablemos", "ayudar", "sentir"
    )

    fun keywords(text: String): Set<String> = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length in 3..32 && it !in ignored }.toSet()

    fun select(memories: List<Memory>, current: String, previous: List<String>): List<Memory> {
        val now = keywords(current)
        val recent = if (isFollowUp(current)) previous.takeLast(2).flatMap { keywords(it) }.toSet()
            else emptySet()
        if (now.isEmpty() && recent.isEmpty()) return emptyList()
        val active = memories.filter { it.active }
        val currentMatches = active.map { item ->
            val terms = item.tags.takeIf { it.isNotEmpty() }?.toSet() ?: keywords(item.content)
            item to terms.intersect(now).size
        }.filter { it.second > 0 }
        val matches = if (currentMatches.isNotEmpty()) currentMatches else active.map { item ->
            item to keywords(item.content).intersect(recent).size
        }.filter { it.second > 0 }
        return matches
            .sortedWith(compareByDescending<Pair<Memory, Int>> { it.second }
                .thenByDescending { it.first.importance }.thenByDescending { it.first.updatedAt })
            .take(5).map { it.first }
    }

    /** Referencias sin tema propio pueden retomar el turno anterior; saludos y temas nuevos no. */
    fun isFollowUp(message: String): Boolean {
        val text = Normalizer.normalize(message.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").trim()
        if (text.matches(Regex("^[¿?¡!\\s]*(?:hola|buenas|como estas|que tal|como te va)(?:\\s+aria)?[¿?¡!.\\s]*$"))) return false
        if (text.matches(Regex("^(?:si|no|vale|claro|exacto|eso mismo|por supuesto|ok|dale)[.!\\s]*$"))) return true
        if (text.matches(Regex("^[¿?¡!\\s]*(?:por que|y por que|como asi|que quieres decir|cuentame mas)[¿?¡!.\\s]*$"))) return true
        return Regex("\\b(?:eso|esto|esa|ese|ella|ellos|se llama|su nombre|lo anterior|y entonces|y despues|y tu|y que opinas|cuentame mas|volvamos|retomemos|de eso)\\b")
            .containsMatchIn(text)
    }
}

sealed class MemoryCommand {
    data class Save(val text: String) : MemoryCommand()
    data class Delete(val id: Long) : MemoryCommand()
    data class Correct(val id: Long, val text: String) : MemoryCommand()
    data object ListAll : MemoryCommand()
}
