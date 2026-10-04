package com.kura.aria.voice

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Immutable metadata for the official sherpa-onnx Piper release asset. */
object PiperModelPackage {
    const val ARCHIVE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-es_MX-claude-high.tar.bz2"
    const val ARCHIVE_SHA256 = "ec33fb689c248fe64810aab564cba97babf0f506672cfd404928d46e751a4721"
    const val ARCHIVE_BYTES = 67_207_890L
    const val MODEL_SHA256 = "6b7a54f5fcc8c9ce3788cd308a26cfa429ad025cdff4a7a6c34d025d0d229341"
    const val MODEL_BYTES = 62_949_322L
    const val ROOT_DIR = "vits-piper-es_MX-claude-high"
}

enum class PiperModelState { NOT_INSTALLED, DOWNLOADING, LOADING, READY, ERROR }

/**
 * Owns the app-private model installation.  The archive is pinned to the
 * official sherpa-onnx release and is never treated as executable code.
 */
class PiperModelStore(private val rootDirectory: File) {
    private val operation = Mutex()
    private val cancelled = AtomicBoolean(false)

    val modelDirectory: File get() = File(rootDirectory, PiperSpanishPrototype.MODEL_ID)

    fun isInstalled(): Boolean {
        val model = File(modelDirectory, PiperSpanishPrototype.MODEL_FILE)
        val config = File(modelDirectory, PiperSpanishPrototype.CONFIG_FILE)
        val tokens = File(modelDirectory, PiperSpanishPrototype.TOKENS_FILE)
        val data = File(modelDirectory, PiperSpanishPrototype.DATA_DIR)
        val marker = File(modelDirectory, ".aria-model.sha256")
        return model.isFile && model.length() == PiperModelPackage.MODEL_BYTES && config.isFile &&
            tokens.isFile && data.isDirectory && marker.isFile &&
            marker.readText().trim() == PiperModelPackage.MODEL_SHA256
    }

    suspend fun install(onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }): File =
        operation.withLock {
            cancelled.set(false)
            if (isInstalled()) return@withLock modelDirectory
            withContext(Dispatchers.IO) {
                val parent = rootDirectory.apply { mkdirs() }
                val archive = File(parent, ".${PiperSpanishPrototype.MODEL_ID}.download")
                val staging = File(parent, ".${PiperSpanishPrototype.MODEL_ID}.staging-${System.nanoTime()}")
                try {
                    download(archive, onProgress)
                    extractAndValidate(archive, staging)
                    val installed = File(parent, PiperSpanishPrototype.MODEL_ID)
                    if (installed.exists() && !installed.deleteRecursively()) {
                        error("No se pudo reemplazar la instalación anterior")
                    }
                    check(File(staging, PiperModelPackage.ROOT_DIR).renameTo(installed)) {
                        "No se pudo instalar el modelo de forma atómica"
                    }
                    installed
                } catch (cancel: CancellationException) {
                    throw cancel
                } finally {
                    archive.delete()
                    staging.deleteRecursively()
                }
            }
        }

    fun cancel() { cancelled.set(true) }

    private fun download(destination: File, onProgress: (Long, Long) -> Unit) {
        val connection = (URL(PiperModelPackage.ARCHIVE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Descarga del modelo HTTP ${connection.responseCode}"
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: PiperModelPackage.ARCHIVE_BYTES
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(destination)).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        if (cancelled.get()) throw CancellationException("Descarga cancelada")
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                }
            }
            check(digest.digest().toHex() == PiperModelPackage.ARCHIVE_SHA256) {
                "Hash del archivo de modelo no coincide"
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractAndValidate(archive: File, staging: File) {
        staging.mkdirs()
        val stagingPath = staging.canonicalPath + File.separator
        var extractedBytes = 0L
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { input ->
            while (true) {
                val entry = input.nextTarEntry ?: break
                if (entry.isSymbolicLink || entry.isLink) error("El archivo contiene un enlace no permitido")
                val target = File(staging, entry.name).canonicalFile
                check(target.path.startsWith(stagingPath)) { "Ruta de extracción no válida" }
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                extractedBytes += entry.size.coerceAtLeast(0L)
                check(extractedBytes <= MAX_EXTRACTED_BYTES) { "Archivo de modelo demasiado grande" }
                target.parentFile?.mkdirs()
                BufferedOutputStream(FileOutputStream(target)).use { output -> input.copyTo(output) }
            }
        }
        val modelRoot = File(staging, PiperModelPackage.ROOT_DIR)
        val model = File(modelRoot, PiperSpanishPrototype.MODEL_FILE)
        check(model.isFile && model.length() == PiperModelPackage.MODEL_BYTES) { "Modelo ONNX incompleto" }
        check(sha256(model) == PiperModelPackage.MODEL_SHA256) { "Hash del modelo ONNX no coincide" }
        check(File(modelRoot, PiperSpanishPrototype.CONFIG_FILE).isFile) { "Falta la configuración Piper" }
        check(File(modelRoot, PiperSpanishPrototype.TOKENS_FILE).isFile) { "Falta tokens.txt" }
        check(File(modelRoot, PiperSpanishPrototype.DATA_DIR).isDirectory) { "Falta espeak-ng-data" }
        File(modelRoot, ".aria-model.sha256").writeText(PiperModelPackage.MODEL_SHA256)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object { const val MAX_EXTRACTED_BYTES = 110L * 1024L * 1024L }
}
