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

    @Test fun roleplayRetryKeepsActorPerspectiveWithoutCopyingPreviousAction() {
        val action = "*acaricio la cabeza de aria*"
        val history = listOf(ChatMessage("Kura", "*abrazo a aria*", 1L),
            ChatMessage("ARIA", "*acaricio la cabeza de aria*", 2L))
        assertTrue(ReplyQuality.needsRetry(action, history.last().text, action))
        val retry = ReplyQuality.retryPrompt(history, action)
        assertTrue(retry.contains("acciones ficticias de Kura"))
        assertFalse(retry.contains("*abrazo a aria*"))
        assertEquals(1, Regex(Regex.escape(action)).findAll(retry).count())
    }

    @Test fun rejectsScreenshotTranscriptAndFreshTurnDoesNotCarryOldTopicIntoRetry() {
        val transcript = "Kura: hoy hace calor\nKura: agradable,no hace calor\n\nARIA: ¿Cómo te ha ido?"
        assertTrue(ReplyQuality.hasTranscript(transcript))
        assertTrue(ReplyQuality.needsRetry("hoy hace calor", "Hola, ¿cómo te ha ido?", transcript))
        assertTrue(ReplyQuality.needsRetry("hoy hace calor", null, "hoy hace calor"))
        assertFalse(ReplyQuality.hasTranscript("ARIA: Uf, sí que aprieta el calor hoy."))
        val previous = listOf(ChatMessage("Kura", "hola aria,que tal tu día?", 1L))
        assertFalse(ReplyQuality.retryPrompt(previous, "hoy hace calor").contains("hola aria"))
    }
}
