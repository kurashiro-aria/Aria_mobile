package com.kura.aria.voice

class AriaListeningSession(
    private val followUpTimeoutMs: Long = 10_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    init { require(followUpTimeoutMs > 0) }
    var state: VoiceInputState = VoiceInputState.SLEEPING
        private set
    var lastWakeAtMs: Long? = null
        private set
    var listeningDeadlineMs: Long? = null
        private set

    fun wake(command: String? = null): WakeDetection {
        if (state == VoiceInputState.SPEAKING) return WakeDetection(null)
        lastWakeAtMs = nowMs()
        state = VoiceInputState.WAKE_DETECTED
        state = VoiceInputState.ACKNOWLEDGING
        if (!command.isNullOrBlank()) state = VoiceInputState.PROCESSING
        else {
            state = VoiceInputState.LISTENING
            listeningDeadlineMs = nowMs() + followUpTimeoutMs
        }
        return WakeDetection(command?.trim()?.takeIf { it.isNotEmpty() })
    }

    fun transcript(text: String): Boolean {
        if (state != VoiceInputState.LISTENING && state != VoiceInputState.FOLLOW_UP_WINDOW) return false
        if (text.isBlank()) return false
        state = VoiceInputState.PROCESSING
        listeningDeadlineMs = null
        return true
    }

    fun responseStarted() { state = VoiceInputState.SPEAKING; listeningDeadlineMs = null }

    fun responseFinished(continuous: Boolean) {
        if (continuous) {
            state = VoiceInputState.FOLLOW_UP_WINDOW
            listeningDeadlineMs = nowMs() + followUpTimeoutMs
        } else sleep()
    }

    fun acknowledgeFinished() {
        if (state == VoiceInputState.ACKNOWLEDGING) {
            state = VoiceInputState.LISTENING
            listeningDeadlineMs = nowMs() + followUpTimeoutMs
        }
    }

    fun tick(): Boolean {
        val deadline = listeningDeadlineMs ?: return false
        if (nowMs() < deadline) return false
        sleep(); return true
    }

    fun sleep() { state = VoiceInputState.SLEEPING; listeningDeadlineMs = null }
}
