package com.kura.aria.voice

import com.kura.aria.emotion.AriaEmotion
import com.kura.aria.personality.ExpressionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AriaVoiceDirectorTest {
    @Test fun emotionalDirectionsRemainSubtle() {
        AriaEmotion.entries.forEach { emotion ->
            val direction = AriaVoiceDirector.forEmotion(emotion)
            assertTrue(direction.rate in 0.90f..1.08f)
            assertTrue(direction.pitch in 0.97f..1.04f)
        }
        assertTrue(AriaVoiceDirector.forEmotion(AriaEmotion.SAD).rate <
            AriaVoiceDirector.forEmotion(AriaEmotion.HAPPY).rate)
    }

    @Test fun cloudDirectionCarriesEmotionIntensityAndExpressionWithoutChangingText() {
        val direction = AriaVoiceDirector.forCloud(AriaEmotion.HAPPY, 0.72f, ExpressionStyle.PLAYFUL)
        assertEquals("happy", direction.emotion)
        assertEquals(0.72f, direction.intensity)
        assertEquals("playful", direction.expressionStyle)
        assertTrue(direction.styleDescription.contains("alegre"))
    }

    @Test fun cloudIntensityIsBoundedAndPreviewSupportsRealProviderStyles() {
        assertEquals(1f, AriaVoiceDirector.forCloud(AriaEmotion.EXCITED, 2f, ExpressionStyle.EXCITED).intensity)
        assertEquals(0f, AriaVoiceDirector.forCloud(AriaEmotion.SAD, -1f, ExpressionStyle.COMFORTING).intensity)
        assertEquals("whisper", AriaVoiceDirector.forPreview("whisper").expressionStyle)
        assertEquals("soft", AriaVoiceDirector.forPreview("soft").expressionStyle)
        assertEquals(listOf("Leda", "Achernar", "Vindemiatrix", "Sulafat", "Aoede"),
            AriaVoiceDirector.cloudCandidates.map { it.id })
    }

    @Test fun androidVoiceDirectorUsesExplicitLocalVoiceAndSafeIdentityBounds() {
        assertEquals("es-us-x-esc-local", AndroidVoiceDirector.ARIA_VOICE_ID)
        val directions = AriaEmotion.values().map {
            AndroidVoiceDirector.forEmotion(it, ExpressionStyle.NATURAL)
        }
        assertTrue(directions.all { it.pitch in 1.02f..1.12f })
        assertTrue(directions.all { it.rate in 0.91f..1.10f })
        assertTrue(directions.all { it.volume in 0.86f..1f })
        assertTrue(AndroidVoiceDirector.forEmotion(AriaEmotion.HAPPY, ExpressionStyle.PLAYFUL).pitch >=
            AndroidVoiceDirector.forEmotion(AriaEmotion.NEUTRAL, ExpressionStyle.NATURAL).pitch)
    }

    @Test fun localSpeechDirectorKeepsActionsOutOfSpokenText() {
        assertEquals("Hola Kura", spokenTextForCloud("*sonríe* Hola Kura"))
        assertTrue(spokenTextForCloud("*sonríe*").isBlank())
    }
    @Test fun androidContrastProfilesRemainDistinctAndBounded() {
        val profiles = listOf(
            AriaEmotion.NEUTRAL, AriaEmotion.HAPPY, AriaEmotion.EMBARRASSED,
            AriaEmotion.ANGRY, AriaEmotion.SAD, AriaEmotion.EXCITED
        ).map { emotion ->
            AndroidVoiceDirector.forEmotion(emotion, AriaEmotion.styleFor(emotion, ExpressionStyle.NATURAL))
        }
        assertEquals(6, profiles.distinct().size)
        assertTrue(profiles.all { it.pitch in 1.02f..1.12f })
        assertTrue(profiles.all { it.rate in 0.91f..1.10f })
        assertTrue(profiles.all { it.volume in 0.86f..1f })
    }
}
