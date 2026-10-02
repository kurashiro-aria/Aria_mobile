package com.kura.aria.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceInputControllerTest {
    private class FakeWake : AriaWakeWordEngine {
        var listener: ((WakeDetection) -> Unit)? = null
        override var isRunning = false
        override fun start(listener: (WakeDetection) -> Unit) { this.listener = listener; isRunning = true }
        override fun stop() { isRunning = false }
        override fun close() = stop()
        fun emit(command: String? = null) { listener?.invoke(WakeDetection(command)) }
    }
    private class FakeSpeech : AriaSpeechRecognizer {
        var starts = 0; var stops = 0
        override var isListening = false
        override fun startListening() { starts++; isListening = true }
        override fun stopListening() { stops++; isListening = false }
        override fun close() = stopListening()
    }

    @Test fun wakeWithoutCommandOpensListening() {
        val wake = FakeWake(); val speech = FakeSpeech(); val states = mutableListOf<VoiceInputState>()
        val controller = AriaVoiceInputController(wake, speech, onState = { states += it.state })
        controller.start(); wake.emit()
        assertEquals(VoiceInputState.LISTENING, controller.state)
        assertEquals(1, speech.starts)
        assertTrue(states.contains(VoiceInputState.ACKNOWLEDGING))
    }

    @Test fun wakeWithCommandPreservesCommand() {
        val wake = FakeWake(); val speech = FakeSpeech(); var command = ""
        val controller = AriaVoiceInputController(wake, speech, onCommand = { command = it })
        controller.start(); wake.emit("pon música")
        assertEquals("pon música", command)
        assertEquals(VoiceInputState.PROCESSING, controller.state)
        assertEquals(0, speech.starts)
    }

    @Test fun followUpExpires() {
        var now = 0L; val session = AriaListeningSession(5_000L) { now }
        session.wake(); assertEquals(VoiceInputState.LISTENING, session.state)
        now = 5_001L
        assertTrue(session.tick())
        assertEquals(VoiceInputState.SLEEPING, session.state)
    }

    @Test fun speakingSuppressesWake() {
        val session = AriaListeningSession()
        session.responseStarted(); val detection = session.wake("para")
        assertNull(detection.command)
        assertEquals(VoiceInputState.SPEAKING, session.state)
    }

    @Test fun followUpAcceptsTranscript() {
        var now = 0L; val session = AriaListeningSession(5_000L) { now }
        session.wake(); session.responseStarted(); session.responseFinished(true)
        assertEquals(VoiceInputState.FOLLOW_UP_WINDOW, session.state)
        assertTrue(session.transcript("¿y durante la noche?"))
        assertEquals(VoiceInputState.PROCESSING, session.state)
    }
}
