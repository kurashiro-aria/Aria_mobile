package com.kura.aria.voice

import org.junit.Assert.*
import org.junit.Test

class WakePhraseParserTest {
    @Test fun parsesWakeWithoutCommand() {
        assertNull(WakePhraseParser.parse("Oye Aria")?.command)
    }

    @Test fun preservesInlineCommand() {
        assertEquals("pon música", WakePhraseParser.parse("Oye Aria, pon música")?.command)
    }

    @Test fun acceptsAccentAndPunctuation() {
        assertEquals("¿qué hora es?", WakePhraseParser.parse("Óye ÁRIA: ¿qué hora es?")?.command)
    }

    @Test fun rejectsUnrelatedSpeech() {
        assertNull(WakePhraseParser.parse("hola Kura"))
    }
}
