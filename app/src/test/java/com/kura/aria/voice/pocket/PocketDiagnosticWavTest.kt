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
    fun resolvesAndPreservesBothDiagnosticFilesForSharing() {
        val cache = temporaryFolder.newFolder("cache-both")
        val files = PocketDiagnosticWav.files(cache)
        files.forEachIndexed { index, file ->
            file.parentFile!!.mkdirs()
            file.writeBytes(ByteArray(96) { (it + index).toByte() })
        }
        val before = files.map { it.readBytes() }

        assertTrue(PocketDiagnosticWav.areShareable(files))
        assertEquals("pocket_audio_pre_pcm_f32.wav", files[0].name)
        assertEquals("pocket_audio_quality.wav", files[1].name)
        assertTrue(files.zip(before).all { (file, bytes) -> bytes.contentEquals(file.readBytes()) })
    }

    @Test
    fun rejectsSharingWhenEitherDiagnosticIsMissing() {
        val cache = temporaryFolder.newFolder("cache-incomplete")
        val files = PocketDiagnosticWav.files(cache)
        files[1].parentFile!!.mkdirs()
        files[1].writeBytes(ByteArray(64))
        assertFalse(PocketDiagnosticWav.areShareable(files))
    }

    @Test
    fun rejectsDirectoryAtExpectedWavPath() {
        val cache = temporaryFolder.newFolder("cache")
        val directory = PocketDiagnosticWav.file(cache)
        directory.mkdirs()

        assertFalse(PocketDiagnosticWav.isShareable(directory))
    }
}
