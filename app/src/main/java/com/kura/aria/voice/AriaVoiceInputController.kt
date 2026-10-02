package com.kura.aria.voice

class AriaVoiceInputController(
    private val wakeWord: AriaWakeWordEngine,
    private val speech: AriaSpeechRecognizer,
    private val continuousConversation: () -> Boolean = { true },
    followUpTimeoutMs: Long = 10_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val onAcknowledgement: (String) -> Unit = {},
    private val onCommand: (String) -> Unit = {},
    private val onState: (VoiceInputDiagnostics) -> Unit = {}
) : AutoCloseable {
    private val session = AriaListeningSession(followUpTimeoutMs, nowMs)
    private var wakeStartedAt: Long? = null
    private var closed = false
    private var speaking = false
    private var lastError: String? = null

    val state: VoiceInputState get() = session.state

    fun start() {
        if (closed) return
        wakeWord.start { detection ->
            if (speaking || session.state == VoiceInputState.SPEAKING) return@start
            wakeStartedAt = nowMs()
            val accepted = session.wake(detection.command)
            if (accepted.command != null) {
                emit()
                onAcknowledgement(acknowledgement())
                onCommand(accepted.command)
            } else {
                // AriaListeningSession resolves a command-less wake to LISTENING
                // immediately. Surface the acknowledgement phase explicitly so
                // diagnostics/UI observers still receive the complete transition.
                emit(VoiceInputState.ACKNOWLEDGING)
                onAcknowledgement(acknowledgement())
                speech.startListening()
                emit()
            }
        }
    }

    fun submitTranscript(text: String) {
        if (session.transcript(text)) {
            speech.stopListening()
            emit()
            onCommand(text.trim())
        }
    }

    fun beginSpeaking() {
        speaking = true
        speech.stopListening()
        session.responseStarted()
        emit()
    }

    fun endSpeaking() {
        speaking = false
        session.responseFinished(continuousConversation())
        if (session.state == VoiceInputState.FOLLOW_UP_WINDOW) speech.startListening()
        emit()
    }

    fun reportError(message: String) {
        lastError = message
        speech.stopListening()
        session.sleep()
        emit()
    }

    fun tick() {
        if (session.tick()) {
            speech.stopListening()
            emit()
        }
    }

    override fun close() {
        closed = true
        speaking = false
        session.sleep()
        speech.stopListening()
        wakeWord.close()
        speech.close()
    }

    private fun acknowledgement() = listOf("¿Sí, Kura?", "Dime Kura, te escucho.", "Te escucho.", "¿Qué necesitas?").first()

    private fun emit(stateOverride: VoiceInputState? = null) {
        val emittedState = stateOverride ?: session.state
        onState(VoiceInputDiagnostics(
            state = emittedState,
            lastWakeAtMs = session.lastWakeAtMs,
            lastError = lastError,
            wakeToListeningMs = if (emittedState == VoiceInputState.LISTENING && wakeStartedAt != null)
                nowMs() - wakeStartedAt!! else null
        ))
    }
}
