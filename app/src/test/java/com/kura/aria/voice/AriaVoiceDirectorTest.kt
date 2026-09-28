package com.kura.aria.voice

import com.kura.aria.emotion.AriaEmotion
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
}
