package com.kura.aria.voice

import com.kura.aria.emotion.AriaEmotion
import com.kura.aria.emotion.ConversationMood
import com.kura.aria.personality.ExpressionStyle

/** Acting choices for the local voice lab; Piper itself has no emotion or pitch API. */
internal enum class VoiceLabPreset(
    val label: String,
    val emotion: AriaEmotion,
    val mood: ConversationMood,
    val expressionStyle: ExpressionStyle
) {
    NEUTRAL("Neutral", AriaEmotion.NEUTRAL, ConversationMood.NEUTRAL, ExpressionStyle.NATURAL),
    HAPPY("Feliz", AriaEmotion.HAPPY, ConversationMood.JOYFUL, ExpressionStyle.PLAYFUL),
    SAD("Triste", AriaEmotion.SAD, ConversationMood.VULNERABLE, ExpressionStyle.COMFORTING),
    ANGRY("Enojada", AriaEmotion.ANGRY, ConversationMood.FRUSTRATED, ExpressionStyle.SERIOUS),
    FLIRTY("Coqueta", AriaEmotion.AFFECTIONATE, ConversationMood.PLAYFUL, ExpressionStyle.FLIRTY),
    SHY("Tímida", AriaEmotion.EMBARRASSED, ConversationMood.SHY, ExpressionStyle.SHY),
    EXCITED("Emocionada", AriaEmotion.EXCITED, ConversationMood.JOYFUL, ExpressionStyle.EXCITED),
    SARCASTIC("Sarcástica", AriaEmotion.AMUSED, ConversationMood.PLAYFUL, ExpressionStyle.SARCASTIC_LIGHT)
}

internal data class VoicePerformanceProfile(
    val speed: Float,
    val pauseBetweenSentencesMs: Int
) {
    init {
        require(speed in 0.85f..1.15f)
        require(pauseBetweenSentencesMs in 100..700)
    }

    companion object {
        /** Resolves emotion, mood, and expression to only the actuated controls. */
        fun from(emotion: AriaEmotion, mood: ConversationMood, style: ExpressionStyle): VoicePerformanceProfile {
            val speed = when {
                style == ExpressionStyle.EXCITED || emotion == AriaEmotion.EXCITED -> 1.12f
                style == ExpressionStyle.SERIOUS || emotion == AriaEmotion.ANGRY -> 1.08f
                style == ExpressionStyle.FLIRTY || style == ExpressionStyle.AFFECTIONATE ||
                    emotion == AriaEmotion.AFFECTIONATE -> 0.98f
                style == ExpressionStyle.SHY || emotion == AriaEmotion.EMBARRASSED -> 0.94f
                emotion == AriaEmotion.SAD || mood == ConversationMood.VULNERABLE ||
                    mood == ConversationMood.TIRED -> 0.90f
                emotion == AriaEmotion.HAPPY || mood == ConversationMood.JOYFUL -> 1.06f
                mood == ConversationMood.PLAYFUL || style == ExpressionStyle.SARCASTIC_LIGHT -> 1.02f
                else -> 1.0f
            }
            val pause = when {
                mood == ConversationMood.VULNERABLE || emotion == AriaEmotion.SAD -> 430
                style == ExpressionStyle.SHY || emotion == AriaEmotion.EMBARRASSED -> 360
                style == ExpressionStyle.EXCITED || emotion == AriaEmotion.EXCITED -> 160
                emotion == AriaEmotion.ANGRY -> 190
                emotion == AriaEmotion.HAPPY || mood == ConversationMood.JOYFUL -> 220
                style == ExpressionStyle.SARCASTIC_LIGHT -> 320
                else -> 280
            }
            return VoicePerformanceProfile(speed, pause)
        }
    }
}
