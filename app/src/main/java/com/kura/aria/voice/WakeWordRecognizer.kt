package com.kura.aria.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.text.Normalizer

/** Restartable speech sessions for the opt-in "Aria" trigger. Must run on the main thread. */
class WakeWordRecognizer(
    context: Context,
    private val onCommand: (String) -> Unit,
    private val onState: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    else SpeechRecognizer.createSpeechRecognizer(context)
    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }
    private var active = false
    private var awaitingCommand = false
    private var failures = 0
    private val restart = Runnable { listen() }
    private val timeout = Runnable {
        awaitingCommand = false
        onState("Di Aria para hablar")
    }

    init {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit

            override fun onResults(results: Bundle?) {
                failures = 0
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty().trim()
                val normalized = Normalizer.normalize(heard, Normalizer.Form.NFD)
                    .replace(Regex("\\p{Mn}+"), "").lowercase()
                val wake = Regex("^aria(?:\\b|[,.:;!?])").find(normalized)
                when {
                    awaitingCommand && heard.isNotBlank() -> {
                        awaitingCommand = false
                        handler.removeCallbacks(timeout)
                        onState("ARIA recibió tu mensaje")
                        onCommand(if (wake != null) heard.substring(wake.range.last + 1)
                            .trimStart(' ', ',', '.', ':', ';', '!', '?').ifBlank { heard } else heard)
                    }
                    wake != null -> {
                        val rest = heard.substring(wake.range.last + 1).trimStart(' ', ',', '.', ':', ';', '!', '?')
                        if (rest.isNotBlank()) onCommand(rest)
                        else {
                            awaitingCommand = true
                            onState("Dime, Kura, te escucho")
                            handler.removeCallbacks(timeout)
                            handler.postDelayed(timeout, 12_000)
                        }
                    }
                }
                schedule(if (awaitingCommand) 2_000 else 350)
            }

            override fun onError(error: Int) {
                if (!active) return
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    onFailure("El micrófono no tiene permiso")
                    return
                }
                failures++
                if (failures >= 8 && error != SpeechRecognizer.ERROR_NO_MATCH &&
                    error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    onFailure("El reconocimiento de voz no responde (código $error)")
                    return
                }
                schedule(if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) 350 else 1500)
            }
        })
    }

    fun start() {
        if (active) return
        active = true
        onState("Di Aria para hablar")
        listen()
    }

    fun stop() {
        active = false
        awaitingCommand = false
        handler.removeCallbacks(restart)
        handler.removeCallbacks(timeout)
        recognizer.cancel()
    }

    fun close() { stop(); recognizer.destroy() }

    private fun schedule(delay: Long) {
        handler.removeCallbacks(restart)
        if (active) handler.postDelayed(restart, delay)
    }

    private fun listen() {
        if (!active) return
        try { recognizer.startListening(intent) }
        catch (e: Exception) { onFailure(e.message ?: "No pude iniciar el micrófono") }
    }
}
