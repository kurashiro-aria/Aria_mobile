package com.kura.aria.voice

import com.kura.aria.emotion.AriaEmotion
import com.kura.aria.personality.ExpressionStyle

/** Presentation only: never changes the words or decides ARIA's emotion. */
data class VoiceDirection(val rate: Float, val pitch: Float)

internal data class CloudVoiceDirection(
    val emotion: String,
    val intensity: Float,
    val expressionStyle: String,
    val styleDescription: String
)

internal data class CloudVoiceCandidate(val id: String, val description: String)

object AriaVoiceDirector {
    internal val cloudCandidates = listOf(
        CloudVoiceCandidate("Leda", "juvenil"),
        CloudVoiceCandidate("Achernar", "suave"),
        CloudVoiceCandidate("Vindemiatrix", "gentil"),
        CloudVoiceCandidate("Sulafat", "cálida"),
        CloudVoiceCandidate("Aoede", "ligera")
    )

    fun forEmotion(emotion: AriaEmotion): VoiceDirection = when (emotion) {
        AriaEmotion.HAPPY, AriaEmotion.AMUSED -> VoiceDirection(1.04f, 1.02f)
        AriaEmotion.EXCITED, AriaEmotion.SURPRISED -> VoiceDirection(1.07f, 1.03f)
        AriaEmotion.SAD, AriaEmotion.TIRED -> VoiceDirection(0.91f, 0.98f)
        AriaEmotion.AFFECTIONATE, AriaEmotion.EMBARRASSED -> VoiceDirection(0.96f, 1.01f)
        AriaEmotion.PLAYFUL -> VoiceDirection(1.05f, 1.02f)
        AriaEmotion.ANGRY, AriaEmotion.ANNOYED, AriaEmotion.SERIOUS -> VoiceDirection(0.96f, 1.0f)
        else -> VoiceDirection(1.0f, 1.0f)
    }

    internal fun forCloud(emotion: AriaEmotion, intensity: Float,
                          expression: ExpressionStyle): CloudVoiceDirection {
        val safeIntensity = intensity.coerceIn(0f, 1f)
        val delivery = when (emotion) {
            AriaEmotion.HAPPY -> "luminosa y alegre"
            AriaEmotion.AMUSED -> "divertida con calidez"
            AriaEmotion.PLAYFUL -> "juguetona con picardía ligera"
            AriaEmotion.AFFECTIONATE -> "cálida, cercana y suave"
            AriaEmotion.SAD -> "más baja y lenta, sin dramatización exagerada"
            AriaEmotion.SURPRISED -> "viva, con una aceleración ligera"
            AriaEmotion.EMBARRASSED -> "suave y algo tímida"
            AriaEmotion.EXCITED -> "enérgica y entusiasta"
            AriaEmotion.SERIOUS -> "serena, clara y seria"
            AriaEmotion.TIRED -> "suave y pausada"
            else -> "natural, suave y conversacional"
        }
        return CloudVoiceDirection(emotion.name.lowercase(), safeIntensity,
            expression.name.lowercase(), delivery)
    }

    /** Default Cloud delivery for automatic replies, keeping Leda's identity while
     * allowing the emotional state to change the interpretation. */
    internal fun forCloudEmotion(emotion: AriaEmotion): CloudVoiceDirection {
        val expression = when (emotion) {
            AriaEmotion.PLAYFUL, AriaEmotion.AMUSED -> ExpressionStyle.PLAYFUL
            AriaEmotion.AFFECTIONATE, AriaEmotion.EMBARRASSED -> ExpressionStyle.AFFECTIONATE
            AriaEmotion.SAD, AriaEmotion.TIRED -> ExpressionStyle.COMFORTING
            AriaEmotion.SERIOUS, AriaEmotion.ANGRY, AriaEmotion.ANNOYED -> ExpressionStyle.SERIOUS
            AriaEmotion.EXCITED, AriaEmotion.SURPRISED, AriaEmotion.HAPPY -> ExpressionStyle.EXCITED
            else -> ExpressionStyle.NATURAL
        }
        val intensity = when (emotion) {
            AriaEmotion.SAD, AriaEmotion.TIRED, AriaEmotion.EMBARRASSED -> 0.35f
            AriaEmotion.EXCITED, AriaEmotion.SURPRISED -> 0.72f
            else -> 0.58f
        }
        return forCloud(emotion, intensity, expression)
    }

    internal fun forPreview(style: String): CloudVoiceDirection = when (style) {
        "happy" -> forCloud(AriaEmotion.HAPPY, 0.65f, ExpressionStyle.EXCITED)
        "playful" -> forCloud(AriaEmotion.PLAYFUL, 0.65f, ExpressionStyle.PLAYFUL)
        "affectionate" -> forCloud(AriaEmotion.AFFECTIONATE, 0.45f, ExpressionStyle.AFFECTIONATE)
        "surprised" -> forCloud(AriaEmotion.SURPRISED, 0.65f, ExpressionStyle.EXCITED)
        "soft" -> forCloud(AriaEmotion.AFFECTIONATE, 0.25f, ExpressionStyle.COMFORTING)
            .copy(expressionStyle = "soft", styleDescription = "especialmente suave")
        "whisper" -> forCloud(AriaEmotion.AFFECTIONATE, 0.25f, ExpressionStyle.COMFORTING)
            .copy(expressionStyle = "whisper", styleDescription = "susurro suave")
        else -> forCloud(AriaEmotion.NEUTRAL, 0.2f, ExpressionStyle.NATURAL)
    }
}
