package com.kura.aria.voice.pocket

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PocketDiagnosticWavTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun normalChatAndVoiceTestBothRouteToTheSameDiagnosticPair() {
        val cache = temporaryFolder.newFolder("cache-routes")
        val chatTargets = PocketDiagnosticWav.forNormalChat(cache)
        val voiceTestTargets = PocketDiagnosticWav.forVoiceTest(cache)

        assertEquals(
            listOf(PocketDiagnosticWav.FLOAT_FILE_NAME, PocketDiagnosticWav.PCM_FILE_NAME),
            chatTargets.files().map { it.name }
        )
        assertEquals(chatTargets.files(), voiceTestTargets.files())
    }

    @Test
    fun oneNormalChatPipelinePublishesBothWavsFromTheSameSamples() {
        val cache = temporaryFolder.newFolder("cache-chat")
        val targets = PocketDiagnosticWav.forNormalChat(cache)
        val capture = PocketDiagnosticWav.beginCapture(targets)
        val source = floatArrayOf(-0.75f, -0.125f, 0.25f, 0.625f, 0.9f)
        val pcmSink = java.io.ByteArrayOutputStream()
        val pipeline = PocketPcm16Pipeline(
            prebufferBytes = 1_200,
            writer = { bytes, offset, length -> pcmSink.write(bytes, offset, length); length },
            onPlaybackStart = {},
            wavWriter = PocketWavWriter(capture.stagedTargets.pcm16File),
            floatWavWriter = PocketFloatWavWriter(capture.stagedTargets.float32File)
        )

        pipeline.accept(source)
        pipeline.finish()
        assertTrue(capture.publish())

        val files = PocketDiagnosticWav.files(cache)
        assertTrue(PocketDiagnosticWav.areShareable(files))
        val floatPayload = files[0].readBytes().copyOfRange(44, files[0].length().toInt())
        val pcmPayload = files[1].readBytes().copyOfRange(44, files[1].length().toInt())
        val decoded = java.nio.ByteBuffer.wrap(floatPayload).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        source.forEach { assertEquals(java.lang.Float.floatToRawIntBits(it), decoded.int) }
        assertArrayEquals(PocketPcm.floatToPcm16(source), pcmPayload)
        assertArrayEquals(pcmSink.toByteArray(), pcmPayload)
    }

    @Test
    fun voiceTestRouteProducesBothCommittedWavs() {
        val cache = temporaryFolder.newFolder("cache-test")
        val targets = PocketDiagnosticWav.forVoiceTest(cache)
        val capture = PocketDiagnosticWav.beginCapture(targets)
        writePair(capture, floatArrayOf(-0.5f, 0.25f, 0.75f))

        assertTrue(capture.publish())
        assertTrue(PocketDiagnosticWav.areShareable(PocketDiagnosticWav.files(cache)))
    }

    @Test
    fun aNewInferenceInvalidatesThePreviousPairBeforeWriting() {
        val cache = temporaryFolder.newFolder("cache-replace")
        val oldCapture = PocketDiagnosticWav.beginCapture(PocketDiagnosticWav.targets(cache))
        writePair(oldCapture, floatArrayOf(0.1f, 0.2f))
        assertTrue(oldCapture.publish())
        val oldBytes = PocketDiagnosticWav.files(cache).map { it.readBytes() }

        val newCapture = PocketDiagnosticWav.beginCapture(PocketDiagnosticWav.targets(cache))

        assertFalse(PocketDiagnosticWav.areShareable(PocketDiagnosticWav.files(cache)))
        assertFalse(PocketDiagnosticWav.files(cache).any { it.exists() })
        assertTrue(oldBytes.all { it.isNotEmpty() })
        newCapture.abort()
    }

    @Test
    fun failedOrCancelledCaptureDoesNotPublishAnIncompletePair() {
        val cache = temporaryFolder.newFolder("cache-failed")
        val capture = PocketDiagnosticWav.beginCapture(PocketDiagnosticWav.targets(cache))
        PocketFloatWavWriter(capture.stagedTargets.float32File).apply {
            append(floatArrayOf(0.25f))
            finish()
        }

        assertFalse(capture.publish())
        assertFalse(PocketDiagnosticWav.areShareable(PocketDiagnosticWav.files(cache)))
        assertFalse(PocketDiagnosticWav.files(cache).any { it.exists() })
    }

    @Test
    fun cancellationAbortsBothStagedFilesWithoutPublishingEither() {
        val cache = temporaryFolder.newFolder("cache-cancelled")
        val capture = PocketDiagnosticWav.beginCapture(PocketDiagnosticWav.targets(cache))
        writePair(capture, floatArrayOf(0.1f, -0.2f))

        capture.abort()

        assertFalse(PocketDiagnosticWav.areShareable(PocketDiagnosticWav.files(cache)))
        assertFalse(PocketDiagnosticWav.files(cache).any { it.exists() })
    }

    @Test
    fun missingMessagesIdentifyWhichWavIsUnavailable() {
        val cache = temporaryFolder.newFolder("cache-missing")
        assertEquals("Faltan ambos WAV diagnósticos.", PocketDiagnosticWav.missingMessage(cache))

        val files = PocketDiagnosticWav.files(cache)
        files[1].parentFile!!.mkdirs()
        PocketWavWriter(files[1]).apply {
            append(byteArrayOf(1, 2))
            finish()
        }
        assertEquals("Falta WAV FLOAT32 diagnóstico.", PocketDiagnosticWav.missingMessage(cache))
        files[0].parentFile!!.mkdirs()
        PocketFloatWavWriter(files[0]).apply {
            append(floatArrayOf(0.25f))
            finish()
        }
        files[1].delete()
        assertEquals("Falta WAV PCM16 diagnóstico.", PocketDiagnosticWav.missingMessage(cache))
    }

    @Test
    fun sharingProducesExactlyTwoDistinctUrisWithExpectedNamesAndPreservesBytes() {
        val cache = temporaryFolder.newFolder("cache-share")
        val capture = PocketDiagnosticWav.beginCapture(PocketDiagnosticWav.targets(cache))
        writePair(capture, floatArrayOf(-0.8f, 0.4f))
        assertTrue(capture.publish())
        val files = PocketDiagnosticWav.files(cache)
        val before = files.map { it.readBytes() }

        val uris = PocketDiagnosticWav.shareUris(files) { file -> "content://test/${file.name}" }

        assertEquals(2, uris.size)
        assertEquals(2, uris.distinct().size)
        assertEquals(listOf("content://test/pocket_audio_pre_pcm_f32.wav", "content://test/pocket_audio_quality.wav"), uris)
        assertEquals("android.intent.action.SEND_MULTIPLE", PocketDiagnosticWav.SHARE_ACTION)
        assertEquals("audio/wav", PocketDiagnosticWav.SHARE_MIME_TYPE)
        assertTrue(files.zip(before).all { (file, bytes) -> bytes.contentEquals(file.readBytes()) })
    }

    @Test
    fun refusesDuplicateShareUris() {
        val cache = temporaryFolder.newFolder("cache-duplicate-uri")
        val capture = PocketDiagnosticWav.beginCapture(PocketDiagnosticWav.targets(cache))
        writePair(capture, floatArrayOf(0.1f, 0.2f))
        assertTrue(capture.publish())

        try {
            PocketDiagnosticWav.shareUris(PocketDiagnosticWav.files(cache)) { "content://same" }
            throw AssertionError("duplicate URIs should be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: Android Sharesheet must receive the two distinct files.
        }
    }

    private fun writePair(capture: PocketDiagnosticCapture, samples: FloatArray) {
        PocketFloatWavWriter(capture.stagedTargets.float32File).apply {
            append(samples)
            finish()
        }
        PocketWavWriter(capture.stagedTargets.pcm16File).apply {
            append(PocketPcm.floatToPcm16(samples))
            finish()
        }
    }
}
