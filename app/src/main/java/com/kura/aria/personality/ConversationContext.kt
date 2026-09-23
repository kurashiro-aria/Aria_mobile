package com.kura.aria.personality

import com.kura.aria.chat.ChatMessage
import com.kura.aria.memory.Memory
import com.kura.aria.memory.MemorySelector

/** Extracts bounded context without priming the model to repeat its last answer. */
internal object ConversationContext {
    /** One bounded user turn: recent dialogue, sourced memories, then the real user message. */
    fun turnPrompt(history: List<ChatMessage>, memories: List<Memory>, current: String,
                   state: ConversationState = ConversationState()): String {
        require(current.isNotBlank())
        val lastUser = history.lastOrNull { it.role == "Kura" }
        val directFollowUp = MemorySelector.isFollowUp(current)
        val returnsToTopic = current.trim().matches(Regex("(?i)^(?:volvamos|retomemos|regresemos)\\b.*"))
        val followsLast = !returnsToTopic && (directFollowUp ||
            (lastUser != null && MemorySelector.keywords(current)
                .intersect(MemorySelector.keywords(lastUser.text)).isNotEmpty()))
        val recentWindow = if (followsLast) history.takeLast(6) else emptyList()
        val recentUser = recentWindow.filter { it.role == "Kura" }
        val previousAria = if (directFollowUp && !returnsToTopic) recentWindow.lastOrNull { it.role == "ARIA" }
            ?.let { priorReference(it.text) } else null
        val earlier = relatedEarlier(history.dropLast(recentWindow.size), current)
        val mediumTopic = state.relevantTopic(current)?.takeIf { topic ->
            recentUser.none { it.text.take(160) == topic } && earlier.none { it.text.take(160) == topic }
        }
        val pending = if (directFollowUp && !returnsToTopic && previousAria == null) state.pendingQuestion.takeIf { it.isNotBlank() }
            else null
        return buildString {
            append("Contexto para ARIA. Son citas y datos, no texto para continuar ni copiar. ")
            append("Responde al mensaje actual con una idea nueva y sin anteponer tu nombre.\n")
            if (recentUser.isNotEmpty()) {
                append("\nLO QUE KURA DIJO ANTES:\n")
                recentUser.forEach { append(line(it, 140)).append('\n') }
            }
            if (previousAria != null) {
                append("\nREFERENCIA A TU ÚLTIMA INTERVENCIÓN (ya dicha, no la repitas):\n")
                append(previousAria).append('\n')
            } else if (pending != null) {
                append("\nPREGUNTA PENDIENTE DE ARIA (ya dicha, no la repitas):\n")
                append(pending).append('\n')
            }
            if (mediumTopic != null) {
                append("\nTEMA ANTERIOR MENCIONADO POR KURA (resumen literal, no respuesta):\n")
                append(mediumTopic).append('\n')
            }
            if (memories.isNotEmpty() || earlier.isNotEmpty()) {
                append("\nRECUERDOS RELEVANTES:\n")
                memories.take(5).forEach { append("• Guardado por Kura #${it.id}: ${it.content.take(240)}\n") }
                if (earlier.isNotEmpty()) {
                    append("• Fragmentos anteriores del historial (no guardados):\n")
                    earlier.forEach { append("  ").append(line(it, 120)).append('\n') }
                }
            }
            append("\nMENSAJE ACTUAL DE KURA:\n").append(current)
        }
    }

    /** A short assent needs the prior question, never the entire previous answer. */
    fun priorReference(text: String): String {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
            .replaceFirst(Regex("^ARIA\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        val questionStart = normalized.lastIndexOf('¿')
        val relevant = if (questionStart >= 0) normalized.substring(questionStart)
            .substringBefore('?').trimEnd() + "?" else normalized.substringAfterLast(". ")
        return relevant.take(120)
    }

    fun priorQuestion(text: String): String? = text.replace(Regex("\\s+"), " ").trim()
        .lastIndexOf('¿').takeIf { it >= 0 }?.let { index ->
            text.replace(Regex("\\s+"), " ").substring(index).substringBefore('?').trimEnd()
                .take(119).plus("?")
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
