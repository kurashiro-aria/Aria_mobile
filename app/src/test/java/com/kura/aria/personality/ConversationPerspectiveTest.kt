package com.kura.aria.personality

import com.kura.aria.chat.ChatMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPerspectiveTest {
    @Test fun directAddressPointsAtAriaWithoutInventingAnEvent() {
        assertTrue(ConversationPerspective.addressesAria("mmm...verte desnuda?"))
        assertTrue(ConversationPerspective.addressesAria("tú sin nada de ropa"))
        assertTrue(ConversationPerspective.addressesAria("me gustaría escucharte cantar"))
        assertFalse(ConversationPerspective.addressesAria("yo quiero cantar"))
        assertFalse(ConversationPerspective.addressesAria("qué suerte tuvimos"))
        val guidance = ConversationPerspective.guidance("¿puedo verte bailar?")!!
        assertTrue(guidance.contains("se refieren a ARIA"))
        assertTrue(guidance.contains("no significa que ya ocurrió"))
    }

    @Test fun shortClarificationKeepsThePreviousQuestionWithoutDraggingUnrelatedTurns() {
        assertTrue(ConversationPerspective.shortClarification("mmm...verte desnuda?", "¿Cómo que fuera?"))
        assertTrue(ConversationPerspective.shortClarification("tu sin nada de ropa", "¿A qué te refieres?"))
        assertFalse(ConversationPerspective.shortClarification("tu sin nada de ropa", "Entiendo."))
        assertFalse(ConversationPerspective.shortClarification("yo saldré por la tarde", "¿Qué quieres hacer?"))
    }

    @Test fun briefCorrectionPreservesSubjectAndActorInTurnPrompt() {
        val history = listOf(
            ChatMessage("Kura", "mmm...verte desnuda?", 1L),
            ChatMessage("ARIA", "¿Te refieres a la escena que imaginamos?", 2L)
        )
        val prompt = ConversationContext.turnPrompt(history, emptyList(), "tu sin nada de ropa")
        assertTrue(prompt.contains("• «mmm...verte desnuda?»"))
        assertTrue(prompt.contains("¿Te refieres a la escena que imaginamos?"))
        assertTrue(prompt.contains("REFERENTES:"))
        assertTrue(prompt.endsWith("MENSAJE ACTUAL DE KURA:\ntu sin nada de ropa"))
    }
}
