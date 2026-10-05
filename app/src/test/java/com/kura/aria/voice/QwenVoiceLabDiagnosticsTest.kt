package com.kura.aria.voice

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QwenVoiceLabDiagnosticsTest {
    @Test fun ariaB5cMasterIsStillBitIdenticalPcm24MonoAt24Khz() {
        val master = listOf(
            File("app/src/main/assets/aria_voice_B5C_MASTER.wav"),
            File("src/main/assets/aria_voice_B5C_MASTER.wav"),
        ).firstOrNull { it.isFile } ?: throw AssertionError("Missing ARIA-B5C MASTER asset")

        val format = QwenReferenceWavInspector.inspect(master)

        assertEquals("b2bc79dc5a7504fe531d5fbd729a4b946423585efb1afff2ef9ab5a058f0a6b5", sha256(master))
        assertEquals(QwenReferenceWavFormat.PCM, format.audioFormat)
        assertEquals(1, format.channels)
        assertEquals(24_000, format.sampleRate)
        assertEquals(24, format.bitsPerSample)
        assertTrue(format.isSupportedByPinnedRuntime)
    }

    @Test fun pcm24ReferenceIsExplicitlyAcceptedByRuntimeContract() {
        val file = Files.createTempFile("aria-pcm24", ".wav").toFile().apply {
            writeBytes(wav(bitsPerSample = 24, data = byteArrayOf(0, 0, 0)))
        }

        val format = QwenReferenceWavInspector.inspect(file)

        assertEquals(24, format.bitsPerSample)
        assertTrue(format.isSupportedByPinnedRuntime)
    }

    @Test fun unsupportedPcmDepthIsRejectedBeforeJni() {
        val file = Files.createTempFile("aria-pcm20", ".wav").toFile().apply {
            writeBytes(wav(bitsPerSample = 20, data = byteArrayOf(0, 0, 0)))
        }

        assertFalse(QwenReferenceWavInspector.inspect(file).isSupportedByPinnedRuntime)
    }

    @Test fun malformedReferenceGetsStableVoiceProfileCode() {
        val file = Files.createTempFile("aria-invalid", ".wav").toFile().apply { writeText("not wav") }
        try {
            QwenReferenceWavInspector.inspect(file)
            fail("Malformed WAV must fail")
        } catch (error: QwenLabException) {
            assertEquals(QwenLabFailureCode.VOICE_PROFILE_FAILED, error.code)
        }
    }

    @Test fun codedFailureIsNotCollapsedToIllegalStateException() {
        val failure = QwenVoiceLabDiagnostics.classify(
            "load",
            QwenLabException(QwenLabFailureCode.ICL_FAILED, "No se pudo crear el perfil ICL"),
        )

        assertEquals(QwenLabFailureCode.ICL_FAILED, failure.code)
        assertEquals("No se pudo crear el perfil ICL", failure.safeDetail)
    }

    @Test fun jniAndAbiFailuresHaveDistinctCodes() {
        assertEquals(
            QwenLabFailureCode.JNI_LIBRARY_FAILED,
            QwenVoiceLabDiagnostics.classify("load", UnsatisfiedLinkError("dlopen failed")).code,
        )
        assertEquals(
            QwenLabFailureCode.ABI_MISMATCH,
            QwenVoiceLabDiagnostics.classify("load", UnsatisfiedLinkError("wrong ELF class")).code,
        )
    }

    private fun wav(bitsPerSample: Int, data: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        bytes.write("RIFF".toByteArray())
        bytes.writeLe32(36 + data.size)
        bytes.write("WAVEfmt ".toByteArray())
        bytes.writeLe32(16)
        bytes.writeLe16(QwenReferenceWavFormat.PCM)
        bytes.writeLe16(1)
        bytes.writeLe32(24_000)
        val blockAlign = (bitsPerSample + 7) / 8
        bytes.writeLe32(24_000 * blockAlign)
        bytes.writeLe16(blockAlign)
        bytes.writeLe16(bitsPerSample)
        bytes.write("data".toByteArray())
        bytes.writeLe32(data.size)
        bytes.write(data)
        if (data.size % 2 != 0) bytes.write(0)
        return bytes.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLe16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeLe32(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
