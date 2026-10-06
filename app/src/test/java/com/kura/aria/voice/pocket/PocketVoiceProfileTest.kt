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

class PocketVoiceProfileTest {
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

    @Test fun rejectsBadMagicTruncatedPayloadAndIncompatibleShape() {
        val voices = Files.createTempDirectory("pocket-invalid").toFile()
        try {
            val badMagic = validEmb().also { it[0] = 0 }
            assertFalse(PocketVoiceProfileFormat.validateEmbedding(write(voices, "bad-magic.emb", badMagic)))
            assertFalse(PocketVoiceProfileFormat.validateEmbedding(write(voices, "truncated.emb", validEmb().dropLast(1).toByteArray())))
            assertFalse(PocketVoiceProfileFormat.validateEmbedding(write(voices, "bad-shape.emb", validEmb(channels = 7))))
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

    private fun validEmb(channels: Int = 8): ByteArray {
        val values = 2
        return ByteBuffer.allocate(8 + 3 * 8 + channels * values * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(PocketVoiceProfileFormat.MAGIC)
            .putInt(3)
            .putLong(1).putLong(channels.toLong()).putLong(values.toLong())
            .apply { repeat(channels * values) { putFloat(it / 10f) } }
            .array()
    }

    private fun write(root: File, name: String, bytes: ByteArray) =
        File(root, name).apply { writeBytes(bytes) }
}
