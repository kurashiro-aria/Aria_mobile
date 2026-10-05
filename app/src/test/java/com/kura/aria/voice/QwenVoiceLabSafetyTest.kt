package com.kura.aria.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenVoiceLabSafetyTest {
    @Test fun failedLoadIsContainedAsError() {
        val machine = QwenVoiceLabStateMachine(QwenLabState.INSTALLED)
        machine.beginLoad()
        machine.finishLoad(false)
        assertEquals(QwenLabState.ERROR, machine.state)
        assertFalse(machine.runtimeLoaded)
    }

    @Test fun failedSynthesisDoesNotEnableAutomaticVoiceOrReplacePiper() {
        val machine = QwenVoiceLabStateMachine(QwenLabState.INSTALLED)
        machine.beginLoad()
        machine.finishLoad(true)
        machine.synthesisFailed()
        assertEquals(QwenLabState.ERROR, machine.state)
        assertFalse(QwenVoiceLabIsolationPolicy.AUTOMATIC_VOICE_ENABLED)
        assertEquals("Piper", QwenVoiceLabIsolationPolicy.FALLBACK_ENGINE)
        assertEquals(":qwen_voice", QwenVoiceLabIsolationPolicy.PROCESS_NAME)
    }

    @Test fun releaseDropsRuntimeAndKeepsValidatedInstallation() {
        val machine = QwenVoiceLabStateMachine(QwenLabState.INSTALLED)
        machine.beginLoad()
        machine.finishLoad(true)
        assertTrue(machine.runtimeLoaded)

        machine.release(modelInstalled = true)

        assertFalse(machine.runtimeLoaded)
        assertEquals(QwenLabState.INSTALLED, machine.state)
    }

    @Test fun firstAudioThresholdsMatchSolCriteria() {
        assertEquals("EXCELENTE", QwenVoiceLabMetrics(firstAudioMs = 3_000).firstAudioClassification)
        assertEquals("USABLE", QwenVoiceLabMetrics(firstAudioMs = 4_000).firstAudioClassification)
        assertEquals("REQUIERE OPTIMIZACIÓN", QwenVoiceLabMetrics(firstAudioMs = 8_000).firstAudioClassification)
        assertEquals("NO APTO COMO VOZ PRINCIPAL", QwenVoiceLabMetrics(firstAudioMs = 10_001).firstAudioClassification)
    }

    @Test fun readyTestButtonImmediatelyDispatchesSynthesisRequest() {
        val machine = QwenVoiceLabStateMachine(QwenLabState.INSTALLED)
        machine.beginLoad()
        machine.finishLoad(true)

        assertTrue(machine.requestSynthesis())
        assertEquals(QwenLabState.SYNTHESIZING, machine.state)
        assertFalse(machine.requestSynthesis())

        machine.finishSynthesis()
        assertEquals(QwenLabState.READY, machine.state)
    }
}
