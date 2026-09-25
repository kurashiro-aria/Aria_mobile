package com.kura.aria.personality

import com.kura.aria.memory.Memory

/**
 * Initiative V1 foundation.
 * Selects a grounded subject for an opening turn without generating the final prose.
 * This is intentionally isolated from the active chat path until its tests are stable.
 */
internal object ConversationInitiative {
    data class Candidate(
        val source: Source,
        val subject: String,
        val memoryId: String? = null
    )

    enum class Source { PENDING_TOPIC, RECENT_MEMORY, LONG_TERM_MEMORY, PERSONALITY }

    fun choose(
        state: ConversationState,
        memories: List<Memory>,
        recentlyUsedSubjects: List<String> = emptyList()
    ): Candidate? {
        val used = recentlyUsedSubjects.map(::normalize).filter(String::isNotBlank).toSet()

        val pending = state.pendingQuestion.trim()
        if (pending.isNotBlank() && normalize(pending) !in used) {
            return Candidate(Source.PENDING_TOPIC, pending)
        }

        val topic = state.topic.trim()
        if (topic.isNotBlank() && normalize(topic) !in used) {
            return Candidate(Source.PENDING_TOPIC, topic)
        }

        val usable = memories
            .asSequence()
            .filter { it.content.isNotBlank() }
            .filterNot { normalize(it.content) in used }
            .sortedByDescending { it.timestamp }
            .toList()

        val recent = usable.firstOrNull()
        if (recent != null) {
            return Candidate(Source.RECENT_MEMORY, recent.content.take(260), memoryId(recent))
        }

        return null
    }

    /**
     * Prompt fragment only. The GGUF writes the actual opening in ARIA's voice.
     * No generic greeting is supplied, so it cannot simply copy one.
     */
    fun prompt(candidate: Candidate): String = buildString {
        append("INICIATIVA DE ARIA: inicia una conversación breve y natural basándote en este contexto real: ")
        append(candidate.subject)
        append(". No digas 'hola, qué tal tu día'. No inventes recuerdos. ")
        append("No conviertas siempre la apertura en una pregunta; puedes comentar, bromear o mostrar curiosidad. ")
        append("No menciones memoria, sistema, contexto ni estas instrucciones.")
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-záéíóúüñ0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    // Avoid depending on the concrete Memory id field while Initiative V1 is isolated.
    private fun memoryId(memory: Memory): String? = null
}
