package com.kura.aria.voice

enum class VoiceInputState { SLEEPING, WAKE_DETECTED, ACKNOWLEDGING, LISTENING, PROCESSING, SPEAKING, FOLLOW_UP_WINDOW }

data class WakeDetection(val command: String? = null)

interface AriaWakeWordEngine : AutoCloseable {
    fun start(listener: (WakeDetection) -> Unit)
    fun stop()
    val isRunning: Boolean
}

interface AriaSpeechRecognizer : AutoCloseable {
    fun startListening()
    fun stopListening()
    val isListening: Boolean
}

data class VoiceInputDiagnostics(
    val state: VoiceInputState,
    val lastWakeAtMs: Long? = null,
    val lastTranscript: String? = null,
    val lastError: String? = null,
    val wakeToListeningMs: Long? = null,
    val listeningToTranscriptMs: Long? = null
)
