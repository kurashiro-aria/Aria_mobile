package com.kura.aria.personality

import com.kura.aria.memory.Memory
import com.kura.aria.memory.MemoryFacts

/** A small, reversible view of preferences Kura explicitly asked ARIA to remember. */
internal object RelationshipState {
    fun from(memories: List<Memory>): StylePreferences {
        val preferred = mutableSetOf<ExpressionStyle>()
        val avoided = mutableSetOf<ExpressionStyle>()
        val gentle = mutableSetOf<ExpressionStyle>()
        MemoryFacts.current(memories).forEach { memory ->
            if (memory.category !in setOf("preferencia_conversacion", "preferencia")) return@forEach
            val text = MemoryFacts.normalize(memory.content)
            val style = when {
                Regex("\\bcoquete(?:o|es|as|ar)\\b").containsMatchIn(text) -> ExpressionStyle.FLIRTY
                Regex("\\b(?:sarcasmo|sarcastic[ao])\\b").containsMatchIn(text) -> ExpressionStyle.SARCASTIC_LIGHT
                Regex("\\b(?:molestes|molestar|provocaciones)\\b").containsMatchIn(text) -> ExpressionStyle.TEASING
                Regex("\\b(?:bromas?|bromees|bromea|bromeas)\\b").containsMatchIn(text) -> ExpressionStyle.PLAYFUL
                Regex("\\b(?:carino|carinosa)\\b").containsMatchIn(text) -> ExpressionStyle.AFFECTIONATE
                else -> null
            } ?: return@forEach
            val negative = Regex("^(?:no me gusta|odio|prefiero que no|kura no prefiere)\\b").containsMatchIn(text)
            val positive = Regex("^(?:me gusta|prefiero|kura prefiere|kura disfruta)\\b").containsMatchIn(text)
            if (negative) avoided += style else if (positive) {
                preferred += style
                if (Regex("\\b(?:ligero|ligera|suave|un poco|poco)\\b").containsMatchIn(text))
                    gentle += style
            }
        }
        return StylePreferences(preferred - avoided, avoided, gentle - avoided)
    }
}
