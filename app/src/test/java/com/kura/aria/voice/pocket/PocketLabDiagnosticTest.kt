package com.kura.aria.voice.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

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

    @Test fun preservesTrackLifecycleAndConstructorCause() {
        val diagnostic = PocketLabDiagnostic(PocketIsolationVariant.A_CONTROL)
        diagnostic.previousTrack("track=7 thread=test pause=OK flush=OK stop=OK release=OK")
        diagnostic.audioTrackRequested(96_000, 24_000, 4, 2)
        diagnostic.audioTrackCreateFailed("IllegalArgumentException: track unavailable")

        val snapshot = diagnostic.snapshot()
        assertTrue(snapshot.previousTrackDetail?.contains("pause=OK flush=OK stop=OK release=OK") == true)
        assertEquals(PocketLabStage.CREATE_AUDIO_TRACK_FAILED, snapshot.stage)
        assertEquals("IllegalArgumentException: track unavailable", snapshot.technicalDetail)
        assertTrue(diagnostic.summary().contains("Min buffer: 96000"))
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

    @Test fun recordsAllWritesAndFailedWriteContext() {
        val diagnostic = PocketLabDiagnostic(PocketIsolationVariant.A_CONTROL)
        diagnostic.trackCreated(7L, 1, 1, 48_000)
        diagnostic.writeAttempt(4_800, 4_800, 1, 1, 7L, cancelled = false)
        diagnostic.writeAttempt(9_600, 0, 1, 1, 7L, cancelled = false)

        val snapshot = diagnostic.snapshot()
        assertEquals(2, snapshot.writeCount)
        assertEquals(14_400L, snapshot.totalBytesRequested)
        assertEquals(4_800L, snapshot.totalBytesWritten)
        assertEquals(2, snapshot.failedWriteIndex)
        assertEquals(9_600, snapshot.failedWriteRequestedBytes)
        assertEquals(0, snapshot.failedWriteResult)
        assertEquals(1, snapshot.failedWriteTrackState)
        assertEquals(1, snapshot.failedWritePlayState)
        assertEquals(7L, snapshot.trackId)
        assertEquals(7L, snapshot.lastWriteTrackId)
        assertEquals(false, snapshot.lastWriteCancelled)
    }

    @Test fun preservesTrackIdentityAcrossFailedWrite() {
        val diagnostic = PocketLabDiagnostic(PocketIsolationVariant.A_CONTROL)
        diagnostic.trackCreated(12L, 1, 1, 48_000)
        diagnostic.writeAttempt(48_000, 48_000, 1, 1, 12L, cancelled = false,
            beforePlaybackHeadPosition = 0, beforeUnderrunCount = 0,
            playbackHeadPosition = 24_000, underrunCount = 0, timestampMs = 100)
        diagnostic.writeAttempt(24_000, -32, 1, 1, 12L, cancelled = false,
            beforePlaybackHeadPosition = 27_600, beforeUnderrunCount = 1,
            playbackHeadPosition = 27_600, underrunCount = 1, timestampMs = 200)

        val snapshot = diagnostic.snapshot()
        assertEquals(12L, snapshot.trackId)
        assertEquals(12L, snapshot.lastWriteTrackId)
        assertEquals(2, snapshot.failedWriteIndex)
        assertEquals(-32, snapshot.failedWriteResult)
        assertEquals(27_600L, snapshot.failedWriteBeforePlaybackHeadPosition)
        assertEquals(1, snapshot.failedWriteBeforeUnderrunCount)
        assertEquals(27_600L, snapshot.failedWritePlaybackHeadPosition)
        assertEquals(1, snapshot.failedWriteUnderrunCount)
        assertEquals(2, snapshot.writeHistory.size)
        assertEquals(100L, snapshot.writeHistory.first().timestampMs)
        assertEquals(200L, snapshot.writeHistory.last().timestampMs)
        assertTrue(diagnostic.summary().contains("Track id: 12"))
    }

    @Test fun completionCannotHideAnExistingError() {
        val diagnostic = PocketLabDiagnostic(PocketIsolationVariant.A_CONTROL)
        diagnostic.fail(PocketLabStage.FIRST_AUDIO_TRACK_WRITE_FAILED, PocketVoiceError.AUDIO_FAILED, "write=-6")
        diagnostic.mark(PocketLabStage.SYNTHESIS_COMPLETE)

        val snapshot = diagnostic.snapshot()
        assertEquals(PocketLabStage.ERROR, snapshot.stage)
        assertEquals("ERROR", snapshot.state)
        assertEquals(PocketVoiceError.AUDIO_FAILED, snapshot.error)
    }

    @Test fun recordsPostWritePlaybackStages() {
        val diagnostic = PocketLabDiagnostic(PocketIsolationVariant.A_CONTROL)
        diagnostic.audioPlayRequested()
        diagnostic.audioPlayOk()
        diagnostic.pipelineFinishRequested()
        diagnostic.pipelineFinishOk()
        diagnostic.playbackDrainRequested()
        diagnostic.playbackDrainTimeout("written=48000 played=12000")
        diagnostic.trackStopRequested()
        diagnostic.trackStopOk()
        diagnostic.trackReleaseOk()

        assertEquals(PocketLabStage.TRACK_RELEASE_OK, diagnostic.snapshot().stage)
        assertTrue(diagnostic.summary().contains("Writes: 0"))
    }

    @Test fun partialWritesExposeValidRangesAndPreserveEveryByte() {
        val source = ByteArray(10) { it.toByte() }
        val output = ByteArrayOutputStream()
        val ranges = mutableListOf<Triple<Int, Int, Int>>()
        val attempts = PocketPartialWrite.writeFully(source, { bytes, offset, length ->
            output.write(bytes, offset, minOf(3, length))
            minOf(3, length)
        }) { offset, length, result ->
            ranges += Triple(offset, length, result)
        }

        assertEquals(4, attempts)
        assertEquals(source.toList(), output.toByteArray().toList())
        assertTrue(ranges.all { (offset, length, result) ->
            offset >= 0 && length >= 0 && offset + length <= source.size && result in 1..length
        })
    }

    @Test fun pipelineMapsPrebufferAndPostBufferWritesToCallbacks() {
        val callbackTrace = mutableListOf<PocketLabCallbackSnapshot>()
        val writeTrace = mutableListOf<PocketLabWriteRangeSnapshot>()
        val played = ByteArrayOutputStream()
        val pipeline = PocketPcm16Pipeline(
            prebufferBytes = 8,
            writer = { bytes, offset, length -> played.write(bytes, offset, length); length },
            onPlaybackStart = {},
            onCallbackTrace = { index, samples, pcmBytes, before, after, startedBefore, startedAfter, first, last ->
                callbackTrace += PocketLabCallbackSnapshot(
                    index, samples, pcmBytes, before, after, startedBefore, startedAfter, first, last
                )
            },
            onWriteTrace = { start, end, arraySize, offset, length, offsetEnd, result ->
                writeTrace += PocketLabWriteRangeSnapshot(start, end, arraySize, offset, length, offsetEnd, result)
            }
        )

        pipeline.accept(floatArrayOf(0.1f, 0.2f))
        pipeline.accept(floatArrayOf(0.3f, 0.4f))
        pipeline.accept(floatArrayOf(0.5f, 0.6f))

        assertEquals(3, callbackTrace.size)
        assertEquals(2, writeTrace.size)
        assertEquals(1, writeTrace[0].sourceCallbackStart)
        assertEquals(2, writeTrace[0].sourceCallbackEnd)
        assertEquals(8, writeTrace[0].arraySize)
        assertEquals(0, writeTrace[0].offset)
        assertEquals(8, writeTrace[0].requestedLength)
        assertEquals(8, writeTrace[0].offsetPlusLength)
        assertTrue(callbackTrace[1].playbackStartedAfter)
        assertEquals(8, callbackTrace[1].pcmBytes + callbackTrace[1].prebufferBefore)
        assertEquals(3, writeTrace[1].sourceCallbackStart)
        assertEquals(3, writeTrace[1].sourceCallbackEnd)
        assertEquals(12, played.size())
    }

    @Test fun fragmentedTransportSplits24000BytesAndPreservesPcmExactly() {
        val values = FloatArray(12_000) { (it % 97) / 100f }
        val source = PocketPcm.floatToPcm16(values)
        val played = ByteArrayOutputStream()
        val requests = mutableListOf<Int>()
        val pipeline = PocketPcm16Pipeline(
            prebufferBytes = source.size,
            writer = { bytes, offset, length ->
                requests += length
                played.write(bytes, offset, length)
                length
            },
            onPlaybackStart = {},
            transportMode = PocketTransportMode.FRAGMENTED_8192
        )

        pipeline.accept(values)

        assertEquals(listOf(8_192, 8_192, 7_616), requests)
        assertArrayEquals(source, played.toByteArray())
        assertEquals(source.size.toLong(), pipeline.writtenSamples * PocketAudioFormat.PCM16_BYTES_PER_SAMPLE)
    }

    @Test fun fragmentedTransportSplits48000BytesIntoBoundedWrites() {
        val values = FloatArray(24_000) { if (it % 2 == 0) 0.25f else -0.25f }
        val source = PocketPcm.floatToPcm16(values)
        val played = ByteArrayOutputStream()
        val requests = mutableListOf<Int>()
        val pipeline = PocketPcm16Pipeline(
            prebufferBytes = source.size,
            writer = { bytes, offset, length ->
                requests += length
                played.write(bytes, offset, length)
                length
            },
            onPlaybackStart = {},
            transportMode = PocketTransportMode.FRAGMENTED_8192
        )

        pipeline.accept(values)

        assertEquals(listOf(8_192, 8_192, 8_192, 8_192, 8_192, 7_040), requests)
        assertArrayEquals(source, played.toByteArray())
    }

    @Test fun fragmentedTransportHandlesPartialWritesWithoutChangingBytes() {
        val values = FloatArray(12_000) { if (it % 3 == 0) 0.5f else -0.5f }
        val source = PocketPcm.floatToPcm16(values)
        val played = ByteArrayOutputStream()
        val requests = mutableListOf<Int>()
        val pipeline = PocketPcm16Pipeline(
            prebufferBytes = source.size,
            writer = { bytes, offset, length ->
                requests += length
                val written = minOf(1_000, length)
                played.write(bytes, offset, written)
                written
            },
            onPlaybackStart = {},
            transportMode = PocketTransportMode.FRAGMENTED_8192
        )

        pipeline.accept(values)

        assertArrayEquals(source, played.toByteArray())
        assertTrue(requests.all { it in 1..8_192 })
        assertEquals(source.size, played.size())
    }

    @Test fun fragmentedTransportStopsOnNonPositiveWriteWithoutRetry() {
        val attempts = mutableListOf<Int>()
        val pipeline = PocketPcm16Pipeline(
            prebufferBytes = 2,
            writer = { _, _, length -> attempts += length; -32 },
            onPlaybackStart = {},
            transportMode = PocketTransportMode.FRAGMENTED_8192
        )

        try {
            pipeline.accept(floatArrayOf(0.1f))
            throw AssertionError("expected write failure")
        } catch (_: IllegalArgumentException) {
            // A non-positive AudioTrack result is terminal; no retry is issued.
        }
        assertEquals(listOf(2), attempts)
    }
}
