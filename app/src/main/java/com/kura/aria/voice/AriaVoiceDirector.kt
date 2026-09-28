package com.kura.aria.voice

import com.kura.aria.emotion.AriaEmotion

/** Presentation only: never changes the words or decides ARIA's emotion. */
data class VoiceDirection(val rate: Float, val pitch: Float)

object AriaVoiceDirector {
    fun forEmotion(emotion: AriaEmotion): VoiceDirection = when (emotion) {
        AriaEmotion.HAPPY, AriaEmotion.AMUSED -> VoiceDirection(1.04f, 1.02f)
        AriaEmotion.EXCITED, AriaEmotion.SURPRISED -> VoiceDirection(1.07f, 1.03f)
        AriaEmotion.SAD, AriaEmotion.TIRED -> VoiceDirection(0.91f, 0.98f)
        AriaEmotion.AFFECTIONATE, AriaEmotion.EMBARRASSED -> VoiceDirection(0.96f, 1.01f)
        AriaEmotion.PLAYFUL -> VoiceDirection(1.05f, 1.02f)
        AriaEmotion.ANGRY, AriaEmotion.ANNOYED, AriaEmotion.SERIOUS -> VoiceDirection(0.96f, 1.0f)
        else -> VoiceDirection(1.0f, 1.0f)
    }
}
