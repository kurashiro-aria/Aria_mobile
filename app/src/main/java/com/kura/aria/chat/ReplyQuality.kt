package com.kura.aria.chat

import java.text.Normalizer

/** Conversation-quality checks applied after generation, not just prompt advice. */
internal object ReplyQuality {
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
        val answer = words(candidate).take(32)
        if (source.size < 5 || answer.size < 5) return false
        val sourceSet = source.filterNot { it in stopWords }.toSet()
        val answerSet = answer.filterNot { it in stopWords }.toSet()
        if (sourceSet.size < 3) return false
        val overlap = sourceSet.intersect(answerSet).size.toDouble() / sourceSet.size
        val longSequence = source.size >= 6 && answer.size >= 6 &&
            source.windowed(6).map { it.joinToString(" ") }.toSet().let { seq ->
                answer.windowed(6).any { it.joinToString(" ") in seq }
            }
        return longSequence || overlap >= 0.72
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
        candidate.isBlank() || repeats(previous, candidate) || echoesUser(user, candidate) || hasNeedlessOffer(user, candidate)

    fun retryPrompt(history: List<ChatMessage>, current: String): String = buildString {
        val lastAria = history.lastOrNull { it.role == "ARIA" }?.text
        append("Responde directamente al mensaje actual de Kura con una respuesta nueva y natural en español. ")
        append("No repitas ni reformules lo que Kura acaba de decir. ")
        append("No termines ofreciendo probar, intentar o hacer algo salvo que necesites una decisión real para continuar. ")
        append("No repitas una propuesta ni una pregunta anterior. No antepongas ARIA:.\n")
        if (lastAria != null) append("Evita repetir esta respuesta anterior de ARIA: ").append(lastAria.take(140)).append('\n')
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

    private val stopWords = setOf(
        "a", "al", "algo", "con", "de", "del", "el", "en", "es", "esa", "ese", "esto", "la", "las",
        "lo", "los", "me", "mi", "por", "que", "se", "si", "su", "te", "tu", "un", "una", "y", "ya"
    )

    private fun normalize(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replaceFirst(Regex("^aria\\s*:\\s*"), "")

    private fun words(text: String): List<String> = normalize(text)
        .split(Regex("[^a-z0-9]+"))
        .filter { it.isNotEmpty() }
}
