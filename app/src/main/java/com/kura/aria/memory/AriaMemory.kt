package com.kura.aria.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import com.kura.aria.personality.RelationshipState
import com.kura.aria.personality.StylePreferences

data class Memory(
    val id: Long,
    val content: String,
    val category: String = "personal",
    val importance: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = "Kura",
    val tags: List<String> = emptyList(),
    val updatedAt: Long = timestamp,
    val active: Boolean = true,
    val lastUsed: Long = 0L,
    val accessCount: Int = 0
)

/** Explicit, private on-device memories. Conversation history remains separate. */
class AriaMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("aria_memories_v2", Context.MODE_PRIVATE)

    @Synchronized fun recallAll(): List<Memory> = MemoryFacts.current(read())

    /** Only Kura's explicitly stored preferences can bias future expression. */
    @Synchronized internal fun stylePreferences(): StylePreferences = RelationshipState.from(recallAll())

    @Synchronized fun remember(text: String, categoryHint: String? = null): Memory {
        val content = text.trim().replace(Regex("\\s+"), " ")
        require(content.length in 3..240) { "El recuerdo debe tener entre 3 y 240 caracteres." }
        val current = read()
        val key = MemoryFacts.key(content)
        val old = current.filter { key != null && MemoryFacts.key(it.content) == key }
            .maxByOrNull { it.updatedAt }
            ?: current.firstOrNull { MemoryFacts.normalize(it.content) == MemoryFacts.normalize(content) }
        val now = System.currentTimeMillis()
        val item = if (old == null) {
            require(current.size < 50) { "Llegué al límite de 50 recuerdos. Borra uno antes de añadir otro." }
            Memory(prefs.getLong("next_id", 1L), content, category = categoryHint ?: MemoryFacts.category(content),
                tags = MemorySelector.keywords(content).take(8), timestamp = now)
        } else old.copy(content = content, category = categoryHint ?: MemoryFacts.category(content),
            tags = MemorySelector.keywords(content).take(8), updatedAt = now, active = true)
        val items = current.filterNot { it.id == item.id || (key != null && MemoryFacts.key(it.content) == key) } + item
        persist(items, if (old == null) item.id + 1 else prefs.getLong("next_id", 1L))
        return item
    }

    @Synchronized fun correct(id: Long, text: String): Memory? {
        val content = text.trim().replace(Regex("\\s+"), " ")
        require(content.length in 3..240) { "El recuerdo debe tener entre 3 y 240 caracteres." }
        val current = read()
        val old = current.firstOrNull { it.id == id } ?: return null
        val changed = old.copy(content = content, category = MemoryFacts.category(content),
            tags = MemorySelector.keywords(content).take(8),
            updatedAt = System.currentTimeMillis(), active = true)
        val key = MemoryFacts.key(content)
        persist(current.filterNot { it.id == id || (key != null && MemoryFacts.key(it.content) == key) } + changed)
        return changed
    }

    @Synchronized fun forget(id: Long): Boolean {
        val current = read()
        if (current.none { it.id == id }) return false
        persist(current.filterNot { it.id == id })
        return true
    }

    @Synchronized fun relevantTo(message: String, previousUserMessages: List<String> = emptyList()): List<Memory> {
        val current = read()
        val selected = MemorySelector.select(current, message, previousUserMessages)
        if (selected.isEmpty()) return selected
        val ids = selected.map { it.id }.toSet()
        val now = System.currentTimeMillis()
        val updated = current.map { if (it.id in ids) it.copy(lastUsed = now,
            accessCount = (it.accessCount + 1).coerceAtMost(1_000_000)) else it }
        persist(updated)
        val byId = updated.associateBy { it.id }
        return selected.mapNotNull { byId[it.id] }
    }

    private fun persist(items: List<Memory>, nextId: Long = prefs.getLong("next_id", 1L)) {
        val data = JSONArray()
        items.forEach { data.put(JSONObject().put("id", it.id).put("content", it.content)
            .put("category", it.category).put("importance", it.importance)
            .put("timestamp", it.timestamp).put("source", it.source)
            .put("tags", JSONArray(it.tags)).put("updatedAt", it.updatedAt).put("active", it.active)
            .put("lastUsed", it.lastUsed).put("accessCount", it.accessCount)) }
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
                obj.optString("category", "personal").takeUnless { it == "personal" }
                    ?: MemoryFacts.category(content), obj.optInt("importance", 1),
                obj.optLong("timestamp"), obj.optString("source", "Kura"),
                (0 until tags.length()).map { tags.optString(it) },
                obj.optLong("updatedAt", obj.optLong("timestamp")), obj.optBoolean("active", true),
                obj.optLong("lastUsed", 0L), obj.optInt("accessCount", 0).coerceAtLeast(0))
        }
    }

    companion object {
        /** Only explicit commands change permanent memory. */
        fun command(message: String): MemoryCommand? {
            val text = message.trim().removePrefix("¿").replaceFirst(Regex("^aria\\s*[,.:]?\\s*", RegexOption.IGNORE_CASE), "")
            Regex("^recuerda\\s+(?:que\\s+|esto\\s*[:：]\\s*)(.+)$", RegexOption.IGNORE_CASE)
                .matchEntire(text)?.let { return MemoryCommand.Save(it.groupValues[1].trim()) }
            Regex("^(?:recuerda|guarda)\\s+(?:nuestra\\s+)?experiencia\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE)
                .matchEntire(text)?.let { return MemoryCommand.Save(it.groupValues[1].trim(), "experiencia_compartida") }
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

/** Only high-confidence explicit facts share a key; unrelated memories stay separate. */
internal object MemoryFacts {
    fun normalize(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?')

    fun category(text: String): String {
        val normalized = normalize(text)
        return when {
            Regex("^(?:no )?me gusta\\b|^prefiero\\b|^odio\\b|^kura (?:no )?prefiere\\b|^kura disfruta\\b").containsMatchIn(normalized) &&
                Regex("\\b(?:bromas?|bromees|bromea|bromeas|coqueteo|coquetees|coqueteas|sarcasmo|sarcastic[ao]|molestes|molestar|carino|carinosa|tono)\\b")
                    .containsMatchIn(normalized) -> "preferencia_conversacion"
            Regex("\\b(?:juntos|juntas|hablamos|hicimos|terminamos|jugamos|vivimos|compartimos)\\b")
                .containsMatchIn(normalized) -> "experiencia_compartida"
            Regex("^(?:no )?me gusta\\b|^prefiero\\b|^odio\\b").containsMatchIn(normalized) -> "preferencia"
            Regex("\\b(?:manga|proyecto|aplicacion|app|historia|novela)\\b").containsMatchIn(normalized) -> "proyecto"
            Regex("\\b(?:fuimos|pasamos)\\b").containsMatchIn(normalized) -> "experiencia_compartida"
            else -> "personal"
        }
    }

    fun key(text: String): String? {
        val normalized = normalize(text)
        if (category(text) == "preferencia_conversacion") {
            val style = when {
                Regex("\\b(?:coqueteo|coquetees|coqueteas)\\b").containsMatchIn(normalized) -> "coqueteo"
                Regex("\\b(?:sarcasmo|sarcastic[ao])\\b").containsMatchIn(normalized) -> "sarcasmo"
                Regex("\\b(?:molestes|molestar)\\b").containsMatchIn(normalized) -> "molestia"
                Regex("\\b(?:bromas?|bromees|bromea|bromeas)\\b").containsMatchIn(normalized) -> "bromas"
                Regex("\\b(?:carino|carinosa)\\b").containsMatchIn(normalized) -> "carino"
                else -> null
            }
            if (style != null) return "estilo:$style"
        }
        Regex("^mi (gato|perro|nombre) (?:se llama|es) ([a-z0-9]+)$")
            .matchEntire(normalized)?.let { return "nombre:" + it.groupValues[1] }
        Regex("^(?:no )?me gusta (?:el |la |los |las )?([a-z0-9 ]+)$")
            .matchEntire(normalized)?.let { return "preferencia:" + it.groupValues[1].trim() }
        Regex("^odio (?:el |la |los |las )?([a-z0-9 ]+)$")
            .matchEntire(normalized)?.let { return "preferencia:" + it.groupValues[1].trim() }
        return null
    }

    fun current(memories: List<Memory>): List<Memory> = memories.filter { it.active }
        .groupBy { key(it.content) ?: "id:${it.id}" }
        .values.map { group -> group.maxWith(compareBy<Memory> { it.updatedAt }.thenBy { it.id }) }
        .sortedBy { it.id }

    fun staleRelativeDate(memory: Memory, now: Long): Boolean =
        memory.category != "experiencia_compartida" && memory.category != "experiencia" &&
            Regex("\\b(?:hoy|ayer)\\b").containsMatchIn(normalize(memory.content)) &&
            memory.updatedAt > 0 && now - memory.updatedAt > 48L * 60 * 60 * 1000
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

    fun select(memories: List<Memory>, current: String, previous: List<String>,
               nowMillis: Long = System.currentTimeMillis()): List<Memory> {
        val now = keywords(current)
        val followUp = isFollowUp(current)
        val recent = if (followUp) previous.takeLast(2).flatMap { keywords(it) }.toSet() else emptySet()
        if (now.isEmpty() && recent.isEmpty()) return emptyList()
        val active = MemoryFacts.current(memories).filterNot { MemoryFacts.staleRelativeDate(it, nowMillis) }
        val scored = active.mapNotNull { item ->
            val terms = item.tags.takeIf { it.isNotEmpty() }?.toSet() ?: keywords(item.content)
            val currentScore = terms.intersect(now).size
            val recentScore = if (followUp) terms.intersect(recent).size else 0
            val score = currentScore * 3 + recentScore
            if (score <= 0) null else Triple(item, score, currentScore)
        }
        return scored
            .sortedWith(compareByDescending<Triple<Memory, Int, Int>> { it.second }
                .thenByDescending { it.third }
                .thenByDescending { it.first.importance }
                .thenByDescending { it.first.updatedAt })
            .take(3).map { it.first }
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
    data class Save(val text: String, val categoryHint: String? = null) : MemoryCommand()
    data class Delete(val id: Long) : MemoryCommand()
    data class Correct(val id: Long, val text: String) : MemoryCommand()
    data object ListAll : MemoryCommand()
}
