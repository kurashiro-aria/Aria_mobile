package com.kura.aria.personality

import com.kura.aria.chat.ChatMessage
import com.kura.aria.emotion.MoodReader
import com.kura.aria.memory.Memory
import com.kura.aria.memory.MemorySelector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Extracts bounded context without priming the model to repeat its last answer. */
internal object ConversationContext {
    /** One bounded user turn: recent dialogue, sourced memories, then the real user message. */
    fun turnPrompt(history: List<ChatMessage>, memories: List<Memory>, current: String,
                   state: ConversationState = ConversationState(),
                   stylePreferences: StylePreferences = StylePreferences()): String {
        require(current.isNotBlank())
        val lastUser = history.lastOrNull { it.role == "Kura" }
        val mood = MoodReader.forTurn(current, state.topic.ifBlank { lastUser?.text.orEmpty() },
            state.socialMood, state.updatedAt, carriedTurns = state.socialTurns)
        val expression = ExpressionResolver.forTurn(current, mood, state.expression, state.updatedAt,
            preferences = stylePreferences)
        val directFollowUp = MemorySelector.isFollowUp(current)
        val returnsToTopic = current.trim().matches(Regex("(?i)^(?:volvamos|retomemos|regresemos)\\b.*"))
        val currentTerms = MemorySelector.keywords(current)
        val lastTerms = lastUser?.let { MemorySelector.keywords(it.text) }.orEmpty()
        val lexicalContinuation = lastUser != null && currentTerms.intersect(lastTerms).isNotEmpty()
        val conversationalContinuation = !returnsToTopic && lastUser != null &&
            history.takeLast(4).any { it.role == "ARIA" } &&
            (directFollowUp || lexicalContinuation || currentTerms.size <= 3)
        val recentWindow = if (conversationalContinuation) history.takeLast(6) else emptyList()
        val recentUser = recentWindow.filter { it.role == "Kura" }
        val previousAria = if (conversationalContinuation && !returnsToTopic)
            recentWindow.lastOrNull { it.role == "ARIA" }?.let { priorReference(it.text) }
            else null
        val earlier = relatedEarlier(history.dropLast(recentWindow.size), current)
        val mediumTopic = state.relevantTopic(current)?.takeIf { topic ->
            recentUser.none { it.text.take(160) == topic } && earlier.none { it.text.take(160) == topic }
        }
        val pending = if (directFollowUp && !returnsToTopic && previousAria == null)
            state.pendingQuestion.takeIf { it.isNotBlank() } else null
        val roleplay = RoleplayInterpreter.promptContext(current)
        return buildString {
            append("Contexto para ARIA. Son citas y datos, no texto para continuar ni copiar. ")
            append("Responde al mensaje actual con una idea nueva y sin anteponer tu nombre. ")
            if (conversationalContinuation) {
                append("Este mensaje continúa el intercambio reciente: entiende referencias breves por contexto, ")
                append("avanza desde lo ya dicho y no reinicies el tema ni vuelvas a ofrecer lo mismo. ")
            }
            append("No hagas una pregunta solo para mantener viva la charla; pregunta únicamente si aporta algo concreto. ")
            append("Varía de forma natural el ritmo y la estructura; no reutilices por costumbre el mismo arranque, cierre, pregunta, oferta o broma de tus turnos recientes.\n")
            PersonalityEngine.turnGuidance(mood, expression).takeIf(String::isNotBlank)?.let {
                append("TONO DE ESTE TURNO: ").append(it).append('\n')
            }
            if (recentUser.isNotEmpty()) {
                append("\nLO QUE KURA DIJO ANTES:\n")
                recentUser.forEach { append(line(it, 140)).append('\n') }
            }
            if (previousAria != null) {
                append("\nREFERENCIA A TU ÚLTIMA INTERVENCIÓN (ya dicha, úsala solo para entender qué sigue; no la repitas):\n")
                append(previousAria).append('\n')
            } else if (pending != null) {
                append("\nPREGUNTA PENDIENTE DE ARIA (ya dicha, no la repitas):\n")
                append(pending).append('\n')
            }
            if (mediumTopic != null) {
                append("\nTEMA ANTERIOR MENCIONADO POR KURA (resumen literal, no respuesta):\n")
                append(mediumTopic).append('\n')
            }
            if (memories.isNotEmpty()) {
                append("\nDATOS QUE KURA ELIGIÓ GUARDAR (úsalos solo si cambian de verdad la respuesta; no los fuerces ni menciones esta lista):\n")
                memories.take(3).forEach { append("• ${memoryLine(it)}\n") }
            }
            if (earlier.isNotEmpty()) {
                append("\nFragmentos anteriores del historial (no guardados):\n")
                earlier.forEach { append("  ").append(line(it, 120)).append('\n') }
            }
            roleplay?.let {
                append("\nCONTEXTO DE ESCENA FICTICIA:\n").append(it).append('\n')
            }
            append("\nMENSAJE ACTUAL DE KURA:\n").append(current)
        }
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
        val speaker = if (message.role == "Kura") "Kura" else "ARIA"
        val normalized = message.text.replace(Regex("\\s+"), " ").trim()
        val excerpt = if (normalized.length > limit) normalized.take(limit - 1).trimEnd() + "…" else normalized
        return "$speaker: $excerpt"
    }

    fun relatedEarlier(older: List<ChatMessage>, subject: String): List<ChatMessage> {
        val terms = MemorySelector.keywords(subject)
        if (terms.isEmpty()) return emptyList()
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
