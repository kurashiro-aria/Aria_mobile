package com.kura.aria.voice

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class PiperModelStoreTest {
    @Test fun missingModelIsNotInstalled() {
        val root = Files.createTempDirectory("aria-piper-store").toFile()
        val store = PiperModelStore(root)
        assertFalse(store.isInstalled())
        assertEquals(
            root.resolve(PiperSpanishPrototype.MODEL_ID).canonicalFile,
            store.modelDirectory.canonicalFile
        )
    }

    @Test fun packageIsPinnedToOfficialArchiveAndExpectedModel() {
        assertEquals("vits-piper-es_MX-claude-high", PiperModelPackage.ROOT_DIR)
        assertEquals(67_207_890L, PiperModelPackage.ARCHIVE_BYTES)
        assertEquals(62_949_322L, PiperModelPackage.MODEL_BYTES)
        assertEquals("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-es_MX-claude-high.tar.bz2", PiperModelPackage.ARCHIVE_URL)
    }
}
