package com.kura.aria.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTtsVoiceProbeTest {
    @Test
    fun presetsStayModerateAndDistinct() {
        assertEquals(1.00f, AndroidTtsPreset.NATURAL.pitch, 0.001f)
        assertEquals(1.06f, AndroidTtsPreset.ARIA_YOUNG.pitch, 0.001f)
        assertEquals(1.10f, AndroidTtsPreset.ARIA_LIGHT.pitch, 0.001f)
        assertTrue(AndroidTtsPreset.all.all { it.pitch in 0.90f..1.15f && it.rate in 0.90f..1.15f })
    }

    @Test
    fun spanishCandidatesArePresentedBeforeOtherLocales() {
        val spanish = AndroidTtsVoice("es", "Spanish", "es-MX", false, true, 400, 100)
        val chile = spanish.copy(localeTag = "es-CL")
        val english = spanish.copy(localeTag = "en-US")
        assertTrue(AndroidTtsVoiceCatalog.priority(chile) > AndroidTtsVoiceCatalog.priority(spanish))
        assertTrue(AndroidTtsVoiceCatalog.priority(spanish) > AndroidTtsVoiceCatalog.priority(english))
    }

    @Test
    fun previewUsesExistingSpeechSanitization() {
        assertEquals("Hola Kura", spokenTextForCloud("*sonríe* Hola Kura"))
        assertEquals("Hola Kura", spokenTextForCloud("*se acerca* Hola *sonríe* Kura"))
        assertEquals("", spokenTextForCloud("*sonríe*"))
    }
}
