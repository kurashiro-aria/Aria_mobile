package com.kura.aria.voice.pocket

/**
 * Coalesces bursts of diagnostic notifications into at most one queued UI
 * refresh. The producer may run on any thread; [postToUi] is responsible for
 * dispatching the runnable to the main thread.
 */
internal class PocketLabUiRefreshCoalescer(
    private val postToUi: ((() -> Unit, Long) -> Unit),
    private val render: () -> Unit,
    private val minIntervalMs: Long = 50L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private val lock = Any()
    private var queued = false
    private var generation = 0L
    private var closed = false
    private var lastRenderMs = Long.MIN_VALUE

    fun request(finalEvent: Boolean = false) {
        val shouldPost = synchronized(lock) {
            if (closed) return@synchronized false
            generation++
            if (finalEvent) generation++
            if (queued) false else {
                queued = true
                true
            }
        }
        if (shouldPost) {
            postToUi({ consume() }, delayUntilAllowed())
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        queued = false
    }

    private fun consume() {
        val generationAtStart = synchronized(lock) {
            if (closed) return
            generation
        }
        val now = nowMs()
        val remaining = synchronized(lock) {
            if (lastRenderMs == Long.MIN_VALUE) 0L
            else (minIntervalMs - (now - lastRenderMs)).coerceAtLeast(0L)
        }
        if (remaining > 0L) {
            postToUi({ consume() }, remaining)
            return
        }
        synchronized(lock) { lastRenderMs = nowMs() }
        render()
        val shouldPostAgain = synchronized(lock) {
            if (closed) return@synchronized false
            queued = false
            if (generation != generationAtStart) {
                queued = true
                true
            } else false
        }
        if (shouldPostAgain) {
            postToUi({ consume() }, delayUntilAllowed())
        }
    }

    private fun delayUntilAllowed(): Long = synchronized(lock) {
        if (lastRenderMs == Long.MIN_VALUE) 0L
        else (minIntervalMs - (nowMs() - lastRenderMs)).coerceAtLeast(0L)
    }
}
