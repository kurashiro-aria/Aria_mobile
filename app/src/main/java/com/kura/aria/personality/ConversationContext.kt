package com.kura.aria.personality

import com.kura.aria.chat.ChatMessage
import com.kura.aria.memory.MemorySelector

/** Extracts bounded, verbatim context. It never invents a summary or saves implicit memories. */
internal object ConversationContext {
    fun line(message: ChatMessage, limit: Int): String {
        val speaker = if (message.role == "Kura") "Kura" else "ARIA"
        val normalized = message.text.replace(Regex("\\s+"), " ").trim()
        val excerpt = if (normalized.length > limit) normalized.take(limit - 1).trimEnd() + "…" else normalized
        return "$speaker: $excerpt"
    }

    fun relatedEarlier(older: List<ChatMessage>, subject: String): List<ChatMessage> {
        val terms = MemorySelector.keywords(subject)
        if (terms.isEmpty()) return emptyList()
        // Prefer a past exchange over an isolated message, and never fill the prompt
        // with unrelated history merely because it is old.
        return older.withIndex().filter { it.value.role == "Kura" }
            .map { indexed ->
                val reply = older.getOrNull(indexed.index + 1)?.takeIf { it.role == "ARIA" }
                val score = terms.intersect(MemorySelector.keywords(indexed.value.text)).size
                Triple(indexed.index, reply, score)
            }
            .filter { it.third > 0 }
            .sortedWith(compareByDescending<Triple<Int, ChatMessage?, Int>> { it.third }
                .thenByDescending { it.first })
            .take(2).sortedBy { it.first }
            .flatMap { (index, reply, _) -> listOfNotNull(older[index], reply) }
    }
}
