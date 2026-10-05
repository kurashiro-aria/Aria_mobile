package com.kura.aria.voice

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QwenModelStoreTest {
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun artifact(data: ByteArray, hash: String = sha(data)) = QwenModelArtifact(
        fileName = "model.gguf",
        bytes = data.size.toLong(),
        sha256 = hash,
        url = "https://models.invalid/model.gguf",
    )

    private class BytesSource(private val data: ByteArray) : QwenDownloadSource {
        var opens = 0
        override fun open(artifact: QwenModelArtifact, offset: Long): QwenDownloadResponse {
            opens += 1
            val body = data.copyOfRange(offset.toInt(), data.size)
            return response(if (offset > 0) 206 else 200, ByteArrayInputStream(body))
        }
    }

    @Test fun stateSequenceEndsInstalledAfterShaVerification() {
        val data = "qwen-model".repeat(512).toByteArray()
        val states = mutableListOf<QwenLabState>()
        val store = QwenModelStore(Files.createTempDirectory("qwen-store").toFile(), listOf(artifact(data)), BytesSource(data))

        val report = store.install { states += it.state }

        assertTrue(store.isInstalled())
        assertTrue(store.verifyInstalled())
        assertTrue(states.contains(QwenLabState.DOWNLOADING))
        assertTrue(states.contains(QwenLabState.VERIFYING))
        assertEquals(QwenLabState.INSTALLED, states.last())
        assertEquals(data.size.toLong(), report.storageBytes)
    }

    @Test fun validInstallPersistsAcrossStoreRecreationAndApkUpdateDoesNotRedownload() {
        val data = "persistent-model".repeat(200).toByteArray()
        val root = Files.createTempDirectory("qwen-persist").toFile()
        val spec = artifact(data)
        QwenModelStore(root, listOf(spec), BytesSource(data)).install()
        val sourceAfterUpdate = BytesSource(data)

        val report = QwenModelStore(root, listOf(spec), sourceAfterUpdate).install()

        assertTrue(report.reusedExistingInstall)
        assertEquals(0, sourceAfterUpdate.opens)
    }

    @Test fun invalidShaIsRejectedAndNeverMarkedInstalled() {
        val data = "corrupt".repeat(200).toByteArray()
        val store = QwenModelStore(
            Files.createTempDirectory("qwen-bad-sha").toFile(),
            listOf(artifact(data, "0".repeat(64))),
            BytesSource(data),
        )
        try {
            store.install()
            fail("SHA mismatch must fail")
        } catch (_: IllegalStateException) {
            assertFalse(store.isInstalled())
        }
    }

    @Test fun cancellationLeavesNoInstalledMarkerAndCanRetry() {
        val data = ByteArray(400_000) { (it % 251).toByte() }
        val spec = artifact(data)
        val root = Files.createTempDirectory("qwen-cancel").toFile()
        val source = BytesSource(data)
        val store = QwenModelStore(root, listOf(spec), source)
        try {
            store.install { if (it.downloadedBytes > 0L) store.cancel() }
            fail("Cancellation expected")
        } catch (_: CancellationException) {
            assertFalse(store.isInstalled())
        }

        assertTrue(QwenModelStore(root, listOf(spec), source).install().storageBytes > 0L)
    }

    @Test fun corruptPersistedFileFailsExplicitVerification() {
        val data = "verified".repeat(300).toByteArray()
        val root = Files.createTempDirectory("qwen-corrupt").toFile()
        val spec = artifact(data)
        val store = QwenModelStore(root, listOf(spec), BytesSource(data))
        store.install()
        root.resolve(spec.fileName).writeBytes(ByteArray(data.size))

        assertFalse(store.verifyInstalled())
        assertFalse(store.isInstalled())
    }

    @Test fun deleteRemovesModelButCanBeCalledAfterInstall() {
        val data = "delete-me".repeat(200).toByteArray()
        val store = QwenModelStore(Files.createTempDirectory("qwen-delete").toFile(), listOf(artifact(data)), BytesSource(data))
        store.install()

        store.delete()

        assertFalse(store.isInstalled())
        assertFalse(store.modelDirectory.exists())
    }

    private companion object {
        fun response(code: Int, input: InputStream) = object : QwenDownloadResponse {
            override val statusCode = code
            override val stream = input
            override fun close() = input.close()
        }
    }
}
