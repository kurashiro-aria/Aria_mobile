package com.kura.aria.chat

import java.text.Normalizer

/** Detects substantial reuse of ARIA's previous answer, without rejecting ordinary topic overlap. */
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

    fun retryPrompt(history: List<ChatMessage>, current: String): String = buildString {
        val lastUser = history.lastOrNull { it.role == "Kura" }?.text
        append("Responde solo al mensaje actual de Kura con una frase nueva en español. ")
        append("No repitas una propuesta ni una pregunta anterior. No antepongas ARIA:.\n")
        if (lastUser != null) append("Tema anterior de Kura: ").append(lastUser.take(100)).append('\n')
        append("Mensaje actual de Kura: ").append(current)
    }

    fun fallback(current: String): String {
        val normalized = words(current).joinToString(" ")
        return when (normalized) {
            "si", "vale", "claro", "dale", "exacto", "ok" -> "Vale, seguimos con eso."
            "no" -> "Entiendo. Dejemos esa idea."
            else -> "Perdona, me repetí. ¿Me lo dices de otra forma?"
        }
    }

    private fun words(text: String): List<String> = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replaceFirst(Regex("^aria\\s*:\\s*"), "")
        .split(Regex("[^a-z0-9]+"))
        .filter { it.isNotEmpty() }
}
