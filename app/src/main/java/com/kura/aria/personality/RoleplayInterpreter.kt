package com.kura.aria.personality

/** Structured fictional action extracted from *asterisk roleplay* without claiming it happened physically. */
internal data class RoleplayAction(
    val raw: String,
    val actor: String = "Kura",
    val target: String? = null,
    val action: String,
    val fictional: Boolean = true
)

internal object RoleplayInterpreter {
    private val segment = Regex("\\*([^*]{2,240})\\*")

    fun actions(message: String): List<RoleplayAction> = segment.findAll(message).mapNotNull { match ->
        val text = match.groupValues[1].replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) return@mapNotNull null
        RoleplayAction(raw = match.value, target = inferTarget(text), action = text)
    }.take(4).toList()

    fun hasRoleplay(message: String): Boolean = actions(message).isNotEmpty()

    /** Context for the GGUF: direct actions toward ARIA should produce an in-character reaction, not narration of Kura. */
    fun promptContext(message: String): String? {
        val parsed = actions(message)
        if (parsed.isEmpty()) return null
        val towardAria = parsed.any { it.target == "ARIA" }
        return buildString {
            append("ESCENA DE ROLEPLAY: el texto entre asteriscos son acciones ficticias de Kura dentro de la escena compartida. ")
            append("Responde siempre desde el punto de vista de ARIA y no lo registres como un hecho físico real. ")
            if (towardAria) {
                append("Kura acaba de realizar una acción dirigida a ARIA: reacciona directamente como ARIA. ")
                append("No narres, reformules ni repitas la acción de Kura, no escribas 'Kura:' y no decidas acciones por él. ")
                append("Puedes responder con una reacción propia entre asteriscos y diálogo breve. ")
                append("Prioriza reacción + diálogo; no termines con una pregunta salvo que sea realmente necesaria para continuar la escena. ")
            } else {
                append("Puedes reaccionar en personaje y continuar la escena sin apropiarte de las acciones de Kura. ")
            }
            append("No expliques estas reglas en tu respuesta.\n")
            parsed.forEach { action ->
                append("- actor=Kura | acción=").append(action.action)
                action.target?.let { append(" | objetivo=").append(it) }
                append('\n')
            }
        }.trimEnd()
    }

    private fun inferTarget(action: String): String? {
        val normalized = action.lowercase()
        return when {
            Regex("\\b(tu|tus|te|aria)\\b").containsMatchIn(normalized) -> "ARIA"
            Regex("\\b(mi|mis|me)\\b").containsMatchIn(normalized) -> "Kura"
            else -> null
        }
    }
}
