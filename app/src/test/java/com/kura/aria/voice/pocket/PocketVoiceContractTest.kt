package com.kura.aria.voice.pocket

import com.kura.aria.emotion.AriaEmotion
import com.kura.aria.personality.ExpressionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class PocketVoiceContractTest {
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
        val values = ByteBuffer.wrap(PocketPcm.floatToPcm16(floatArrayOf(-2f, 0f, 2f)))
            .order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(-32767, values.short.toInt())
        assertEquals(0, values.short.toInt())
        assertEquals(32767, values.short.toInt())
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
