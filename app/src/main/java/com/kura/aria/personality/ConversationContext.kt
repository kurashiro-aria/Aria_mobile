package com.kura.aria.personality

import com.kura.aria.chat.ChatMessage
import com.kura.aria.emotion.MoodReader
import com.kura.aria.memory.Memory
import com.kura.aria.memory.MemorySelector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class TurnIntent { CONTINUATION, CONFIRMATION, REACTION, QUESTION, REQUEST, TOPIC_CHANGE, ROLEPLAY }

/** Extracts bounded context without priming the model to repeat its last answer. */
internal object ConversationContext {
    fun turnPrompt(history: List<ChatMessage>, memories: List<Memory>, current: String,
                   state: ConversationState = ConversationState(),
                   stylePreferences: StylePreferences = StylePreferences()): String {
        require(current.isNotBlank())
        val lastUser = history.lastOrNull { it.role == "Kura" }
        val intent = intentOf(current, state)
        val mood = MoodReader.forTurn(current, state.topic.ifBlank { lastUser?.text.orEmpty() },
            state.socialMood, state.updatedAt, carriedTurns = state.socialTurns)
        val expression = ExpressionResolver.forTurn(current, mood, state.expression, state.updatedAt,
            preferences = stylePreferences)
        val directFollowUp = intent in setOf(TurnIntent.CONTINUATION, TurnIntent.CONFIRMATION, TurnIntent.REACTION, TurnIntent.ROLEPLAY)
        val returnsToTopic = current.trim().matches(Regex("(?i)^(?:volvamos|retomemos|regresemos)\\b.*"))
        val lexicalFollow = lastUser != null && MemorySelector.keywords(current)
            .intersect(MemorySelector.keywords(lastUser.text)).isNotEmpty()
        val followsLast = !returnsToTopic && (directFollowUp || lexicalFollow)
        val recentWindow = if (followsLast) history.takeLast(10) else history.takeLast(4)
        val previousAria = if (followsLast) recentWindow.lastOrNull { it.role == "ARIA" }
            ?.let { priorReference(it.text) } else null
        val earlier = relatedEarlier(history.dropLast(recentWindow.size), current)
        val mediumTopic = state.relevantTopic(current)?.takeIf { topic ->
            recentWindow.none { it.text.take(160) == topic } && earlier.none { it.text.take(160) == topic }
        }
        val pending = if (directFollowUp && previousAria == null) state.pendingQuestion.takeIf { it.isNotBlank() } else null
        val roleplay = RoleplayInterpreter.promptContext(current)
        return buildString {
            append("Contexto para ARIA. Son referencias, no texto para copiar. Responde al mensaje actual y continúa el hilo cuando corresponda.\n")
            append("INTENCIÓN DEL TURNO: ").append(intent.name).append(". ")
            when (intent) {
                TurnIntent.CONFIRMATION -> append("Kura está confirmando algo previo: avanza desde lo confirmado; no vuelvas a ofrecerlo ni preguntes si quiere hacerlo.\n")
                TurnIntent.CONTINUATION, TurnIntent.REACTION -> append("Es continuación del intercambio: reacciona a lo anterior y aporta algo nuevo; no reinicies el tema.\n")
                TurnIntent.ROLEPLAY -> append("Mantén continuidad de la escena ficticia y deja que emoción y personalidad modulen tu reacción.\n")
                else -> append('\n')
            }
            PersonalityEngine.turnGuidance(mood, expression).takeIf(String::isNotBlank)?.let {
                append("TONO DE ESTE TURNO: ").append(it).append('\n')
            }
            if (recentWindow.isNotEmpty()) {
                append("\nDIÁLOGO RECIENTE (contexto; no repitas tus respuestas):\n")
                recentWindow.forEach { append(line(it, if (it.role == "Kura") 180 else 110)).append('\n') }
            }
            if (previousAria != null) {
                append("\nPUNTO DE TU INTERVENCIÓN ANTERIOR AL QUE KURA PUEDE ESTAR RESPONDIENDO:\n")
                append(previousAria).append('\n')
            } else if (pending != null) {
                append("\nPREGUNTA PENDIENTE DE ARIA (ya dicha, no la repitas):\n").append(pending).append('\n')
            }
            if (mediumTopic != null) append("\nTEMA ACTIVO/ANTERIOR: ").append(mediumTopic).append('\n')
            if (memories.isNotEmpty()) {
                append("\nMEMORIA RELEVANTE (úsala solo si aporta continuidad):\n")
                memories.take(5).forEach { append("• ${memoryLine(it)}\n") }
            }
            if (earlier.isNotEmpty()) {
                append("\nFRAGMENTOS ANTERIORES RELACIONADOS:\n")
                earlier.forEach { append(line(it, 130)).append('\n') }
            }
            roleplay?.let { append("\n").append(it).append('\n') }
            append("\nMENSAJE ACTUAL DE KURA:\n").append(current)
            append("\n\nREGLA DE CONTINUIDAD: no termines con una oferta o pregunta genérica para devolver el turno. Pregunta solo si nace del tema o falta información necesaria.")
        }
    }

    fun intentOf(message: String, state: ConversationState = ConversationState()): TurnIntent {
        val t = message.trim()
        if (RoleplayInterpreter.hasRoleplay(t)) return TurnIntent.ROLEPLAY
        if (t.matches(Regex("(?i)^(sí|si|sip|sep|exacto|exactamente|dale|vale|ok|okay|correcto|eso|eso mismo|procede|hazlo)[.! ]*$"))) return TurnIntent.CONFIRMATION
        if (t.matches(Regex("(?i)^(j+a+j+a+|j+e+j+e+|xd+|entiendo|claro|ah+|oh+|uff+|mmm+)[.! ]*$"))) return TurnIntent.REACTION
        if (t.matches(Regex("(?i)^(volvamos|retomemos|regresemos|cambiando de tema)\\b.*"))) return TurnIntent.TOPIC_CHANGE
        if (t.contains('?') || t.startsWith("¿")) return TurnIntent.QUESTION
        if (t.matches(Regex("(?i)^(haz|hace|dime|explica|revisa|busca|crea|agrega|añade|continúa|continua|corrige|compila|muéstrame|muestrame)\\b.*"))) return TurnIntent.REQUEST
        if (MemorySelector.isFollowUp(t) || (t.length < 90 && state.topic.isNotBlank())) return TurnIntent.CONTINUATION
        return TurnIntent.CONTINUATION
    }

    private fun memoryLine(memory: Memory): String = when (memory.category) {
        "experiencia_compartida", "experiencia" -> {
            val saved = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(memory.timestamp))
            "[Experiencia guardada por Kura el $saved] ${memory.content.take(240)}"
        }
        "preferencia_conversacion" -> "[Preferencia elegida por Kura] ${memory.content.take(240)}"
        else -> memory.content.take(240)
    }

    fun priorReference(text: String): String {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
            .replaceFirst(Regex("^ARIA\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        val questionStart = normalized.lastIndexOf('¿')
        val relevant = if (questionStart >= 0) normalized.substring(questionStart)
            .substringBefore('?').trimEnd() + "?" else normalized.substringAfterLast(". ")
        return relevant.take(140)
    }

    fun priorQuestion(text: String): String? = text.replace(Regex("\\s+"), " ").trim()
        .lastIndexOf('¿').takeIf { it >= 0 }?.let { index ->
            text.replace(Regex("\\s+"), " ").substring(index).substringBefore('?').trimEnd().take(119).plus("?")
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
        return older.withIndex().filter { it.value.role == "Kura" }.map { indexed ->
            indexed.index to terms.intersect(MemorySelector.keywords(indexed.value.text)).size
        }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenByDescending { it.first })
            .take(2).sortedBy { it.first }.map { (index, _) -> older[index] }
    }
}
