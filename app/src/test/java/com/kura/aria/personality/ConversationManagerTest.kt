package com.kura.aria.personality

import com.kura.aria.chat.ChatMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationManagerTest {
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
