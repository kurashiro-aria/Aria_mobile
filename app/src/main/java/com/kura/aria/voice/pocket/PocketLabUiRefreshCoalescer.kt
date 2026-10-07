package com.kura.aria.voice.pocket

/**
 * Coalesces bursts of diagnostic notifications into at most one queued UI
 * refresh. The producer may run on any thread; [postToUi] is responsible for
 * dispatching the runnable to the main thread.
 */
internal class PocketLabUiRefreshCoalescer(
    private val postToUi: ((() -> Unit) -> Unit),
    private val render: () -> Unit
) {
    private val lock = Any()
    private var queued = false
    private var generation = 0L

    fun request() {
        val shouldPost = synchronized(lock) {
            generation++
            if (queued) false else {
                queued = true
                true
            }
        }
        if (shouldPost) {
            postToUi { consume() }
        }
    }

    private fun consume() {
        val generationAtStart = synchronized(lock) { generation }
        render()
        val shouldPostAgain = synchronized(lock) {
            queued = false
            if (generation != generationAtStart) {
                queued = true
                true
            } else false
        }
        if (shouldPostAgain) {
            postToUi { consume() }
        }
    }
}
