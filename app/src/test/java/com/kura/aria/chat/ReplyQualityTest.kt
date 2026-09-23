package com.kura.aria.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyQualityTest {
    @Test fun catchesRepeatedOfferFromTheReportedConversation() {
        val prior = "ARIA: Jajaja, ¿cómo te sientes? Si odias la calor, quizás necesitas un refugio más fresco. ¿Te gustaría que te llevara a un lugar donde el clima sea más amigable?"
        val repeated = "ARIA: Jajaja, sí odias la calor, quizás necesitas un refugio más fresco. ¿Te gustaría que te llevara a un lugar donde el clima sea más amigable?"
        assertTrue(ReplyQuality.repeats(prior, repeated))
    }

    @Test fun allowsNewResponseAboutTheSameSubject() {
        val prior = "Si odias el calor, quizás necesitas un refugio más fresco."
        assertFalse(ReplyQuality.repeats(prior, "Uf, busca sombra y toma algo frío. Hoy está pesado."))
    }

    @Test fun retryDoesNotPasteAriaPreviousAnswer() {
        val history = listOf(
            ChatMessage("Kura", "con mucha calor", 1),
            ChatMessage("ARIA", "Quizás necesitas un refugio más fresco", 2)
        )
        val prompt = ReplyQuality.retryPrompt(history, "sí")
        assertTrue(prompt.contains("con mucha calor"))
        assertFalse(prompt.contains("refugio más fresco"))
        assertEquals("Vale, seguimos con eso.", ReplyQuality.fallback("sí"))
    }
}
