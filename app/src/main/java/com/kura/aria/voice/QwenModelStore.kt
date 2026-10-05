package com.kura.aria.voice

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

enum class QwenLabState {
    NOT_INSTALLED, DOWNLOADING, VERIFYING, INSTALLED, LOADING, READY, SYNTHESIZING, ERROR
}

data class QwenModelArtifact(
    val fileName: String,
    val bytes: Long,
    val sha256: String,
    val url: String,
)

object Qwen06BAndroidPackage {
    const val RUNTIME_REPOSITORY = "Danmoreng/qwen3-tts.cpp"
    const val RUNTIME_COMMIT = "4562731dc612cb87b4cd1eedac275a17d1078773"
    const val MODEL_REPOSITORY = "Serveurperso/Qwen3-TTS-GGUF"
    const val MODEL_REVISION = "b7ee2e8c7459c3bea99da23e3d178125a7d1713c"
    const val QUANTIZATION = "Q8_0"
    const val TALKER_FILE = "qwen-talker-0.6b-base-Q8_0.gguf"
    const val TOKENIZER_FILE = "qwen-tokenizer-12hz-Q8_0.gguf"

    private const val BASE =
        "https://huggingface.co/$MODEL_REPOSITORY/resolve/$MODEL_REVISION/"

    val artifacts = listOf(
        QwenModelArtifact(
            TALKER_FILE,
            992_615_488L,
            "d54dbaf10591421fa764ed630d764efa717ae40cd959bd48c66d4eb1af226426",
            "$BASE$TALKER_FILE?download=true",
        ),
        QwenModelArtifact(
            TOKENIZER_FILE,
            291_150_624L,
            "1883beeed99348fc35e23dd225e9082f93f6f8c109330a33d935baa8acdbfd94",
            "$BASE$TOKENIZER_FILE?download=true",
        ),
    )

    val downloadBytes: Long = artifacts.sumOf { it.bytes }
    val packageFingerprint: String = artifacts.joinToString("|") { "${it.fileName}:${it.sha256}" }
}

data class QwenModelProgress(
    val state: QwenLabState,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = Qwen06BAndroidPackage.downloadBytes,
    val detail: String? = null,
)

data class QwenInstallReport(
    val downloadMs: Long,
    val verifyMs: Long,
    val downloadedBytes: Long,
    val storageBytes: Long,
    val reusedExistingInstall: Boolean,
)

interface QwenDownloadResponse : Closeable {
    val statusCode: Int
    val stream: InputStream
}

fun interface QwenDownloadSource {
    fun open(artifact: QwenModelArtifact, offset: Long): QwenDownloadResponse
}

class HttpQwenDownloadSource : QwenDownloadSource {
    override fun open(artifact: QwenModelArtifact, offset: Long): QwenDownloadResponse {
        val connection = (URL(artifact.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }
        val code = connection.responseCode
        return object : QwenDownloadResponse {
            override val statusCode: Int = code
            override val stream: InputStream
                get() = connection.inputStream
            override fun close() = connection.disconnect()
        }
    }
}

/**
 * Transactional, app-private model installation. A completed marker is only
 * written after every pinned artifact has passed its SHA-256 check.
 */
class QwenModelStore(
    val modelDirectory: File,
    private val artifacts: List<QwenModelArtifact> = Qwen06BAndroidPackage.artifacts,
    private val source: QwenDownloadSource = HttpQwenDownloadSource(),
) {
    private val cancelled = AtomicBoolean(false)
    private val marker: File get() = File(modelDirectory, ".aria-qwen-package-v1")

    fun isInstalled(): Boolean = marker.isFile &&
        marker.readText().trim() == fingerprint() &&
        artifacts.all { File(modelDirectory, it.fileName).length() == it.bytes }

    fun verifyInstalled(): Boolean {
        if (!isInstalled()) return false
        val valid = artifacts.all { artifact ->
            val file = File(modelDirectory, artifact.fileName)
            file.length() == artifact.bytes && sha256(file) == artifact.sha256
        }
        if (!valid) marker.delete()
        return valid
    }

    @Synchronized
    fun install(onProgress: (QwenModelProgress) -> Unit = {}): QwenInstallReport {
        cancelled.set(false)
        if (isInstalled()) {
            onProgress(QwenModelProgress(QwenLabState.INSTALLED, totalBytes(), totalBytes()))
            return QwenInstallReport(0L, 0L, 0L, storageBytes(), true)
        }
        modelDirectory.mkdirs()
        var downloadedThisRun = 0L
        var completedBytes = artifacts.sumOf { artifact ->
            File(modelDirectory, artifact.fileName).takeIf {
                it.length() == artifact.bytes && sha256(it) == artifact.sha256
            }?.length() ?: 0L
        }
        val downloadStarted = System.nanoTime()
        artifacts.forEach { artifact ->
            val target = File(modelDirectory, artifact.fileName)
            if (target.length() == artifact.bytes && sha256(target) == artifact.sha256) return@forEach
            target.delete()
            val part = File(modelDirectory, ".${artifact.fileName}.part")
            if (part.length() > artifact.bytes) part.delete()
            var offset = part.length()
            onProgress(QwenModelProgress(QwenLabState.DOWNLOADING, completedBytes + offset, totalBytes(), artifact.fileName))
            var response = source.open(artifact, offset)
            if (offset > 0L && response.statusCode != HttpURLConnection.HTTP_PARTIAL) {
                response.close()
                part.delete()
                offset = 0L
                response = source.open(artifact, 0L)
            }
            response.use { opened ->
                check(opened.statusCode == HttpURLConnection.HTTP_OK ||
                    opened.statusCode == HttpURLConnection.HTTP_PARTIAL) {
                    "Descarga del modelo HTTP ${opened.statusCode}"
                }
                BufferedInputStream(opened.stream).use { input ->
                    BufferedOutputStream(FileOutputStream(part, offset > 0L)).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var current = offset
                        while (true) {
                            if (cancelled.get()) throw CancellationException("Descarga cancelada")
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            current += count
                            downloadedThisRun += count
                            check(current <= artifact.bytes) { "Archivo de modelo más grande de lo esperado" }
                            onProgress(QwenModelProgress(
                                QwenLabState.DOWNLOADING,
                                completedBytes + current,
                                totalBytes(),
                                artifact.fileName,
                            ))
                        }
                    }
                }
            }
            check(part.length() == artifact.bytes) { "Descarga incompleta: ${artifact.fileName}" }
            onProgress(QwenModelProgress(QwenLabState.VERIFYING, completedBytes + part.length(), totalBytes(), artifact.fileName))
            check(sha256(part) == artifact.sha256) {
                part.delete()
                "SHA-256 inválido: ${artifact.fileName}"
            }
            check(part.renameTo(target)) { "No se pudo instalar ${artifact.fileName}" }
            completedBytes += artifact.bytes
        }
        val downloadMs = (System.nanoTime() - downloadStarted) / 1_000_000L
        val verifyStarted = System.nanoTime()
        onProgress(QwenModelProgress(QwenLabState.VERIFYING, totalBytes(), totalBytes()))
        check(artifacts.all { sha256(File(modelDirectory, it.fileName)) == it.sha256 }) {
            "Verificación final SHA-256 falló"
        }
        val verifyMs = (System.nanoTime() - verifyStarted) / 1_000_000L
        marker.writeText(fingerprint())
        onProgress(QwenModelProgress(QwenLabState.INSTALLED, totalBytes(), totalBytes()))
        return QwenInstallReport(downloadMs, verifyMs, downloadedThisRun, storageBytes(), false)
    }

    fun cancel() { cancelled.set(true) }

    @Synchronized
    fun delete() {
        cancel()
        check(!modelDirectory.exists() || modelDirectory.deleteRecursively()) {
            "No se pudo eliminar el modelo Qwen"
        }
    }

    fun storageBytes(): Long = artifacts.sumOf { File(modelDirectory, it.fileName).length() }

    private fun totalBytes(): Long = artifacts.sumOf { it.bytes }
    private fun fingerprint(): String = artifacts.joinToString("|") { "${it.fileName}:${it.sha256}" }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
