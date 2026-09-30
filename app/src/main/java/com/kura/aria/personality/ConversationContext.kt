package com.kura.aria.personality

import com.kura.aria.chat.ChatMessage
import com.kura.aria.emotion.MoodReader
import com.kura.aria.memory.Memory
import com.kura.aria.memory.MemorySelector
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Extracts bounded context without priming the model to repeat its last answer. */
internal object ConversationContext {
    /** One bounded user turn: recent dialogue, sourced memories, then the real user message. */
    fun turnPrompt(history: List<ChatMessage>, memories: List<Memory>, current: String,
                   state: ConversationState = ConversationState(),
                   stylePreferences: StylePreferences = StylePreferences(),
                   understood: ConversationTurn = ConversationBrain.interpret(history, current, state)): String {
        require(current.isNotBlank())
        val lastUser = history.lastOrNull { it.role == "Kura" }
        val mood = MoodReader.forTurn(current, state.topic.ifBlank { lastUser?.text.orEmpty() },
            state.socialMood, state.updatedAt, carriedTurns = state.socialTurns)
        val expression = ExpressionResolver.forTurn(current, mood, state.expression, state.updatedAt,
            preferences = stylePreferences)
        val roleplay = if (understood.roleplay.isNotEmpty()) RoleplayInterpreter.promptContext(current) else null
        return buildString {
            append("Contexto para ARIA: estas citas no son tu respuesta. Responde al mensaje actual sin copiar turnos ni anteponer tu nombre. ")
            if (understood.continuity == TurnContinuity.CONTINUES) {
                append("Este mensaje continúa el intercambio reciente; usa sus referentes sin repetir lo ya dicho. ")
            }
            if (understood.intent == ConversationIntent.ANSWER)
                append("Kura respondió a una pregunta; continúa desde su elección. ")
            if (isQualifiedAssent(current) && understood.intent == ConversationIntent.ANSWER)
                append("Kura ya aceptó y precisó su elección; incorpórala sin volver a preguntarla. ")
            append("Pregunta si aporta algo; también puedes comentar, reaccionar o cerrar sin pregunta.\n")
            PersonalityEngine.turnGuidance(mood, expression).takeIf(String::isNotBlank)?.let {
                append("TONO DE ESTE TURNO: ").append(it).append('\n')
            }
            ConversationPerspective.guidance(current)?.let { append(it).append('\n') }
            if (understood.recent.isNotEmpty()) {
                append("\nINTERCAMBIO INMEDIATO (ya ocurrió; no lo repitas):\n")
                understood.recent.forEach { prior ->
                    if (prior.role == "Kura") append("Kura dijo: ").append(line(prior, 140))
                    else append("ARIA dijo: «").append(priorReference(prior.text)).append('»')
                    append('\n')
                }
            } else if (understood.pendingQuestion != null) {
                append("\nPREGUNTA PENDIENTE DE ARIA (ya dicha, no la repitas):\n")
                append(understood.pendingQuestion).append('\n')
            }
            if (understood.relevantTopic != null) {
                append("\nTEMA ANTERIOR MENCIONADO POR KURA (resumen literal, no respuesta):\n")
                append(understood.relevantTopic).append('\n')
            }
            if (understood.continuity == TurnContinuity.RETURNS_TO_TOPIC &&
                understood.relevantTopic == null) {
                val earlier = relatedEarlier(history, understood.spokenText)
                if (earlier.isNotEmpty()) {
                    append("\nFragmentos anteriores del historial (no guardados):\n")
                    earlier.forEach { append("  ").append(line(it, 120)).append('\n') }
                }
            }
            if (understood.intent != ConversationIntent.SOCIAL &&
                understood.intent != ConversationIntent.ROLEPLAY_ACTION && memories.isNotEmpty()) {
                append("\nDATOS QUE KURA ELIGIÓ GUARDAR (úsalos solo si cambian de verdad la respuesta; no los fuerces ni menciones esta lista):\n")
                memories.take(3).forEach { append("• ${memoryLine(it)}\n") }
            }
            roleplay?.let {
                append("\nCONTEXTO DE ESCENA FICTICIA:\n").append(it).append('\n')
            }
            append("\nMENSAJE ACTUAL DE KURA:\n").append(current)
        }
    }

    fun isStandaloneGreeting(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT).trim()
            .replace(Regex("[¡!¿?.,;:]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return normalized.matches(Regex("^(?:(?:hola|hey|buenas|holi|holaa)(?:\\s+aria)?|(?:buenos días|buenas tardes|buenas noches)(?:\\s+aria)?)$"))
    }

    /** An assent can carry the actual choice: "sí, uno de uva" still answers ARIA's last question. */
    fun isQualifiedAssent(text: String): Boolean {
        val normalized = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").trim()
        return normalized.matches(Regex("^(?:si|claro|exacto|vale|ok|dale|por supuesto)(?:[,;:]\\s*|\\s+).{1,80}[.!]?$"))
    }

    /** A short reaction or answer can refer to ARIA's proposal without repeating its nouns. */
    fun respondsToPriorTurn(current: String, previousAria: String?): Boolean {
        if (previousAria.isNullOrBlank()) return false
        val text = Normalizer.normalize(RoleplayInterpreter.spokenText(current).lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").trim().trimStart('¿', '¡')
        if (text.length > 110 || isStandaloneGreeting(current)) return false
        if (Regex("^(?:suena|se ve|me gusta|me encanta|prefiero|mejor|perfecto|genial|que rico|que bueno|delicioso|tentador)\\b")
                .containsMatchIn(text)) return true
        val clarification = priorQuestion(previousAria)?.let { question ->
            Normalizer.normalize(question.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
        }.orEmpty()
        return Regex("\\b(?:en que sentido|que tipo|que cosa|que necesitas|cual de|como que)\\b")
            .containsMatchIn(clarification) && text.split(Regex("\\s+")).size <= 12 &&
            !Regex("^(?:hoy|ayer|manana|ahora hablemos|cambiando de tema)\\b").containsMatchIn(text)
    }

    private fun memoryLine(memory: Memory): String = when (memory.category) {
        "experiencia_compartida", "experiencia" -> {
            val saved = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(memory.timestamp))
            "[Kura pidió guardar esta experiencia el $saved] ${memory.content.take(240)}"
        }
        "preferencia_conversacion" -> "[Preferencia de conversación elegida por Kura] ${memory.content.take(240)}"
        else -> memory.content.take(240)
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
        val normalized = RoleplayInterpreter.spokenText(message.text)
        val excerpt = if (normalized.length > limit) normalized.take(limit - 1).trimEnd() + "…" else normalized
        return "• «$excerpt»"
    }

    fun relatedEarlier(older: List<ChatMessage>, subject: String): List<ChatMessage> {
        val terms = MemorySelector.keywords(RoleplayInterpreter.spokenText(subject))
        if (terms.isEmpty()) return emptyList()
        return older.withIndex().filter { it.value.role == "Kura" &&
            !RoleplayInterpreter.isPureAction(it.value.text) }
            .map { indexed ->
                val score = terms.intersect(MemorySelector.keywords(
                    RoleplayInterpreter.spokenText(indexed.value.text))).size
                indexed.index to score
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }
                .thenByDescending { it.first })
            .take(2).sortedBy { it.first }
            .map { (index, _) -> older[index] }
    }
}
