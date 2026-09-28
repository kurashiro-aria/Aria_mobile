package com.kura.aria.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/** Android offline voice adapter. No speech or transcript is sent to a server. */
class LocalSpeechOutput(context: Context, private val onStatus: (String) -> Unit) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var pending: Pair<String, VoiceDirection>? = null
    private var requestId = 0L

    init {
        tts = TextToSpeech(context.applicationContext) { result ->
            if (!closed) {
                ready = result == TextToSpeech.SUCCESS && selectOfflineSpanishVoice()
                onStatus(if (ready) "Voz local lista" else "No hay voz española sin conexión instalada")
                if (ready) pending?.let { (text, direction) -> pending = null; speak(text, direction) }
                else pending = null
            }
        }
    }

    private fun selectOfflineSpanishVoice(): Boolean {
        val synthesizer = tts ?: return false
        val chosen: Voice = synthesizer.voices.orEmpty()
            .filter { it.locale.language == "es" && !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            .sortedWith(compareByDescending<Voice> { it.locale.country.equals("MX", true) }
                .thenByDescending { it.quality })
            .firstOrNull() ?: return false
        return synthesizer.setVoice(chosen) == TextToSpeech.SUCCESS
    }

    fun speak(text: String, direction: VoiceDirection) {
        if (closed || text.isBlank()) return
        if (!ready) { pending = text to direction; return }
        val synthesizer = tts ?: return
        synthesizer.stop()
        synthesizer.setSpeechRate(direction.rate)
        synthesizer.setPitch(direction.pitch)
        val id = (++requestId).toString()
        synthesizer.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = Unit
            @Deprecated("Required for older Android TTS engines")
            override fun onError(utteranceId: String) {
                if (utteranceId == id) onStatus("No pude reproducir la voz local")
            }
        })
        if (synthesizer.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) == TextToSpeech.ERROR)
            onStatus("No pude reproducir la voz local")
    }

    fun stop() { pending = null; requestId++; tts?.stop() }

    fun close() { closed = true; pending = null; tts?.stop(); tts?.shutdown(); tts = null }
}
