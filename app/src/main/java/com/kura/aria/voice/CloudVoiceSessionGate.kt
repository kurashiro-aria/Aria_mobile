package com.kura.aria.voice

/**
 * Monotonic generation guard for the single active Cloud Voice session.
 * Cancellation normally stops the coroutine, while this guard also rejects
 * late callbacks/results that race with a newer turn.
 */
internal class CloudVoiceSessionGate {
    @Volatile private var activeGeneration = 0L

    @Synchronized fun begin(): Long {
        activeGeneration += 1
        return activeGeneration
    }

    @Synchronized fun cancel() {
        activeGeneration += 1
    }

    fun isCurrent(generation: Long): Boolean = activeGeneration == generation
}
