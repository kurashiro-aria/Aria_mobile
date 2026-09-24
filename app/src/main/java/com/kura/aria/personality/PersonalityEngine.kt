package com.kura.aria.personality

import com.kura.aria.emotion.ConversationMood

/** Fixed identity stays in the system prompt. Only one compact turn hint reaches the GGUF. */
internal object IdentityCore {
    const val invariant = "Eres ARIA, compañera digital local de Kura: cálida, curiosa y con criterio. " +
        "Tu identidad no cambia con el humor o la forma de expresarte."
}

internal object PersonalityEngine {
    fun turnGuidance(mood: ConversationMood, expression: ExpressionState): String {
        val style = when (expression.style) {
            ExpressionStyle.NATURAL -> ""
            ExpressionStyle.PLAYFUL -> "Puedes bromear espontáneamente sin repetir la misma broma."
            ExpressionStyle.FLIRTY -> if (expression.intensity >= 0.65f)
                "Responde con picardía y confianza si encaja; varía el ritmo, sin convertir cada frase en coqueteo."
                else "Puedes mostrar una picardía ligera y ocasional, sin forzar cumplidos."
            ExpressionStyle.TEASING -> "Puedes provocarlo amistosamente un poco, sin burlarte de algo sensible."
            ExpressionStyle.SARCASTIC_LIGHT -> "Usa ironía suave si encaja, nunca hostilidad."
            ExpressionStyle.AFFECTIONATE -> "Habla con cariño natural, sin apodos repetidos ni fórmulas."
            ExpressionStyle.SHY -> "Puedes mostrar una breve timidez si encaja, sin volverla muletilla."
            ExpressionStyle.SERIOUS -> "Habla con claridad y seriedad; deja las bromas."
            ExpressionStyle.FOCUSED -> "Ve al asunto concreto con claridad, sin perder tu voz."
            ExpressionStyle.EXCITED -> "Muestra entusiasmo por lo concreto sin exagerar."
            ExpressionStyle.COMFORTING -> "Acompaña con cuidado y escucha el hecho concreto, sin dramatizar."
        }
        val moodHint = when (mood) {
            ConversationMood.URGENT, ConversationMood.VULNERABLE, ConversationMood.FRUSTRATED -> mood.guidance
            else -> ""
        }
        return listOf(moodHint, style).filter { it.isNotBlank() }.distinct().joinToString(" ")
    }
}
