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
        assertFalse(AriaPersonality.promptWithRecentConversation(emptyList()).contains("Fragmentos recientes"))
    }
}
