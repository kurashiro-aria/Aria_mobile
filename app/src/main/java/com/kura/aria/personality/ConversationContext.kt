package com.kura.aria.personality

import com.kura.aria.chat.ChatMessage
import com.kura.aria.memory.Memory
import com.kura.aria.memory.MemorySelector

/** Extracts bounded, verbatim context. It never invents a summary or saves implicit memories. */
internal object ConversationContext {
    /** One bounded user turn: recent dialogue, sourced memories, then the real user message. */
    fun turnPrompt(history: List<ChatMessage>, memories: List<Memory>, current: String): String {
        require(current.isNotBlank())
        val lastUser = history.lastOrNull { it.role == "Kura" }
        val followsLast = MemorySelector.isFollowUp(current) ||
            (lastUser != null && MemorySelector.keywords(current)
                .intersect(MemorySelector.keywords(lastUser.text)).isNotEmpty())
        val recent = if (followsLast) history.takeLast(4) else emptyList()
        val earlier = relatedEarlier(history.dropLast(recent.size), current)
        return buildString {
            append("Contexto para ARIA. Son citas y datos; responde solo al mensaje actual.\n")
            if (recent.isNotEmpty()) {
                append("\nCONTEXTO RECIENTE:\n")
                recent.forEach { append(line(it, 180)).append('\n') }
            }
            if (memories.isNotEmpty() || earlier.isNotEmpty()) {
                append("\nRECUERDOS RELEVANTES:\n")
                memories.take(3).forEach { append("• Guardado por Kura #${it.id}: ${it.content}\n") }
                if (earlier.isNotEmpty()) {
                    append("• Fragmentos anteriores del historial (no guardados):\n")
                    earlier.forEach { append("  ").append(line(it, 120)).append('\n') }
                }
            }
            append("\nMENSAJE ACTUAL DE KURA:\n").append(current)
        }
    }

    fun line(message: ChatMessage, limit: Int): String {
        val speaker = if (message.role == "Kura") "Kura" else "ARIA"
        val normalized = message.text.replace(Regex("\\s+"), " ").trim()
        val excerpt = if (normalized.length > limit) normalized.take(limit - 1).trimEnd() + "…" else normalized
        return "$speaker: $excerpt"
    }

    fun relatedEarlier(older: List<ChatMessage>, subject: String): List<ChatMessage> {
        val terms = MemorySelector.keywords(subject)
        if (terms.isEmpty()) return emptyList()
        // Earlier assistant replies often become an answer to copy, so retrieve user facts only.
        return older.withIndex().filter { it.value.role == "Kura" }
            .map { indexed ->
                val score = terms.intersect(MemorySelector.keywords(indexed.value.text)).size
                indexed.index to score
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }
                .thenByDescending { it.first })
            .take(2).sortedBy { it.first }
            .map { (index, _) -> older[index] }
    }
}
