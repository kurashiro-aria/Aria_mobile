package com.kura.aria.personality

import com.kura.aria.chat.ChatMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AriaPersonalityTest {
    @Test fun recentConversationIsBoundedAndOrdered() {
        val messages = (1..14).map { ChatMessage("Kura", "Mensaje $it " + "x".repeat(250), it.toLong()) }
        val prompt = AriaPersonality.promptWithRecentConversation(messages)

        assertFalse(prompt.contains("Mensaje 4 "))
        assertTrue(prompt.indexOf("Mensaje 5 ") < prompt.indexOf("Mensaje 14 "))
        assertTrue(prompt.contains("Mensaje 14 "))
        assertFalse(prompt.contains("x".repeat(250)))
    }

    @Test fun emptyHistoryDoesNotInventConversation() {
        assertFalse(AriaPersonality.promptWithRecentConversation(emptyList()).contains("Mensajes recientes"))
    }

    @Test fun coreMemoryAndFastModeAreAvailableWithoutChatHistory() {
        val prompt = AriaPersonality.promptWithRecentConversation(emptyList())
        assertTrue(prompt.contains("Adaptive Reasoning & Interactive Assistant"))
        assertTrue(prompt.contains("22 de septiembre de 2026"))
        assertTrue(prompt.endsWith("/no_think"))
    }

    @Test fun previousModelRepliesProvideConversationalContinuity() {
        val prompt = AriaPersonality.promptWithRecentConversation(listOf(
            ChatMessage("Kura", "¿Quién es Sol?", 1),
            ChatMessage("ARIA", "Sol nos ayuda con el código de la aplicación.", 2)
        ))
        assertTrue(prompt.contains("¿Quién es Sol?"))
        assertTrue(prompt.contains("ARIA: Sol nos ayuda con el código"))
    }

    @Test fun currentTurnKeepsNonThinkingInstructionLast() {
        val prompt = AriaPersonality.directResponsePrompt("¿Cómo te fue? /think")
        assertTrue(prompt.endsWith("/no_think"))
    }

    @Test fun relatedOlderExchangeSurvivesRestartWithoutUnrelatedHistory() {
        val messages = listOf(
            ChatMessage("Kura", "El proyecto del manga tiene una mazmorra", 1),
            ChatMessage("ARIA", "Sora y Rika entran juntos.", 2),
            ChatMessage("Kura", "Tengo que comprar pintura", 3),
            ChatMessage("ARIA", "Anotado para esta charla.", 4)
        ) + (5..14).map { ChatMessage("Kura", "Charla diaria $it", it.toLong()) } +
            ChatMessage("Kura", "Volvamos al manga y la mazmorra", 15)
        val prompt = AriaPersonality.promptWithRecentConversation(messages)
        assertTrue(prompt.contains("Sora y Rika entran juntos"))
        assertFalse(prompt.contains("Tengo que comprar pintura"))
    }
}
