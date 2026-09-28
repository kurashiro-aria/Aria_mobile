package com.kura.aria.personality

import java.text.Normalizer
import java.util.Locale

/** Resolves who a direct address in Kura's message refers to; it never selects a response. */
internal object ConversationPerspective {
    private val secondPerson = Regex("\\b(?:tu|tus|te|ti|contigo|[a-z]+(?:ar|er|ir)te)\\b")
    private val nonVerbs = setOf("suerte", "muerte", "fuerte")

    fun addressesAria(message: String): Boolean = secondPerson.findAll(normalize(
        RoleplayInterpreter.spokenText(message))).any { it.value !in nonVerbs }

    /** A brief answer to ARIA's question can clarify its subject without repeating the noun. */
    fun shortClarification(message: String, previousAria: String?): Boolean {
        if (previousAria?.contains('?') != true) return false
        val words = normalize(RoleplayInterpreter.spokenText(message)).split(Regex("[^a-z0-9]+"))
            .filter(String::isNotBlank)
        if (words.size !in 1..8) return false
        val normalized = normalize(RoleplayInterpreter.spokenText(message))
            .trimStart('.', ' ', '¿', '¡')
            .replaceFirst(Regex("^(?:mmm|eh)[.\\s]*"), "")
        return secondPerson.find(normalized)?.let { it.range.first == 0 && it.value !in nonVerbs } == true
    }

    fun guidance(message: String): String? = if (addressesAria(message))
        "REFERENTES: en el mensaje actual Kura se dirige a ARIA; «tú/tu/te» y los verbos dirigidos a ti se refieren a ARIA, «yo/me/mi» a Kura. Responde desde tu perspectiva a la intención de Kura sin atribuirle lo que te pide o imagina para ti. Una propuesta no significa que ya ocurrió."
    else null

    private fun normalize(text: String): String = Normalizer.normalize(
        text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
}
