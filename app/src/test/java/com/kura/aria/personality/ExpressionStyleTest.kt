package com.kura.aria.personality

import com.kura.aria.emotion.AriaEmotion
import com.kura.aria.emotion.ConversationMood
import com.kura.aria.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionStyleTest {
    @Test fun invitationLastsSeveralTurnsThenStopsWithoutAResetCommand() {
        var state = ConversationState().afterExchange("Aria, coquetea conmigo", "Qué atrevido.", 1_000L)
        assertEquals(ExpressionStyle.FLIRTY, state.expression.style)
        repeat(5) { index ->
            state = state.afterExchange("sí", "Eso me gusta.", 2_000L + index * 1_000L)
            assertEquals(ExpressionStyle.FLIRTY, state.expression.style)
        }
        state = state.afterExchange("hablemos de otra cosa", "Claro.", 8_000L)
        assertEquals(ExpressionStyle.NATURAL, state.expression.style)
    }

    @Test fun explicitBoundariesAndSeriousContextOverrideFlirtingImmediately() {
        val flirty = ExpressionState(ExpressionStyle.FLIRTY, 0.7f, 2, true)
        assertEquals(ExpressionStyle.NATURAL, ExpressionResolver.forTurn("Ya no coquetees",
            ConversationMood.NEUTRAL, flirty, 1_000L, 2_000L).style)
        assertEquals(AriaEmotion.NEUTRAL, AriaEmotion.fromInteraction(ConversationMood.PLAYFUL,
            ExpressionResolver.forTurn("Ya no coquetees", ConversationMood.PLAYFUL, flirty, 1_000L, 2_000L),
            "Ya no coquetees", ""))
        assertEquals(ExpressionStyle.NATURAL, ExpressionResolver.forTurn("Háblame normal",
            ConversationMood.NEUTRAL, flirty, 1_000L, 2_000L).style)
        assertEquals(ExpressionStyle.SERIOUS, ExpressionResolver.forTurn("Jajaja, ya, ahora ponte seria",
            ConversationMood.PLAYFUL, flirty, 1_000L, 2_000L).style)
        assertEquals(AriaEmotion.SERIOUS, AriaEmotion.fromInteraction(ConversationMood.PLAYFUL,
            ExpressionState(ExpressionStyle.SERIOUS, 0.7f), "Jajaja, ponte seria", ""))
        assertEquals(ExpressionStyle.COMFORTING, ExpressionResolver.forTurn(
            "Fuera de bromas, hoy me pasó algo que me preocupa", ConversationMood.PLAYFUL,
            flirty, 1_000L, 2_000L).style)
    }

    @Test fun teasingAndPlayfulnessDifferAndCanAriseFromContext() {
        val teasing = ExpressionResolver.forTurn("Moléstame un poco", ConversationMood.NEUTRAL)
        val playful = ExpressionResolver.forTurn("Bromea conmigo", ConversationMood.NEUTRAL)
        assertEquals(ExpressionStyle.TEASING, teasing.style)
        assertEquals(ExpressionStyle.PLAYFUL, playful.style)
        val first = ExpressionResolver.forTurn("Jajaja", ConversationMood.PLAYFUL)
        val second = ExpressionResolver.forTurn("Jaja, sigue", ConversationMood.PLAYFUL,
            first.copy(turns = 1), 1_000L, 2_000L)
        assertEquals(ExpressionStyle.TEASING, second.style)
        assertEquals(ExpressionStyle.FLIRTY, ExpressionResolver.forTurn("Qué guapa eres",
            ConversationMood.NEUTRAL).style)
    }

    @Test fun moodAndStyleCanCombineWithoutMakingPortraitInappropriate() {
        val flirty = ExpressionState(ExpressionStyle.FLIRTY, 0.5f)
        assertEquals(AriaEmotion.EMBARRASSED, AriaEmotion.fromInteraction(
            ConversationMood.SHY, flirty, "Me da vergüenza", ""))
        assertEquals(AriaEmotion.PLAYFUL, AriaEmotion.fromInteraction(
            ConversationMood.PLAYFUL, flirty, "Jaja", ""))
        assertEquals(AriaEmotion.SERIOUS, AriaEmotion.fromInteraction(
            ConversationMood.URGENT, flirty, "Hay una emergencia", ""))
        val hint = PersonalityEngine.turnGuidance(ConversationMood.NEUTRAL, flirty)
        assertTrue(hint.contains("picardía"))
        assertFalse(hint.contains("0.5"))
        assertTrue(hint.length < 180)
    }

    @Test fun promptKeepsOneShortStyleHintAndTheRealMessageLast() {
        val state = ConversationState(expression = ExpressionState(ExpressionStyle.FLIRTY, 0.5f, 2, true),
            updatedAt = System.currentTimeMillis())
        val prompt = ConversationContext.turnPrompt(listOf(ChatMessage("Kura", "Bromeábamos", 1)),
            emptyList(), "sí", state)
        assertTrue(prompt.contains("picardía ligera"))
        assertEquals(1, "TONO DE ESTE TURNO:".toRegex().findAll(prompt).count())
        assertTrue(prompt.endsWith("MENSAJE ACTUAL DE KURA:\nsí"))
    }
}
