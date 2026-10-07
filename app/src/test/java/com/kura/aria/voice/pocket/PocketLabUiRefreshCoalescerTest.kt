package com.kura.aria.voice.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketLabUiRefreshCoalescerTest {
    @Test fun burstLeavesAtMostOnePendingRefreshAndRendersLatestState() {
        val queue = ArrayDeque<() -> Unit>()
        var renders = 0
        val coalescer = PocketLabUiRefreshCoalescer(
            postToUi = { queue.addLast(it) },
            render = { renders++ }
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
            postToUi = { queue.addLast(it) },
            render = {
                renders++
                if (renders == 1) repeat(10) { coalescer.request() }
            }
        )

        coalescer.request()
        assertEquals(1, queue.size)
        queue.removeFirst().invoke()
        assertEquals(1, queue.size)
        queue.removeFirst().invoke()
        assertEquals(2, renders)
        assertEquals(0, queue.size)
    }
}
