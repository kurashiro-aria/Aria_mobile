package com.kura.aria.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Isolated catalogue and preview adapter for voices installed on Android.
 * It is intentionally not used by ARIA's automatic Cloud Voice path.
 */
class AndroidTtsVoiceProbe(
    context: Context,
    private val onReady: (List<AndroidTtsVoice>) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var utteranceSequence = 0L

    init {
        tts = TextToSpeech(context.applicationContext) { result ->
            if (closed) return@TextToSpeech
            if (result != TextToSpeech.SUCCESS) {
                onStatus("No se pudo inicializar el motor TTS de Android")
                return@TextToSpeech
            }
            ready = true
            onReady(catalogue())
            onStatus("Motor TTS listo · ${tts?.defaultEngine ?: "Android"}")
        }
    }

    fun catalogue(): List<AndroidTtsVoice> = tts?.voices.orEmpty()
        .map(AndroidTtsVoice::from)
        .sortedWith(compareByDescending<AndroidTtsVoice> { AndroidTtsVoiceCatalog.priority(it) }
            .thenBy { it.localeTag }
            .thenBy { it.id })

    fun speak(voiceId: String, preset: AndroidTtsPreset, text: String) {
        val spoken = spokenTextForCloud(text)
        if (spoken.isBlank()) {
            onStatus("No hay texto hablable en este mensaje")
            return
        }
        val synthesizer = tts
        if (!ready || synthesizer == null) {
            onStatus("El motor TTS todavía no está listo")
            return
        }
        val voice = synthesizer.voices.orEmpty().firstOrNull { it.name == voiceId }
        if (voice == null) {
            onStatus("La voz seleccionada ya no está disponible")
            return
        }
        stop()
        if (synthesizer.setVoice(voice) != TextToSpeech.SUCCESS) {
            onStatus("No se pudo seleccionar la voz ${voice.name}")
            return
        }
        synthesizer.setPitch(preset.pitch)
        synthesizer.setSpeechRate(preset.rate)
        val utteranceId = "android-tts-${++utteranceSequence}"
        synthesizer.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = Unit
            @Deprecated("Required for older Android TTS engines")
            override fun onError(utteranceId: String) {
                if (!closed) onStatus("El motor no pudo reproducir esta voz")
            }
            override fun onError(utteranceId: String, errorCode: Int) {
                if (!closed) onStatus("El motor no pudo reproducir esta voz (código $errorCode)")
            }
        })
        if (synthesizer.speak(spoken, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId) == TextToSpeech.ERROR)
            onStatus("El motor rechazó la muestra de voz")
    }

    fun stop() { tts?.stop() }

    fun close() {
        closed = true
        ready = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

data class AndroidTtsVoice(
    val id: String,
    val displayName: String,
    val localeTag: String,
    val requiresNetwork: Boolean,
    val installed: Boolean,
    val quality: Int,
    val latency: Int
) {
    companion object {
        fun from(voice: Voice): AndroidTtsVoice = AndroidTtsVoice(
            id = voice.name,
            displayName = voice.name.substringAfter(':').replace('_', ' '),
            localeTag = voice.locale.toLanguageTag(),
            requiresNetwork = voice.isNetworkConnectionRequired,
            installed = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty(),
            quality = voice.quality,
            latency = voice.latency
        )
    }
}

object AndroidTtsVoiceCatalog {
    /** Ranking is only a presentation aid; Android remains the source of truth. */
    fun priority(voice: AndroidTtsVoice): Int {
        if (!voice.localeTag.startsWith("es", ignoreCase = true)) return 0
        var score = 100
        when {
            voice.localeTag.equals("es-CL", true) -> score += 40
            voice.localeTag.equals("es-MX", true) -> score += 35
            voice.localeTag.startsWith("es-", true) -> score += 20
        }
        if (voice.installed) score += 10
        if (!voice.requiresNetwork) score += 5
        return score
    }
}

data class AndroidTtsPreset(val label: String, val pitch: Float, val rate: Float) {
    companion object {
        val NATURAL = AndroidTtsPreset("Natural", 1.00f, 1.00f)
        val ARIA_YOUNG = AndroidTtsPreset("ARIA young", 1.06f, 1.02f)
        val ARIA_LIGHT = AndroidTtsPreset("ARIA light", 1.10f, 1.03f)
        val all = listOf(NATURAL, ARIA_YOUNG, ARIA_LIGHT)
    }
}
