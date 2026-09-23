package com.kura.aria.emotion

import com.kura.aria.chat.ChatMessage
import com.kura.aria.personality.ConversationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMoodTest {
    @Test fun followsFeelingForShortAnswerButResetsOnNewTopic() {
        assertEquals(ConversationMood.VULNERABLE, MoodReader.forTurn("sí", "Estoy triste hoy"))
        assertEquals(AriaEmotion.SAD, AriaEmotion.fromExchange("sí", "Entiendo.", "Estoy triste hoy"))
        assertEquals(ConversationMood.NEUTRAL, MoodReader.forTurn("Hablemos del manga", "Estoy triste hoy"))
        assertEquals(AriaEmotion.NEUTRAL, AriaEmotion.fromExchange("Hablemos del manga", "De acuerdo."))
    }

    @Test fun contextGuidesCareWithoutReplayingThePreviousReply() {
        val history = listOf(ChatMessage("Kura", "Estoy triste hoy", 1),
            ChatMessage("ARIA", "Me preocupa lo que cuentas. ¿Quieres hablar?", 2))
        val prompt = ConversationContext.turnPrompt(history, emptyList(), "sí")
        assertTrue(prompt.contains("TONO DE ESTE TURNO:"))
        assertTrue(prompt.contains("¿Quieres hablar?"))
        assertFalse(prompt.contains("Me preocupa lo que cuentas"))
    }

    @Test fun seriousProblemsBeatReplyKeywordsAndNegationDoesNotMarkSadness() {
        assertEquals(AriaEmotion.SERIOUS, AriaEmotion.fromExchange("Tuve un accidente", "Genial, vamos."))
        assertEquals(ConversationMood.NEUTRAL, MoodReader.forTurn("No estoy triste"))
        assertEquals(ConversationMood.PLAYFUL, MoodReader.forTurn("Odio el calor jaja"))
        assertEquals(AriaEmotion.SERIOUS, AriaEmotion.fromExchange("La app no funciona", "Lo siento."))
    }
}
