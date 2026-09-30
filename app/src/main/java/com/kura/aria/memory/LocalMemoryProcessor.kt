package com.kura.aria.memory

import com.kura.aria.personality.RoleplayInterpreter

/** Conservative local extraction. History is always stored separately; only clear facts become memory. */
internal object LocalMemoryProcessor {
    data class Candidate(val content: String, val type: String, val confidence: Float)

    fun candidate(userMessage: String): Candidate? {
        if (RoleplayInterpreter.hasRoleplay(userMessage)) return null
        val text = userMessage.trim().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?')
        if (text.length !in 8..240) return null
        val type = when {
            Regex("(?i)^(?:yo )?prefiero\\b|^(?:a mi )?me gusta\\b|^no me gusta\\b|^odio\\b")
                .containsMatchIn(text) -> "preferencia"
            Regex("(?i)^mi (?:telefono|celular|nombre|perro|gato|madre|padre|herman[oa]) (?:es|se llama)\\b")
                .containsMatchIn(text) -> "hecho"
            Regex("(?i)^(?:estoy|estamos) (?:trabajando|haciendo|creando) (?:en )?(?:el )?(?:proyecto|app|aplicacion)\\b")
                .containsMatchIn(text) -> "proyecto"
            Regex("(?i)^(?:hoy|ayer) (?:fui|hicimos|terminamos|comenzamos|paso|ocurrio)\\b")
                .containsMatchIn(text) -> "experiencia"
            else -> return null
        }
        return Candidate(text, type, 0.9f)
    }

    fun process(memory: AriaMemory, userMessage: String): Memory? {
        val candidate = candidate(userMessage) ?: return null
        return memory.remember(candidate.content, candidate.type)
    }
}
