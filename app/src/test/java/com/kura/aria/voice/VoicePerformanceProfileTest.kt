package com.kura.aria.voice

import com.kura.aria.emotion.AriaEmotion
import com.kura.aria.emotion.ConversationMood
import com.kura.aria.personality.ExpressionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePerformanceProfileTest {
    @Test fun labOffersOnlyTheVerifiedCompatibleMexicanFemaleModel() {
        assertEquals("es_MX-claude-high", PiperSpanishPrototype.MODEL_ID)
        assertEquals(8, VoiceLabPreset.entries.size)
    }

    @Test fun allEightEmotionalPresetsMapToBoundedRealControls() {
        val profiles = VoiceLabPreset.entries.map {
            VoicePerformanceProfile.from(it.emotion, it.mood, it.expressionStyle)
        }
        assertTrue(profiles.all { it.speed in 0.85f..1.15f })
        assertTrue(profiles.all { it.pauseBetweenSentencesMs in 100..700 })
        assertTrue(profiles.distinct().size > 1)
        assertNotEquals(profiles.first().speed, profiles[VoiceLabPreset.EXCITED.ordinal].speed)
    }

    @Test fun emotionMoodAndStyleChangeTheResolvedProfile() {
        val neutral = VoicePerformanceProfile.from(AriaEmotion.NEUTRAL, ConversationMood.NEUTRAL, ExpressionStyle.NATURAL)
        val sad = VoicePerformanceProfile.from(AriaEmotion.SAD, ConversationMood.VULNERABLE, ExpressionStyle.COMFORTING)
        val playful = VoicePerformanceProfile.from(AriaEmotion.PLAYFUL, ConversationMood.PLAYFUL, ExpressionStyle.SARCASTIC_LIGHT)
        assertNotEquals(neutral, sad)
        assertNotEquals(neutral, playful)
    }
}
