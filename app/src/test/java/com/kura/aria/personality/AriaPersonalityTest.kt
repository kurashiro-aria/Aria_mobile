package com.kura.aria.personality

import com.kura.aria.chat.ChatMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AriaPersonalityTest {
    @Test fun recentConversationIsBoundedAndOrdered() {
        val messages = (1..9).map { ChatMessage("Kura", "Mensaje $it " + "x".repeat(250), it.toLong()) }
        val prompt = AriaPersonality.promptWithRecentConversation(messages)

        assertFalse(prompt.contains("Mensaje 3"))
        assertTrue(prompt.indexOf("Mensaje 4") < prompt.indexOf("Mensaje 9"))
        assertTrue(prompt.contains("Mensaje 9"))
        assertFalse(prompt.contains("x".repeat(200)))
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
}
