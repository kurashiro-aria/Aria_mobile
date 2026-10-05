package com.kura.aria.voice.pocket

import com.kura.aria.emotion.AriaEmotion
import com.kura.aria.personality.ExpressionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class PocketVoiceContractTest {
    @Test fun pocketAudioFormatMatchesPinnedNativeDecoder() {
        assertEquals(24_000, PocketAudioFormat.SAMPLE_RATE)
        assertEquals(1, PocketAudioFormat.CHANNELS)
        assertEquals("FLOAT32_LE", PocketAudioFormat.NATIVE_SAMPLE_FORMAT)
        assertEquals("PCM16_LE", PocketAudioFormat.PLAYBACK_SAMPLE_FORMAT)
        assertEquals(2, PocketAudioFormat.PCM16_BYTES_PER_SAMPLE)
    }

    @Test fun modelSpecPinsSpanishReleaseAndChecksum() {
        assertTrue(PocketModelSpec.DOWNLOAD_URL.endsWith("PocketTTS-spanish-FP32.zip"))
        assertEquals(64, PocketModelSpec.ARCHIVE_SHA256.length)
        assertEquals(24_000, PocketModelSpec.SAMPLE_RATE)
        assertTrue(PocketModelSpec.DOWNLOAD_BYTES > 190L * 1024 * 1024)
    }

    @Test fun installValidationRequiresEveryModelAndRealVoice() {
        val root = Files.createTempDirectory("pocket-valid").toFile()
        try {
            val models = File(root, "models").apply { mkdirs() }
            val voices = File(root, "voices").apply { mkdirs() }
            PocketInstallValidator.requiredModelNames("fp32").forEach { File(models, it).writeText("x") }
            File(voices, "lola.wav").writeBytes(ByteArray(45) { 1 })
            val pack = PocketPack("spanish", "Spanish", "es-ES", "fp32", 0.7f, 1, 4,
                root, listOf(PocketVoice("lola", "Lola", "lola.wav")))
            assertTrue(PocketInstallValidator.hasRequiredFiles(pack))
            File(models, "flow_lm_main.onnx").delete()
            assertFalse(PocketInstallValidator.hasRequiredFiles(pack))
        } finally { root.deleteRecursively() }
    }

    @Test fun int8AndFp32RequireDifferentDecoderNames() {
        assertTrue("mimi_decoder.onnx" in PocketInstallValidator.requiredModelNames("fp32"))
        assertTrue("mimi_decoder_int8.onnx" in PocketInstallValidator.requiredModelNames("int8"))
        assertNotEquals(PocketInstallValidator.requiredModelNames("fp32"),
            PocketInstallValidator.requiredModelNames("int8"))
    }

    @Test fun archiveTraversalIsRejected() {
        val root = Files.createTempDirectory("pocket-zip").toFile()
        try {
            assertTrue(PocketInstallValidator.safeArchivePath(root, "models/a.onnx").path.startsWith(root.path))
            assertTrue(runCatching { PocketInstallValidator.safeArchivePath(root, "../escape") }.isFailure)
        } finally { root.deleteRecursively() }
    }

    @Test fun checksumIsStableAndDetectsChanges() {
        val file = File.createTempFile("pocket-sha", ".bin")
        try {
            file.writeText("ARIA")
            val first = PocketModelManager.sha256(file)
            assertEquals(first, PocketModelManager.sha256(file))
            file.appendText(" Pocket")
            assertNotEquals(first, PocketModelManager.sha256(file))
        } finally { file.delete() }
    }

    @Test fun selectedVoiceUsesStableIdAndFallsBackSafely() {
        val voices = listOf(PocketVoice("lola", "Lola", "lola.wav"),
            PocketVoice("custom-aria", "ARIA", "custom-aria.wav", true))
        assertEquals("custom-aria", PocketVoiceSelection.selected(voices, "custom-aria")?.id)
        assertEquals("lola", PocketVoiceSelection.selected(voices, "missing")?.id)
    }

    @Test fun fallbackNeverCreatesDoubleVoiceAfterPocketStarts() {
        assertTrue(PocketFallbackPolicy.useAndroidFallback(LocalVoiceEngine.POCKET, false, false, false))
        assertTrue(PocketFallbackPolicy.useAndroidFallback(LocalVoiceEngine.POCKET, true, false, false))
        assertFalse(PocketFallbackPolicy.useAndroidFallback(LocalVoiceEngine.POCKET, true, true, false))
        assertFalse(PocketFallbackPolicy.useAndroidFallback(LocalVoiceEngine.POCKET, true, false, true))
        assertTrue(PocketFallbackPolicy.useAndroidFallback(LocalVoiceEngine.ANDROID_FALLBACK, true, false, false))
    }

    @Test fun emotionAndExpressionAreRoutedWithoutInventingPitchOrSpeed() {
        val direction = AriaPocketProsodyDirector.resolve(
            AriaEmotion.HAPPY, ExpressionStyle.PLAYFUL, 0.65f)
        assertEquals(AriaEmotion.HAPPY, direction.emotion)
        assertEquals(ExpressionStyle.PLAYFUL, direction.expressionStyle)
        assertEquals(0.65f, direction.temperature)
    }

    @Test fun pcmConversionClipsAndUsesLittleEndianPcm16() {
        val bytes = PocketPcm.floatToPcm16(floatArrayOf(-2f, Float.NaN, 0f, Float.POSITIVE_INFINITY, 2f))
        assertEquals(10, bytes.size)
        val values = ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(-32767, values.short.toInt())
        assertEquals(0, values.short.toInt())
        assertEquals(0, values.short.toInt())
        assertEquals(0, values.short.toInt())
        assertEquals(32767, values.short.toInt())
    }

    @Test fun pcmInspectorReportsClippingNonFiniteAndDcOffset() {
        val inspector = PocketPcmInspector()
        inspector.accept(floatArrayOf(-2f, -0.5f, 0.5f, 2f, Float.NaN, Float.NEGATIVE_INFINITY))
        val stats = inspector.snapshot()
        assertEquals(6L, stats.sampleCount)
        assertEquals(-2f, stats.minimum)
        assertEquals(2f, stats.maximum)
        assertEquals(2f, stats.peakAbsolute)
        assertEquals(1L, stats.nanCount)
        assertEquals(1L, stats.infiniteCount)
        assertEquals(2L, stats.clippedSamples)
        assertEquals(0.0, stats.dcOffset, 0.000001)
    }

    @Test fun chunksRemainOrderedAndPartialWritesAreCompletedBeforePlayback() {
        val output = ByteArrayOutputStream()
        var starts = 0
        var bytesAtStart = -1
        val pipeline = PocketPcm16Pipeline(
            prebufferBytes = 8,
            writer = { bytes, offset, length ->
                val accepted = minOf(3, length)
                output.write(bytes, offset, accepted)
                accepted
            },
            onPlaybackStart = { starts++; bytesAtStart = output.size() }
        )
        val first = floatArrayOf(-1f, -0.5f)
        val second = floatArrayOf(0.5f, 1f)
        val third = floatArrayOf(0.25f)
        pipeline.accept(first)
        assertEquals(0, starts)
        pipeline.accept(second)
        assertEquals(1, starts)
        assertEquals(8, bytesAtStart)
        pipeline.accept(third)
        pipeline.finish()
        val expected = PocketPcm.floatToPcm16(first) + PocketPcm.floatToPcm16(second) +
            PocketPcm.floatToPcm16(third)
        assertTrue(expected.contentEquals(output.toByteArray()))
        assertEquals(5L, pipeline.writtenSamples)
    }

    @Test fun diagnosticWavContainsExactPlaybackPcm() {
        val root = Files.createTempDirectory("pocket-wav").toFile()
        try {
            val target = File(root, "quality.wav")
            val playback = ByteArrayOutputStream()
            val values = floatArrayOf(-1f, -0.25f, 0f, 0.25f, 1f)
            PocketPcm16Pipeline(
                prebufferBytes = 2,
                writer = { bytes, offset, length -> playback.write(bytes, offset, length); length },
                onPlaybackStart = {},
                wavWriter = PocketWavWriter(target)
            ).apply { accept(values); finish() }
            val wav = target.readBytes()
            assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals("WAVE", wav.copyOfRange(8, 12).toString(Charsets.US_ASCII))
            assertEquals(PocketAudioFormat.SAMPLE_RATE,
                ByteBuffer.wrap(wav, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int)
            assertTrue(playback.toByteArray().contentEquals(wav.copyOfRange(44, wav.size)))
        } finally { root.deleteRecursively() }
    }

    @Test fun uiEnablesOnlyValidOperations() {
        assertTrue(PocketUiPolicy.canDownload(PocketInstallPhase.NOT_INSTALLED))
        assertTrue(PocketUiPolicy.canDownload(PocketInstallPhase.ERROR))
        assertFalse(PocketUiPolicy.canDownload(PocketInstallPhase.DOWNLOADING))
        assertFalse(PocketUiPolicy.canSynthesize(PocketInstallPhase.INSTALLED, emptyList()))
        assertTrue(PocketUiPolicy.canSynthesize(PocketInstallPhase.READY,
            listOf(PocketVoice("lola", "Lola", "lola.wav"))))
    }
}
