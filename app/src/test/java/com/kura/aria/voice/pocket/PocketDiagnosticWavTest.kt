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
    fun resolvesExistingDiagnosticAndPreservesItsBytes() {
        val cache = temporaryFolder.newFolder("cache")
        val expected = File(cache, "pocket-diagnostics/pocket_audio_quality.wav")
        expected.parentFile!!.mkdirs()
        val originalBytes = ByteArray(128) { it.toByte() }
        expected.writeBytes(originalBytes)

        val resolved = PocketDiagnosticWav.file(cache)

        assertEquals(expected.canonicalFile, resolved.canonicalFile)
        assertTrue(PocketDiagnosticWav.isShareable(resolved))
        assertArrayEquals(originalBytes, resolved.readBytes())
    }

    @Test
    fun rejectsMissingAndTooSmallDiagnosticFiles() {
        val cache = temporaryFolder.newFolder("cache")
        val missing = PocketDiagnosticWav.file(cache)
        assertFalse(PocketDiagnosticWav.isShareable(missing))

        missing.parentFile!!.mkdirs()
        missing.writeBytes(ByteArray(44))
        assertFalse(PocketDiagnosticWav.isShareable(missing))
    }

    @Test
    fun rejectsDirectoryAtExpectedWavPath() {
        val cache = temporaryFolder.newFolder("cache")
        val directory = PocketDiagnosticWav.file(cache)
        directory.mkdirs()

        assertFalse(PocketDiagnosticWav.isShareable(directory))
    }
}
