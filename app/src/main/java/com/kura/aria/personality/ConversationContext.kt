package com.kura.aria.personality

import com.kura.aria.chat.ChatMessage
import com.kura.aria.memory.Memory
import com.kura.aria.memory.MemorySelector

/** Extracts bounded, verbatim context. It never invents a summary or saves implicit memories. */
internal object ConversationContext {
    /** One bounded user turn: recent dialogue, sourced memories, then the real user message. */
    fun turnPrompt(history: List<ChatMessage>, memories: List<Memory>, current: String): String {
        require(current.isNotBlank())
        val recent = history.takeLast(6)
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

    fun initiativePrompt(history: List<ChatMessage>): String = buildString {
        append("Contexto reciente (citas parciales):\n")
        history.takeLast(4).forEach { append(line(it, 160)).append('\n') }
        append("Inicia tú la conversación con Kura de forma natural y breve. ")
        append("Retoma un tema visible si tiene sentido; no inventes pendientes ni conviertas el mensaje en un cuestionario.")
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
