package com.kura.aria.voice.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.io.RandomAccessFile
import java.security.MessageDigest

class PocketVoiceProfileTest {
    @Test fun productionResourceMatchesApprovedAriaD2Artifact() {
        val productionAsset = sequenceOf(
            File("src/main/res/raw/aria_d_b2.emb"),
            File("app/src/main/res/raw/aria_d_b2.emb")
        ).first(File::isFile)
        val bytes = productionAsset.readBytes()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(PocketBuiltinVoiceProfile.EMBEDDING_BYTES, bytes.size.toLong())
        assertEquals(PocketBuiltinVoiceProfile.EMBEDDING_SHA256, sha256)
        assertTrue(PocketVoiceProfileFormat.validateEmbedding(productionAsset))
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(PocketVoiceProfileFormat.MAGIC, header.int)
        assertEquals(3, header.int)
        assertEquals(1L, header.long)
        assertEquals(376L, header.long)
        assertEquals(1024L, header.long)
    }

    @Test fun bundledProfileIsReusedAndCorruptCopyIsSafelyRepaired() {
        val bytes = javaClass.getResourceAsStream("/com/kura/aria/voice/pocket/aria_d_b2.emb")!!
            .use { it.readBytes() }
        val voices = Files.createTempDirectory("pocket-bundled-profile").toFile()
        try {
            val store = PocketVoiceProfileStore(voices)
            val profile = PocketBuiltinVoiceProfile.profile
            val installed = File(voices, ".cache/${profile.id}.emb")
            assertEquals(PocketVoiceProfileStore.BundledInstall.INSTALLED,
                store.installBundled(profile, ByteArrayInputStream(bytes), PocketBuiltinVoiceProfile.EMBEDDING_SHA256))
            assertTrue(bytes.contentEquals(installed.readBytes()))

            val before = installed.readBytes()
            assertEquals(PocketVoiceProfileStore.BundledInstall.REUSED,
                store.installBundled(profile, ByteArrayInputStream(bytes), PocketBuiltinVoiceProfile.EMBEDDING_SHA256))
            assertTrue(before.contentEquals(installed.readBytes()))

            installed.writeBytes(byteArrayOf(1, 2, 3))
            val corrupt = installed.readBytes()
            assertTrue(runCatching {
                store.installBundled(profile, ByteArrayInputStream(bytes), "0".repeat(64))
            }.isFailure)
            assertTrue(corrupt.contentEquals(installed.readBytes()))

            assertEquals(PocketVoiceProfileStore.BundledInstall.REPAIRED,
                store.installBundled(profile, ByteArrayInputStream(bytes), PocketBuiltinVoiceProfile.EMBEDDING_SHA256))
            assertTrue(bytes.contentEquals(installed.readBytes()))
            assertFalse(File(voices, profile.voiceFileName).exists())
            assertFalse(File(voices, ".cache/${profile.id}.kv").exists())
        } finally { voices.deleteRecursively() }
    }

    @Test fun stableAriaD2SelectionRestoresThePreviouslySelectedProfile() {
        val profile = PocketBuiltinVoiceProfile.profile
        val voices = listOf(
            PocketVoice("legacy", "Legacy", "legacy.wav"),
            PocketVoice(profile.id, profile.name, profile.voiceFileName, true, profile)
        )
        assertEquals(profile.id, PocketVoiceSelection.selected(voices, profile.id)?.id)
    }

    @Test fun acceptsRealPocketEncoderOutputAndImportsItWithoutReferenceAudio() {
        val bytes = javaClass.getResourceAsStream("/com/kura/aria/voice/pocket/aria_d_b2.emb")!!
            .use { it.readBytes() }
        val voices = Files.createTempDirectory("pocket-real-import").toFile()
        try {
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(PocketVoiceProfileFormat.MAGIC, header.int)
            assertEquals(3, header.int)
            assertEquals(1L, header.long)
            assertEquals(376L, header.long)
            assertEquals(1024L, header.long)
            assertEquals(PocketVoiceProfileFormat.MAX_BYTES, bytes.size)
            assertTrue(PocketVoiceProfileFormat.validateEmbedding(write(voices, "source.emb", bytes)))
            val profile = profile("aria-d-b2").copy(name = "ARIA-D-B2")
            PocketVoiceProfileStore(voices).import(profile, ByteArrayInputStream(bytes))
            assertTrue(PocketVoiceProfileValidator.isInstalledProfileValid(profile, voices))
            assertFalse(File(voices, profile.voiceFileName).exists())
            assertFalse(File(voices, ".cache/${profile.id}.kv").exists())
        } finally { voices.deleteRecursively() }
    }

    @Test fun importsValidEmbAsReusableProfileWithoutWaveOrKv() {
        val voices = Files.createTempDirectory("pocket-profile").toFile()
        try {
            val profile = profile("aria-a")
            val imported = PocketVoiceProfileStore(voices).import(profile, ByteArrayInputStream(validEmb()))
            assertEquals(profile, imported)
            assertTrue(PocketVoiceProfileValidator.isInstalledProfileValid(profile, voices))
            assertTrue(PocketInstallValidator.isVoiceAvailable(voices,
                PocketVoice(profile.id, profile.name, profile.voiceFileName, true, profile)))
            assertFalse(File(voices, profile.voiceFileName).exists())
            assertFalse(File(voices, ".cache/${profile.id}.kv").exists())
        } finally { voices.deleteRecursively() }
    }

    @Test fun rejectsBadMagicRankDimensionsTruncationAndPayloadMismatch() {
        val voices = Files.createTempDirectory("pocket-invalid").toFile()
        try {
            val badMagic = validEmb().also { it[0] = 0 }
            assertFalse(PocketVoiceProfileFormat.validateEmbedding(write(voices, "bad-magic.emb", badMagic)))

            val badRank = validEmb().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 4) }
            assertFalse(PocketVoiceProfileFormat.validateEmbedding(write(voices, "bad-rank.emb", badRank)))

            assertFalse(PocketVoiceProfileFormat.validateEmbedding(write(voices, "truncated.emb", validEmb().dropLast(1).toByteArray())))
            assertFalse(PocketVoiceProfileFormat.validateEmbedding(write(voices, "trailing.emb", validEmb() + byteArrayOf(0))))

            val badBatch = validEmb().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(8, 2) }
            val badFrames = validEmb().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(16, 377) }
            val badFeature = validEmb().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(24, 8) }
            assertFalse(PocketVoiceProfileFormat.validateEmbedding(write(voices, "bad-batch.emb", badBatch)))
            assertFalse(PocketVoiceProfileFormat.validateEmbedding(write(voices, "too-many-frames.emb", badFrames)))
            assertFalse(PocketVoiceProfileFormat.validateEmbedding(write(voices, "bad-feature-width.emb", badFeature)))

            val inconsistentPayload = validEmb().dropLast(4).toByteArray()
            assertFalse(PocketVoiceProfileFormat.validateEmbedding(write(voices, "payload-mismatch.emb", inconsistentPayload)))

            val nan = validEmb().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(32, Float.NaN) }
            val inf = validEmb().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(32, Float.POSITIVE_INFINITY) }
            assertFalse(PocketVoiceProfileFormat.validateEmbedding(write(voices, "nan.emb", nan)))
            assertFalse(PocketVoiceProfileFormat.validateEmbedding(write(voices, "inf.emb", inf)))

            val oversized = File(voices, "oversized.emb")
            RandomAccessFile(oversized, "rw").use { it.setLength(PocketVoiceProfileFormat.MAX_BYTES.toLong() + 1) }
            assertFalse(PocketVoiceProfileFormat.validateEmbedding(oversized))
        } finally { voices.deleteRecursively() }
    }

    @Test fun incompatibleMetadataAndPathTraversalAreRejected() {
        val voices = Files.createTempDirectory("pocket-path").toFile()
        try {
            assertFalse(PocketVoiceProfileValidator.validateMetadata(profile("../escape")))
            val wrongRuntime = profile("aria-b").copy(runtimeId = "other-runtime")
            assertFalse(PocketVoiceProfileValidator.validateMetadata(wrongRuntime))
            assertTrue(runCatching {
                PocketVoiceProfileStore(voices).import(profile("../escape"), ByteArrayInputStream(validEmb()))
            }.isFailure)
            assertFalse(File(voices.parentFile, "escape.emb").exists())
        } finally { voices.deleteRecursively() }
    }

    @Test fun failedImportLeavesPriorProfileUntouchedAndPublishesNoPartialFile() {
        val voices = Files.createTempDirectory("pocket-atomic").toFile()
        try {
            val store = PocketVoiceProfileStore(voices)
            val existing = profile("aria-d-b2")
            store.import(existing, ByteArrayInputStream(validEmb()))
            val installed = File(voices, ".cache/${existing.id}.emb")
            val before = installed.readBytes()

            assertTrue(runCatching {
                store.import(existing, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            }.isFailure)
            assertTrue(before.contentEquals(installed.readBytes()))

            val invalid = profile("aria-c")
            assertTrue(runCatching {
                store.import(invalid, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            }.isFailure)
            assertFalse(File(voices, ".cache/${invalid.id}.emb").exists())
            assertEquals(listOf("${existing.id}.emb"), File(voices, ".cache").listFiles()
                ?.filter { it.isFile }?.map { it.name })
        } finally { voices.deleteRecursively() }
    }

    @Test fun legacyWavVoiceAndEmbBackedVoiceAreBothValid() {
        val root = Files.createTempDirectory("pocket-mixed").toFile()
        try {
            val models = File(root, "models").apply { mkdirs() }
            val voices = File(root, "voices").apply { mkdirs() }
            PocketInstallValidator.requiredModelNames("fp32").forEach { File(models, it).writeText("model") }
            File(voices, "lola.wav").writeBytes(ByteArray(64) { 1 })
            val profile = profile("custom-aria")
            PocketVoiceProfileStore(voices).import(profile, ByteArrayInputStream(validEmb()))
            val pack = PocketPack("spanish", "Spanish", "es-ES", "fp32", 0.7f, 1, 4, root,
                listOf(PocketVoice("lola", "Lola", "lola.wav"),
                    PocketVoice(profile.id, profile.name, profile.voiceFileName, true, profile)))
            assertTrue(PocketInstallValidator.hasRequiredFiles(pack))
            File(voices, ".cache/${profile.id}.emb").delete()
            assertFalse(PocketInstallValidator.hasRequiredFiles(pack))
        } finally { root.deleteRecursively() }
    }

    private fun profile(id: String) = PocketVoiceProfile(
        id = id,
        name = "Voice $id",
        formatId = PocketVoiceProfileFormat.ID,
        runtimeId = PocketModelSpec.PROFILE_RUNTIME_ID,
        modelId = PocketModelSpec.ID,
        embeddingFileName = "$id.emb"
    )

    private fun validEmb(frames: Int = 2): ByteArray {
        val features = 1024
        val values = frames * features
        return ByteBuffer.allocate(8 + 3 * 8 + values * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(PocketVoiceProfileFormat.MAGIC)
            .putInt(3)
            .putLong(1).putLong(frames.toLong()).putLong(features.toLong())
            .apply { repeat(values) { putFloat(it / 10f) } }
            .array()
    }

    private fun write(root: File, name: String, bytes: ByteArray) =
        File(root, name).apply { writeBytes(bytes) }
}
