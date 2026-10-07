package com.kura.aria.voice.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketLabDiagnosticTest {
    @Test fun startsWithNullHardwareFields() {
        val diagnostic = PocketLabDiagnostic(PocketIsolationVariant.A_CONTROL)
        val snapshot = diagnostic.snapshot()

        assertEquals("LISTO", snapshot.state)
        assertEquals(PocketLabStage.IDLE, snapshot.stage)
        assertNull(snapshot.error)
        assertNull(snapshot.minBufferSize)
        assertNull(snapshot.trackState)
        assertNull(snapshot.firstWriteResult)
        assertEquals(0, snapshot.callbacks)
        assertEquals(0L, snapshot.samples)
    }

    @Test fun recordsNativeStreamFloatAndWriteStages() {
        val diagnostic = PocketLabDiagnostic(PocketIsolationVariant.A_CONTROL)
        diagnostic.mark(PocketLabStage.LAB_REQUEST)
        diagnostic.nativeConfig(PocketKvMode.CURRENT_SINGLE_BUFFER, 1, seedFixed = true)
        diagnostic.mark(PocketLabStage.NATIVE_CREATE_OK)
        diagnostic.mark(PocketLabStage.STREAM_START_REQUEST)
        diagnostic.mark(PocketLabStage.STREAM_START_OK)
        diagnostic.mark(PocketLabStage.STREAM_READ_FIRST, "result=1920")
        diagnostic.callback(1920)
        diagnostic.pipelineAccepted(true)
        diagnostic.audioTrackRequested(4096, 24_000, 4, 2)
        diagnostic.audioTrackCreated(1, 1, 2048)
        diagnostic.mark(PocketLabStage.FIRST_AUDIO_TRACK_WRITE)
        diagnostic.firstWrite(3840, 3840)

        val snapshot = diagnostic.snapshot()
        assertEquals(PocketLabStage.FIRST_AUDIO_TRACK_WRITE_OK, snapshot.stage)
        assertEquals(1, snapshot.callbacks)
        assertEquals(1920L, snapshot.samples)
        assertEquals(4096, snapshot.minBufferSize)
        assertEquals(3840, snapshot.firstWriteRequestedBytes)
        assertEquals(3840, snapshot.firstWriteResult)
        assertTrue(diagnostic.summary().contains("KV=CURRENT_SINGLE_BUFFER LSD=1 seed=FIXED"))
    }

    @Test fun preservesExactFailedWriteAndStage() {
        val diagnostic = PocketLabDiagnostic(PocketIsolationVariant.A_CONTROL)
        diagnostic.audioTrackRequested(-2, 24_000, 4, 2)
        diagnostic.audioTrackCreateFailed("minBufferSize=-2")
        assertEquals("ERROR", diagnostic.snapshot().state)
        assertEquals(PocketLabStage.CREATE_AUDIO_TRACK_FAILED, diagnostic.snapshot().stage)
        assertEquals(PocketVoiceError.AUDIO_FAILED, diagnostic.snapshot().error)

        val writeFailure = PocketLabDiagnostic(PocketIsolationVariant.A_CONTROL)
        writeFailure.mark(PocketLabStage.FIRST_AUDIO_TRACK_WRITE)
        writeFailure.firstWrite(3840, -6)
        assertEquals(PocketLabStage.FIRST_AUDIO_TRACK_WRITE_FAILED, writeFailure.snapshot().stage)
        assertEquals(-6, writeFailure.snapshot().firstWriteResult)
    }

    @Test fun configurationLabelsDoNotChangeLabVariants() {
        val a = PocketIsolationVariant.A_CONTROL.runtimeConfig()
        val b = PocketIsolationVariant.B_KV_SEPARATE.runtimeConfig()
        val c = PocketIsolationVariant.C_KV_SEPARATE_LSD3.runtimeConfig()
        assertEquals(PocketKvMode.CURRENT_SINGLE_BUFFER, a.kvMode)
        assertEquals(PocketKvMode.SEPARATE_INPUT_OUTPUT, b.kvMode)
        assertEquals(PocketKvMode.SEPARATE_INPUT_OUTPUT, c.kvMode)
        assertEquals(1, a.lsdSteps)
        assertEquals(1, b.lsdSteps)
        assertEquals(3, c.lsdSteps)
        assertEquals(a.randomSeed, b.randomSeed)
        assertEquals(b.randomSeed, c.randomSeed)
    }
}
