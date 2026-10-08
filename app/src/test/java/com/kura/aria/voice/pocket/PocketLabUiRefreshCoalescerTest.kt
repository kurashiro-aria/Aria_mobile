package com.kura.aria.voice.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketLabUiRefreshCoalescerTest {
    @Test fun burstLeavesAtMostOnePendingRefreshAndRendersLatestState() {
        val queue = ArrayDeque<() -> Unit>()
        var renders = 0
        val coalescer = PocketLabUiRefreshCoalescer(
            postToUi = { action, _ -> queue.addLast(action) },
            render = { renders++ },
            minIntervalMs = 0L,
            nowMs = { 0L }
        )

        repeat(100) { coalescer.request() }
        assertEquals(1, queue.size)

        queue.removeFirst().invoke()
        assertEquals(1, renders)
        assertEquals(0, queue.size)
    }

    @Test fun eventsDuringRenderScheduleOneFollowUpRefresh() {
        val queue = ArrayDeque<() -> Unit>()
        var renders = 0
        lateinit var coalescer: PocketLabUiRefreshCoalescer
        coalescer = PocketLabUiRefreshCoalescer(
            postToUi = { action, _ -> queue.addLast(action) },
            render = {
                renders++
                if (renders == 1) repeat(10) { coalescer.request() }
            },
            minIntervalMs = 0L,
            nowMs = { 0L }
        )

        coalescer.request()
        assertEquals(1, queue.size)
        queue.removeFirst().invoke()
        assertEquals(1, queue.size)
        queue.removeFirst().invoke()
        assertEquals(2, renders)
        assertEquals(0, queue.size)
    }

    @Test fun finalEventIsEventuallyRenderedAndCloseDropsPendingWork() {
        val queue = ArrayDeque<() -> Unit>()
        var renders = 0
        val coalescer = PocketLabUiRefreshCoalescer(
            postToUi = { action, _ -> queue.addLast(action) },
            render = { renders++ },
            minIntervalMs = 25L,
            nowMs = { 100L }
        )

        coalescer.request()
        coalescer.request(finalEvent = true)
        assertEquals(1, queue.size)
        queue.removeFirst().invoke()
        assertEquals(1, renders)

        coalescer.request()
        assertEquals(1, queue.size)
        coalescer.close()
        queue.removeFirst().invoke()
        assertEquals(1, renders)
    }

    @Test fun refreshesAreBoundedByMinimumIntervalWithoutLosingFinalSnapshot() {
        val queue = ArrayDeque<Pair<() -> Unit, Long>>()
        var now = 0L
        var renders = 0
        val coalescer = PocketLabUiRefreshCoalescer(
            postToUi = { action, delay -> queue.addLast(action to delay) },
            render = { renders++ },
            minIntervalMs = 50L,
            nowMs = { now }
        )

        coalescer.request()
        assertEquals(1, queue.size)
        queue.removeFirst().first.invoke()
        assertEquals(1, renders)

        repeat(20) { coalescer.request() }
        assertEquals(1, queue.size)
        assertEquals(50L, queue.first().second)
        now = 50L
        queue.removeFirst().first.invoke()
        assertEquals(2, renders)
    }
}
