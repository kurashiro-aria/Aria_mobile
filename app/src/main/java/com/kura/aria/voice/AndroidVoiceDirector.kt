package com.kura.aria.voice

import com.kura.aria.emotion.AriaEmotion
import com.kura.aria.personality.ExpressionStyle

/**
 * Maps ARIA's real emotion/expression state to small Android TTS changes.
 * This is deliberately separate from CloudVoiceDirector and does not alter text.
 */
object AndroidVoiceDirector {
    const val ARIA_VOICE_ID = "es-us-x-esc-local"

    private const val BASE_PITCH = 1.06f
    private const val BASE_RATE = 1.00f

    /** Android TTS has no real whisper/emotion controls; these are gentle delivery hints. */
    internal fun forEmotion(emotion: AriaEmotion,
                            expression: ExpressionStyle = ExpressionStyle.NATURAL): VoiceDirection {
        val base = when (emotion) {
            AriaEmotion.HAPPY, AriaEmotion.AMUSED -> VoiceDirection(1.04f, 1.08f, 1f)
            AriaEmotion.PLAYFUL -> VoiceDirection(1.05f, 1.09f, 1f)
            AriaEmotion.EXCITED, AriaEmotion.SURPRISED -> VoiceDirection(1.08f, 1.10f, 1f)
            AriaEmotion.AFFECTIONATE -> VoiceDirection(0.95f, 1.07f, 0.94f)
            AriaEmotion.EMBARRASSED -> VoiceDirection(0.94f, 1.09f, 0.90f)
            AriaEmotion.SAD -> VoiceDirection(0.91f, 1.02f, 0.90f)
            AriaEmotion.TIRED -> VoiceDirection(0.92f, 1.03f, 0.92f)
            AriaEmotion.SERIOUS -> VoiceDirection(0.96f, 1.03f, 1f)
            AriaEmotion.ANGRY, AriaEmotion.ANNOYED -> VoiceDirection(1.04f, 1.02f, 1f)
            else -> VoiceDirection(BASE_RATE, BASE_PITCH, 1f)
        }
        val styleAdjustment = when (expression) {
            ExpressionStyle.PLAYFUL, ExpressionStyle.EXCITED -> 0.01f to 0.01f
            ExpressionStyle.AFFECTIONATE, ExpressionStyle.COMFORTING -> -0.01f to -0.01f
            ExpressionStyle.SHY -> -0.02f to 0.01f
            ExpressionStyle.SERIOUS, ExpressionStyle.FOCUSED -> -0.02f to -0.02f
            ExpressionStyle.TEASING, ExpressionStyle.SARCASTIC_LIGHT -> -0.01f to -0.01f
            else -> 0f to 0f
        }
        return VoiceDirection(
            rate = (base.rate + styleAdjustment.first).coerceIn(0.91f, 1.10f),
            pitch = (base.pitch + styleAdjustment.second).coerceIn(1.02f, 1.12f),
            volume = base.volume.coerceIn(0.86f, 1f)
        )
    }
}
