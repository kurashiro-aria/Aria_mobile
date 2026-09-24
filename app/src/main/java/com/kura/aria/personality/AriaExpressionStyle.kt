package com.kura.aria.personality

import com.kura.aria.emotion.ConversationMood
import java.text.Normalizer

/** How ARIA speaks this turn. It is separate from Kura's mood and from ARIA's portrait. */
internal enum class ExpressionStyle {
    NATURAL, PLAYFUL, FLIRTY, TEASING, SARCASTIC_LIGHT, AFFECTIONATE,
    SHY, SERIOUS, FOCUSED, EXCITED, COMFORTING
}

internal data class ExpressionState(
    val style: ExpressionStyle = ExpressionStyle.NATURAL,
    val intensity: Float = 0f,
    val turns: Int = 0,
    val requestedByKura: Boolean = false
)

/** Future relationship preferences can bias a suggestion, never force a lasting mode. */
internal data class StylePreferences(
    val preferred: Set<ExpressionStyle> = emptySet(),
    val avoided: Set<ExpressionStyle> = emptySet()
)

internal object ExpressionResolver {
    private fun normalize(text: String) = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").trim()

    fun forTurn(current: String, mood: ConversationMood, previous: ExpressionState = ExpressionState(),
                previousAt: Long = 0L, now: Long = System.currentTimeMillis(),
                preferences: StylePreferences = StylePreferences()): ExpressionState {
        val text = normalize(current)
        // Safety and Kura's current boundary always outrank a previous invitation to joke or flirt.
        if (mood == ConversationMood.URGENT || mood == ConversationMood.VULNERABLE ||
            Regex("\\b(?:fuera de bromas|hablando en serio|esto es serio|me preocupa|me paso algo grave)\\b")
                .containsMatchIn(text)) return ExpressionState(
            if (mood == ConversationMood.VULNERABLE || text.contains("me preocupa"))
                ExpressionStyle.COMFORTING else ExpressionStyle.SERIOUS, 0.75f)
        if (Regex("\\b(?:ya no coquetees|deja de coquetear|no coquetees|deja de molestarme|no me molestes|ya basta de bromas|sin bromas|hablame normal|habla normal|como siempre)\\b")
                .containsMatchIn(text)) return ExpressionState(requestedByKura = true)
        if (Regex("\\b(?:ponte seria|habla en serio|se seria|ahora en serio)\\b")
                .containsMatchIn(text)) return ExpressionState(ExpressionStyle.SERIOUS, 0.7f, requestedByKura = true)
        if (mood == ConversationMood.FRUSTRATED || mood == ConversationMood.FOCUSED)
            return ExpressionState(ExpressionStyle.FOCUSED, 0.65f)

        val requested = when {
            Regex("\\b(?:coquetea|coquetees|coqueteo|lig(a|ue) conmigo|hablame coqueto|ponte coqueta)\\b")
                .containsMatchIn(text) -> ExpressionStyle.FLIRTY
            Regex("\\b(?:molestame|vacilame|burlate un poco|tomame el pelo|provocame)\\b")
                .containsMatchIn(text) -> ExpressionStyle.TEASING
            Regex("\\b(?:sarcasmo|sarcastica|ironica|ironia)\\b").containsMatchIn(text) -> ExpressionStyle.SARCASTIC_LIGHT
            Regex("\\b(?:bromea|hazme reir|hagamos bromas|se juguetona)\\b").containsMatchIn(text) -> ExpressionStyle.PLAYFUL
            Regex("\\b(?:hablame con carino|se carinosa|dame carino)\\b").containsMatchIn(text) -> ExpressionStyle.AFFECTIONATE
            else -> null
        }
        if (requested != null) return ExpressionState(requested,
            if (Regex("\\b(?:mas|mucho|bastante)\\b").containsMatchIn(text)) 0.75f else 0.5f,
            requestedByKura = true)

        val recent = previousAt > 0 && now - previousAt in 0..30 * 60 * 1000L
        val holdLimit = when (previous.style) {
            ExpressionStyle.SERIOUS, ExpressionStyle.FOCUSED -> 3
            ExpressionStyle.EXCITED -> 1
            else -> 5
        }
        if (recent && previous.requestedByKura && previous.turns < holdLimit)
            return previous.copy(intensity = (previous.intensity - 0.04f).coerceAtLeast(0.3f),
                turns = previous.turns + 1)

        val contextual = when {
            Regex("\\b(?:me gustas|eres linda|que guapa|te ves linda|un beso)\\b").containsMatchIn(text) ->
                ExpressionStyle.FLIRTY
            mood == ConversationMood.PLAYFUL && recent && previous.style == ExpressionStyle.PLAYFUL &&
                previous.turns >= 1 -> ExpressionStyle.TEASING
            mood == ConversationMood.PLAYFUL -> ExpressionStyle.PLAYFUL
            mood == ConversationMood.SHY -> ExpressionStyle.SHY
            mood == ConversationMood.JOYFUL -> ExpressionStyle.EXCITED
            mood == ConversationMood.TIRED -> ExpressionStyle.COMFORTING
            else -> ExpressionStyle.NATURAL
        }
        val safe = contextual.takeUnless { it in preferences.avoided } ?: ExpressionStyle.NATURAL
        val intensity = if (safe == ExpressionStyle.NATURAL) 0f
            else if (safe in preferences.preferred) 0.5f else 0.3f
        return ExpressionState(safe, intensity,
            turns = if (recent && safe == previous.style) (previous.turns + 1).coerceAtMost(5) else 0)
    }
}
