package com.kura.aria.chat

import com.kura.aria.personality.RoleplayInterpreter
import com.kura.aria.personality.ConversationPerspective
import com.kura.aria.personality.ConversationContext
import com.kura.aria.memory.MemorySelector
import java.text.Normalizer

/** Conversation-quality checks applied after generation, not just prompt advice. */
internal object ReplyQuality {
    /** A continuation of a multi-speaker transcript is not ARIA's own reply. */
    fun hasTranscript(candidate: String): Boolean {
        val body = candidate.trimStart().replaceFirst(Regex("^ARIA\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        return Regex("(?im)^\\s*(?:Kura|ARIA)\\s*:").containsMatchIn(body)
    }

    fun repeats(previous: String?, candidate: String): Boolean {
        if (previous.isNullOrBlank() || candidate.isBlank()) return false
        val old = words(previous)
        val now = words(candidate)
        if (now.size < 4 || old.size < 4) return false
        if (old == now) return true
        if (old.size < 7 || now.size < 7) return false
        val sequences = old.windowed(7).map { it.joinToString(" ") }.toSet()
        return now.windowed(7).any { it.joinToString(" ") in sequences }
    }

    fun echoesUser(user: String, candidate: String): Boolean {
        val source = words(user)
        val candidateWords = words(candidate)
        if (source.size >= 2 && source == candidateWords) return true
        val answer = candidateWords.take(32)
        if (source.size < 8 || answer.size < 8) return false
        return source.windowed(8).map { it.joinToString(" ") }.toSet().let { seq ->
                answer.windowed(8).any { it.joinToString(" ") in seq }
            }
    }

    /** A roleplay action remains Kura's even if the model surrounds the copy with new text. */
    fun copiesKuraAction(user: String, candidate: String): Boolean {
        val userActions = RoleplayInterpreter.actions(user).map { it.action.lowercase() }.toSet()
        return userActions.isNotEmpty() && RoleplayInterpreter.actions(candidate)
            .any { it.action.lowercase() in userActions }
    }

    fun hasNeedlessOffer(user: String, candidate: String): Boolean {
        val normalized = normalize(candidate).trim()
        if (!normalized.endsWith("?")) return false
        val lastSentence = normalized.split(Regex("[.!]"))
            .lastOrNull { it.isNotBlank() }?.trim() ?: normalized
        val offer = Regex("^(y\\s+)?(quieres|te gustaria|prefieres)\\s+(que\\s+)?(lo\\s+)?(intente|intentemos|pruebe|probemos|haga|hagamos|siga|sigamos|continue|continuemos|revise|revisemos)|^(seguimos|continuamos|lo intentamos|lo probamos)\\b")
        if (!offer.containsMatchIn(lastSentence)) return false
        val userNormalized = normalize(user)
        return !Regex("\\b(cual|que opcion|prefieres|recomiendas|podemos|puedes)\\b").containsMatchIn(userNormalized)
    }

    fun needsRetry(user: String, previous: String?, candidate: String): Boolean =
        candidate.isBlank() || hasTranscript(candidate) || copiesKuraAction(user, candidate) ||
            repeats(previous, candidate) ||
            echoesUser(user, candidate) || hasNeedlessOffer(user, candidate)

    fun retryPrompt(history: List<ChatMessage>, current: String): String = buildString {
        // Keep enough of Kura's prior turn to resolve short replies such as "sí" or "exacto",
        // but never paste ARIA's previous answer into the retry prompt: doing so can make the
        // model copy the very response that triggered the retry.
        val previousKura = history.asReversed().takeIf {
            MemorySelector.isFollowUp(current) || ConversationPerspective.shortClarification(current,
                history.lastOrNull()?.takeIf { message -> message.role == "ARIA" }?.text)
        }
            .orEmpty()
            .firstOrNull { it.role == "Kura" && it.text.trim() != current.trim() &&
                !RoleplayInterpreter.isPureAction(it.text) }
            ?.text

        val previousAria = history.lastOrNull()?.takeIf { it.role == "ARIA" }?.text
            ?.takeIf { it.contains('?') && (MemorySelector.isFollowUp(current) ||
                ConversationContext.isQualifiedAssent(current) ||
                ConversationContext.respondsToPriorTurn(current, it)) }
            ?.takeUnless(::hasTranscript)?.let(ConversationContext::priorReference)

        append("Responde directamente al mensaje actual de Kura con una respuesta nueva y natural en español. ")
        append("No repitas ni reformules lo que Kura acaba de decir. ")
        append("No termines ofreciendo probar, intentar o hacer algo salvo que necesites una decisión real para continuar. ")
        append("No repitas una propuesta ni una pregunta anterior. No antepongas ARIA:.\n")
        ConversationPerspective.guidance(current)?.let { append(it).append('\n') }
        RoleplayInterpreter.promptContext(current)?.let { append(it).append('\n') }
        if (!previousKura.isNullOrBlank()) {
            append("Contexto inmediato de Kura: ").append(previousKura.take(180)).append('\n')
        }
        if (!previousAria.isNullOrBlank()) {
            append("Referencia a tu pregunta anterior, ya dicha: «").append(previousAria)
                .append("». Responde al paso siguiente sin repetirla.\n")
        }
        append("Mensaje actual de Kura: ").append(current)
    }

    fun fallback(current: String): String {
        val normalized = words(current).joinToString(" ")
        return when (normalized) {
            "si", "vale", "claro", "dale", "exacto", "ok", "procede", "hazlo" -> "Vale, seguimos con eso."
            "no" -> "Entiendo. Dejemos esa idea."
            else -> "Se me cruzaron los cables un segundo. Voy directo al punto."
        }
    }

    private fun normalize(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replaceFirst(Regex("^aria\\s*:\\s*"), "")

    private fun words(text: String): List<String> = normalize(text)
        .split(Regex("[^a-z0-9]+"))
        .filter { it.isNotEmpty() }
}
