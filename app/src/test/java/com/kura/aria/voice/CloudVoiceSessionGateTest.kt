package com.kura.aria.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudVoiceSessionGateTest {
    @Test fun newerSessionInvalidatesOlderSession() {
        val gate = CloudVoiceSessionGate()
        val first = gate.begin()
        val second = gate.begin()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test fun cancelInvalidatesActiveSession() {
        val gate = CloudVoiceSessionGate()
        val generation = gate.begin()
        gate.cancel()
        assertFalse(gate.isCurrent(generation))
    }

    @Test fun repeatedTurnsLeaveOnlyTheLastGenerationCurrent() {
        val gate = CloudVoiceSessionGate()
        val generations = (1..10).map { gate.begin() }
        generations.dropLast(1).forEach { assertFalse(gate.isCurrent(it)) }
        assertTrue(gate.isCurrent(generations.last()))
    }
}
