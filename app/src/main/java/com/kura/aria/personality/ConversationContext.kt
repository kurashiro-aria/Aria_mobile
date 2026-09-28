package com.kura.aria.personality

import com.kura.aria.chat.ChatMessage
import com.kura.aria.chat.ReplyQuality
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
                   stylePreferences: StylePreferences = StylePreferences()): String {
        require(current.isNotBlank())
        val lastUser = history.lastOrNull { it.role == "Kura" }
        val mood = MoodReader.forTurn(current, state.topic.ifBlank { lastUser?.text.orEmpty() },
            state.socialMood, state.updatedAt, carriedTurns = state.socialTurns)
        val expression = ExpressionResolver.forTurn(current, mood, state.expression, state.updatedAt,
            preferences = stylePreferences)
        val greeting = isStandaloneGreeting(RoleplayInterpreter.spokenText(current))
        val directFollowUp = !greeting && (MemorySelector.isFollowUp(current) || isQualifiedAssent(current) ||
            ConversationPerspective.shortClarification(current, history.lastOrNull()?.takeIf { it.role == "ARIA" }?.text))
        val returnsToTopic = current.trim().matches(Regex("(?i)^(?:volvamos|retomemos|regresemos)\\b.*"))
        val currentTerms = MemorySelector.keywords(RoleplayInterpreter.spokenText(current))
        val lastTerms = lastUser?.let { MemorySelector.keywords(RoleplayInterpreter.spokenText(it.text)) }.orEmpty()
        val lexicalContinuation = !greeting && lastUser != null && currentTerms.intersect(lastTerms).isNotEmpty()
        val conversationalContinuation = !greeting && !returnsToTopic && lastUser != null &&
            history.takeLast(4).any { it.role == "ARIA" } &&
            (directFollowUp || lexicalContinuation)
        val recentWindow = if (conversationalContinuation) history.takeLast(6) else emptyList()
        val recentUser = recentWindow.filter { it.role == "Kura" &&
            !RoleplayInterpreter.isPureAction(it.text) }
        val previousAria = if (conversationalContinuation && !returnsToTopic)
            recentWindow.lastOrNull { it.role == "ARIA" }?.text
                ?.takeUnless(ReplyQuality::hasTranscript)?.let(::priorReference)
            else null
        val earlier = if (greeting || RoleplayInterpreter.isPureAction(current)) emptyList()
            else relatedEarlier(history.dropLast(recentWindow.size), current)
        val mediumTopic = if (greeting || RoleplayInterpreter.isPureAction(current)) null
            else state.relevantTopic(current)?.takeIf { topic ->
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
            ConversationPerspective.guidance(current)?.let { append(it).append('\n') }
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
            if (!greeting && memories.isNotEmpty()) {
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
