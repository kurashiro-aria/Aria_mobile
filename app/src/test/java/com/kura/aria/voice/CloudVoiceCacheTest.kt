package com.kura.aria.voice

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CloudVoiceCacheTest {
    @Test fun cacheIsBoundedAndDoesNotUseConversationTextAsFilename() {
        val dir = Files.createTempDirectory("aria-voice-cache").toFile()
        try {
            val cache = CloudVoiceCache(dir, maxFiles = 2, maxBytes = 200)
            val audio = "RIFF".toByteArray() + ByteArray(60)
            val first = cache.store("frase privada uno", audio)
            Thread.sleep(2)
            cache.store("frase privada dos", audio)
            Thread.sleep(2)
            cache.store("frase privada tres", audio)
            assertFalse(first.exists())
            assertTrue(dir.listFiles().orEmpty().size <= 2)
            assertFalse(dir.listFiles().orEmpty().any { it.name.contains("privada") })
            assertNotNull(cache.find("frase privada tres"))
        } finally { dir.deleteRecursively() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyAudioIsNeverCached() {
        val dir = Files.createTempDirectory("aria-voice-empty").toFile()
        try { CloudVoiceCache(dir).store("empty", ByteArray(0)) }
        finally { dir.deleteRecursively() }
    }
}
