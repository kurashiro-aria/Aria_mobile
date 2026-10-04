package com.kura.aria.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.kura.aria.emotion.AriaEmotion
import com.kura.aria.personality.ExpressionStyle

data class LocalSpeechDiagnostics(
    val emotion: String,
    val expressionStyle: String,
    val voiceId: String,
    val pitch: Float,
    val rate: Float,
    val volume: Float
)

/** Android offline voice adapter. No speech or transcript is sent to a server. */
class LocalSpeechOutput(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onApplied: ((LocalSpeechDiagnostics) -> Unit)? = null
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private data class PendingSpeech(
        val text: String,
        val direction: VoiceDirection,
        val emotion: AriaEmotion?,
        val expression: ExpressionStyle?
    )
    private var pending: PendingSpeech? = null
    private var requestId = 0L

    init {
        tts = TextToSpeech(context.applicationContext) { result ->
            if (!closed) {
                ready = result == TextToSpeech.SUCCESS && selectAriaVoice()
                onStatus(if (ready) "Voz local de ARIA lista" else "Voz local de ARIA no disponible")
                if (ready) pending?.let {
                    pending = null
                    speak(it.text, it.direction, it.emotion, it.expression)
                }
                else pending = null
            }
        }
    }

    private fun selectAriaVoice(): Boolean {
        val synthesizer = tts ?: return false
        val chosen: Voice = synthesizer.voices.orEmpty().firstOrNull {
            it.name.equals(AndroidVoiceDirector.ARIA_VOICE_ID, ignoreCase = true) &&
                it.locale.language == "es" && !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        } ?: return false
        return synthesizer.setVoice(chosen) == TextToSpeech.SUCCESS
    }

    fun speak(text: String, direction: VoiceDirection,
              emotion: AriaEmotion? = null,
              expression: ExpressionStyle? = null) {
        val spokenText = spokenTextForCloud(text)
        if (closed || spokenText.isBlank()) return
        if (!ready) {
            pending = PendingSpeech(spokenText, direction, emotion, expression)
            return
        }
        val synthesizer = tts ?: return
        synthesizer.stop()
        synthesizer.setSpeechRate(direction.rate)
        synthesizer.setPitch(direction.pitch)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, direction.volume.coerceIn(0f, 1f))
        }
        onApplied?.invoke(LocalSpeechDiagnostics(
            emotion = emotion?.name ?: "UNKNOWN",
            expressionStyle = expression?.name ?: "UNKNOWN",
            voiceId = AndroidVoiceDirector.ARIA_VOICE_ID,
            pitch = direction.pitch,
            rate = direction.rate,
            volume = direction.volume.coerceIn(0f, 1f)
        ))
        val id = (++requestId).toString()
        synthesizer.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = Unit
            @Deprecated("Required for older Android TTS engines")
            override fun onError(utteranceId: String) {
                if (utteranceId == id) onStatus("No pude reproducir la voz local")
            }
        })
        if (synthesizer.speak(spokenText, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.ERROR)
            onStatus("No pude reproducir la voz local")
    }

    fun stop() { pending = null; requestId++; tts?.stop() }

    fun close() { closed = true; pending = null; tts?.stop(); tts?.shutdown(); tts = null }
}
