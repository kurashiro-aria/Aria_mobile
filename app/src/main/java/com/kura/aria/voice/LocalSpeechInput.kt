package com.kura.aria.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Prefers on-device dictation; uses the phone's configured service when unavailable. */
class LocalSpeechInput(
    context: Context,
    private val onText: (String) -> Unit,
    private val onFinished: (String?) -> Unit,
) {
    private var recognizer: SpeechRecognizer
    private var closed = false
    private var usingOnDevice: Boolean
    val onDevice: Boolean get() = usingOnDevice

    init {
        usingOnDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        require(usingOnDevice || SpeechRecognizer.isRecognitionAvailable(context)) {
            "No hay servicio de reconocimiento de voz instalado en el teléfono"
        }
        recognizer = if (usingOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
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
                if (closed) return
                if (usingOnDevice && SpeechRecognizer.isRecognitionAvailable(context) &&
                    (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
                    recognizer.destroy()
                    usingOnDevice = false
                    recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    recognizer.setRecognitionListener(this)
                    start()
                    return
                }
                onFinished(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "No escuché palabras claras. Inténtalo de nuevo"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "Activa el permiso de micrófono de ARIA en Android"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "Instala el paquete de reconocimiento de español en el teléfono"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "El reconocimiento del teléfono necesita conexión o idioma sin conexión"
                    else -> "No pude reconocer tu voz (código $error)"
                })
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
