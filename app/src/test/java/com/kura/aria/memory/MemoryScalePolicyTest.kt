package com.kura.aria.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryScalePolicyTest {
    @Test
    fun largeSyntheticStoreOnlyConsumesBoundedCandidateWindow() {
        var examined = 0
        val syntheticMillion = generateSequence(1) { previous ->
            if (previous < 1_000_000) previous + 1 else null
        }.onEach { examined++ }

        val selected = MemoryRetrievalPolicy.candidateWindow(syntheticMillion)

        assertEquals(60, selected.size)
        assertEquals(60, examined)
        assertEquals(60, selected.last())
    }

    @Test
    fun consolidationIsPreparedWithoutDeletingOriginals() {
        assertFalse(MemoryRetrievalPolicy.shouldConsolidate(49))
        assertTrue(MemoryRetrievalPolicy.shouldConsolidate(50))
    }

    @Test
    fun callerCannotRequestAnUnboundedCandidateSet() {
        assertEquals(1, MemoryRetrievalPolicy.clampCandidateLimit(0))
        assertEquals(60, MemoryRetrievalPolicy.clampCandidateLimit(100_000))
    }
}
