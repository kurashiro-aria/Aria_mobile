package com.kura.aria.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Dictation stays on the phone and returns editable text to the chat input. */
class LocalSpeechInput(
    context: Context,
    private val onText: (String) -> Unit,
    private val onFinished: (String?) -> Unit,
) {
    private val recognizer: SpeechRecognizer
    private var closed = false

    init {
        require(SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            "Este teléfono no tiene reconocimiento de voz local disponible"
        }
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                if (!closed) partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let(onText)
            }

            override fun onResults(results: Bundle?) {
                if (closed) return
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let(onText)
                onFinished(null)
            }

            override fun onError(error: Int) {
                if (!closed) onFinished("No pude reconocer la voz en el dispositivo (código $error)")
            }
        })
    }

    fun start() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer.startListening(intent)
    }

    fun close() {
        if (closed) return
        closed = true
        recognizer.cancel()
        recognizer.destroy()
    }
}
