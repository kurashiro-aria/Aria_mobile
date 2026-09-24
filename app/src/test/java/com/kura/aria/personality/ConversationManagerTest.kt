package com.kura.aria.personality

import com.kura.aria.chat.ChatMessage
import com.kura.aria.emotion.ConversationMood
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationManagerTest {
    @Test fun socialToneHoldsAcrossRepliesThenYieldsToWorkAndUrgency() {
        val playful = ConversationState().afterExchange("Jajaja, te pillé", "Jeje, ya me viste.", 1_000L)
        assertTrue(playful.socialMood == ConversationMood.PLAYFUL)
        val followUp = playful.afterExchange("sí", "Claro.", 2_000L)
        assertTrue(followUp.socialMood == ConversationMood.PLAYFUL)
        val working = followUp.afterExchange("Revisa el código del proyecto", "Voy a revisarlo.", 3_000L)
        assertTrue(working.socialMood == ConversationMood.FOCUSED)
        val urgent = working.afterExchange("Tuve un accidente", "Voy contigo paso a paso.", 4_000L)
        assertTrue(urgent.socialMood == ConversationMood.URGENT)
        assertTrue(ConversationContext.turnPrompt(emptyList(), emptyList(), "Hay una emergencia", urgent)
            .contains("TONO DE ESTE TURNO:"))
    }

    @Test fun socialToneExpiresAfterIdleOrSeveralNeutralTurns() {
        val start = ConversationState().afterExchange("Jajaja, qué broma", "Me hiciste reír.", 1_000L)
        val late = start.afterExchange("sí", "Entendido.", 31 * 60 * 1000L + 2_000L)
        assertTrue(late.socialMood == ConversationMood.NEUTRAL)
        val one = start.afterExchange("sí", "Entendido.", 2_000L)
        val two = one.afterExchange("exacto", "De acuerdo.", 3_000L)
        val three = two.afterExchange("vale", "Vamos.", 4_000L)
        assertTrue(three.afterExchange("sí", "Perfecto.", 5_000L).socialMood == ConversationMood.NEUTRAL)
    }

    @Test fun resumesTopicAfterManyTurnsWithoutReplayingAria() {
        var state = ConversationState()
        state = state.afterExchange("Estoy dibujando el manga de Sora", "¿Qué escena dibujas?", 1)
        (1..16).forEach { turn ->
            state = state.afterExchange("Hablemos de cocina receta $turn", "Claro, sigamos.", turn.toLong() + 1)
        }
        val history = (1..16).flatMap { turn ->
            listOf(ChatMessage("Kura", "Hablemos de cocina receta $turn", turn.toLong()),
                ChatMessage("ARIA", "Una receta distinta $turn", turn.toLong()))
        }
        val prompt = ConversationContext.turnPrompt(history, emptyList(), "Retomemos el manga de Sora", state)
        assertTrue(prompt.contains("Estoy dibujando el manga de Sora"))
        assertFalse(prompt.contains("Una receta distinta"))
    }

    @Test fun greetingDoesNotDragOldTopicOrPendingQuestion() {
        val state = ConversationState().afterExchange("No me gusta el café", "¿Quieres café?", 1)
        val prompt = ConversationContext.turnPrompt(emptyList(), emptyList(), "hola aria", state)
        assertFalse(prompt.contains("café"))
    }

    @Test fun shortAssentUsesQuestionWithoutEarlierAnswer() {
        val state = ConversationState().afterExchange("Dibujo el manga", "La pose se ve bien. ¿Quieres cambiar el fondo?", 1)
        val prompt = ConversationContext.turnPrompt(emptyList(), emptyList(), "sí", state)
        assertTrue(prompt.contains("¿Quieres cambiar el fondo?"))
        assertFalse(prompt.contains("La pose se ve bien"))
    }
}
