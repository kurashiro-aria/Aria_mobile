package com.kura.aria.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudVoiceTextTest {
    @Test fun removesSingleStageDirectionForSpeechOnly() {
        assertEquals("Me alegra verte, Kura.", spokenTextForCloud("*sonríe suavemente* Me alegra verte, Kura."))
    }

    @Test fun removesSeveralDirectionsAndKeepsVisibleWords() {
        assertEquals("Hola Kura", spokenTextForCloud("*se acerca* Hola *sonríe* Kura"))
        assertEquals("Bueno... quizás tengas razón", spokenTextForCloud("Bueno... *mira hacia otro lado* quizás tengas razón"))
    }

    @Test fun onlyStageDirectionProducesNoSpeech() {
        assertTrue(spokenTextForCloud("*se ríe suavemente*").isEmpty())
    }

    @Test fun ordinaryTextRemainsVerbatimApartFromWhitespaceNormalization() {
        assertEquals("Hola, Kura.", spokenTextForCloud("Hola, Kura."))
    }

    @Test fun speechChunksKeepNaturalSentenceOrder() {
        assertEquals(
            listOf("Primera frase.", "Segunda frase!", "Tercera frase?"),
            speechChunks("Primera frase. Segunda frase! Tercera frase?")
        )
    }

    @Test fun speechChunksBoundLongSentencesWithoutTinyFragments() {
        val chunks = speechChunks("Una frase bastante larga que debe dividirse para preparar el audio sin esperar toda la respuesta.", 55)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 55 })
        assertEquals(
            "Una frase bastante larga que debe dividirse para preparar el audio sin esperar toda la respuesta.",
            chunks.joinToString(" ")
        )
    }

    @Test fun speechChunksIgnoreActionOnlyResponses() {
        assertTrue(speechChunks("*sonríe suavemente*").isEmpty())
    }
}
