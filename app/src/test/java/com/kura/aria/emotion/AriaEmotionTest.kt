package com.kura.aria.emotion

import org.junit.Assert.assertEquals
import org.junit.Test

class AriaEmotionTest {
    @Test fun recognizesTheFourPilotExpressions() {
        assertEquals(AriaEmotion.NEUTRAL, AriaEmotion.fromReply("De acuerdo, lo reviso."))
        assertEquals(AriaEmotion.HAPPY, AriaEmotion.fromReply("Genial, quedó listo."))
        assertEquals(AriaEmotion.EXCITED, AriaEmotion.fromReply("¡Lo logramos! Funcionó."))
        assertEquals(AriaEmotion.THINKING, AriaEmotion.fromReply("Hmm, interesante."))
    }
}
