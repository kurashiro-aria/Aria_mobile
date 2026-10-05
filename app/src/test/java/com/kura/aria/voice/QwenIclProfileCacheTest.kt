package com.kura.aria.voice

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenIclProfileCacheTest {
    @Test fun b5cFingerprintAndPinnedCompatibilityAreStable() {
        val identity = identity()

        assertEquals(
            "b2bc79dc5a7504fe531d5fbd729a4b946423585efb1afff2ef9ab5a058f0a6b5",
            identity.masterSha256,
        )
        assertEquals("4562731dc612cb87b4cd1eedac275a17d1078773", identity.runtimeCommit)
        assertEquals("b7ee2e8c7459c3bea99da23e3d178125a7d1713c", identity.modelRevision)
        assertEquals(64, identity.fingerprint.length)
        assertTrue(Qwen06BAndroidPackage.packageFingerprint.contains(Qwen06BAndroidPackage.TALKER_FILE))
    }

    @Test fun validCacheIsAHitAndPersistsAcrossInstances() {
        val root = tempRoot()
        val cache = cache(root)
        val installed = cache.install(prompt(root.resolve("extract.tmp")))

        assertEquals(QwenIclCacheStatus.HIT, installed.status)
        assertEquals(QwenIclCacheStatus.HIT, cache(root).lookup().status)
        assertEquals(4, cache(root).lookup().summary?.speakerEmbeddingValues)
        assertTrue(root.resolve(".aria-icl-cache-v2").isFile)
    }

    @Test fun missingCacheIsAMissAndWavAloneCannotBecomeAHit() {
        val root = tempRoot()
        root.resolve(AriaB5CVoiceProfile.V1.referenceAsset).writeText("wav-placeholder")

        val lookup = cache(root).lookup()

        assertEquals(QwenIclCacheStatus.MISS, lookup.status)
        assertFalse(lookup.canUse)
    }

    @Test fun changedPromptIsDetectedAsCorrupt() {
        val root = tempRoot()
        val cache = cache(root)
        cache.install(prompt(root.resolve("extract.tmp")))
        cache.promptFile.appendText(" ")

        val lookup = cache.lookup()

        assertEquals(QwenIclCacheStatus.CORRUPT, lookup.status)
        assertEquals("size_mismatch", lookup.detail)
        assertFalse(lookup.canUse)
    }

    @Test fun incompatibleSchemaIsRejected() {
        val root = tempRoot()
        val cache = cache(root)
        cache.install(prompt(root.resolve("extract.tmp")))
        val metadata = root.resolve(".aria-icl-cache-v2")
        metadata.writeText(metadata.readText().replace("schema=2", "schema=99"))

        assertEquals(QwenIclCacheStatus.INCOMPATIBLE, cache.lookup().status)
    }

    @Test fun incompatibleModelOrRuntimeIdentityIsRejected() {
        val root = tempRoot()
        cache(root).install(prompt(root.resolve("extract.tmp")))
        val changed = identity().copy(runtimeCommit = "different-runtime")

        val lookup = QwenIclProfileCache(root, AriaB5CVoiceProfile.V1.promptFileName, changed).lookup()

        assertEquals(QwenIclCacheStatus.INCOMPATIBLE, lookup.status)
        assertNotEquals(identity().fingerprint, changed.fingerprint)
    }

    @Test fun corruptCacheCanBeInvalidatedForSafeColdFallback() {
        val root = tempRoot()
        val cache = cache(root)
        cache.install(prompt(root.resolve("extract.tmp")))
        cache.promptFile.writeText("not an ICL prompt")

        assertEquals(QwenIclCacheStatus.CORRUPT, cache.lookup().status)
        cache.invalidate()
        assertEquals(QwenIclCacheStatus.MISS, cache.lookup().status)
    }

    @Test fun legacy0247PromptCanBePromotedWithoutReencodingWav() {
        val root = tempRoot()
        val legacyPrompt = prompt(root.resolve(AriaB5CVoiceProfile.V1.promptFileName))
        val identity = identity()
        root.resolve(".profile-v1").writeText(
            "${identity.masterSha256}|${identity.transcriptSha256}|${identity.modelRevision}",
        )
        val cache = cache(root)

        val legacy = cache.lookup()
        assertEquals(QwenIclCacheStatus.LEGACY_HIT, legacy.status)
        cache.promoteLegacy(legacyPrompt, requireNotNull(legacy.summary))

        assertEquals(QwenIclCacheStatus.HIT, cache.lookup().status)
        assertFalse(root.resolve(".profile-v1").exists())
    }

    @Test fun parserRejectsInconsistentReferenceCodeShape() {
        val file = tempRoot().resolve("bad.json").apply {
            writeText(validPrompt().replace("\"frames\": 2", "\"frames\": 3"))
        }

        val rejected = runCatching { QwenIclPromptInspector.inspect(file) }.isFailure

        assertTrue(rejected)
    }

    private fun cache(root: File) = QwenIclProfileCache(
        root,
        AriaB5CVoiceProfile.V1.promptFileName,
        identity(),
    )

    private fun identity() = QwenIclCacheIdentity.forProfile(AriaB5CVoiceProfile.V1)

    private fun prompt(file: File): File = file.apply { writeText(validPrompt()) }

    private fun validPrompt(): String = """
        {
          "format": "qwen3_tts_icl_prompt_v1",
          "reference_text": "Hola Kura",
          "reference_token_ids": [1, 2, 3],
          "speaker_embedding": [0.1, 0.2, 0.3, 0.4],
          "reference_codes": {
            "frames": 2,
            "codebooks": 2,
            "codes": [10, 11, 12, 13]
          }
        }
    """.trimIndent()

    private fun tempRoot(): File = Files.createTempDirectory("aria-icl-cache").toFile()
}
