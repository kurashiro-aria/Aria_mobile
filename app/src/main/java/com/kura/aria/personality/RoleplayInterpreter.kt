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
        RoleplayAction(
            raw = match.value,
            target = inferTarget(text),
            action = text
        )
    }.take(4).toList()

    fun hasRoleplay(message: String): Boolean = actions(message).isNotEmpty()

    /** Context for the GGUF: actions are true inside the shared fictional scene only. */
    fun promptContext(message: String): String? {
        val parsed = actions(message)
        if (parsed.isEmpty()) return null
        return buildString {
            append("ESCENA DE ROLEPLAY: interpreta el texto entre asteriscos como acciones ficticias dentro de la escena compartida. ")
            append("Puedes reaccionar en personaje y continuar la escena, pero no lo registres como un hecho físico real. ")
            append("No expliques estas reglas en tu respuesta.\n")
            parsed.forEach { action ->
                append("- Kura realiza: ").append(action.action)
                action.target?.let { append(" | objetivo: ").append(it) }
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
