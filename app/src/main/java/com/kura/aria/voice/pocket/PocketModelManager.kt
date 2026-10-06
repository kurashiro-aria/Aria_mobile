package com.kura.aria.voice.pocket

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

internal class PocketModelManager(private val context: Context) {
    private val pocketRoot = File(context.filesDir, "voice/pocket").apply { mkdirs() }
    private val packRoot = File(pocketRoot, "models/${PocketModelSpec.ID}")
    private val cacheRoot = File(pocketRoot, "cache").apply { mkdirs() }
    @Volatile var status: PocketInstallStatus = if (installedPack() != null)
        PocketInstallStatus(PocketInstallPhase.INSTALLED) else PocketInstallStatus(PocketInstallPhase.NOT_INSTALLED)
        private set

    fun logicalStoragePath(): String = "files/voice/pocket"

    fun installedBytes(): Long = if (!packRoot.exists()) 0L else packRoot.walkTopDown()
        .filter(File::isFile).sumOf(File::length)

    fun installedPack(): PocketPack? = runCatching {
        if (!File(packRoot, ".aria-package.sha256").readText().trim()
                .equals(PocketModelSpec.ARCHIVE_SHA256, true)) return@runCatching null
        readPack(packRoot).takeIf(PocketInstallValidator::hasRequiredFiles)
    }.getOrNull()

    fun voices(): List<PocketVoice> = installedPack()?.voices.orEmpty()

    suspend fun install(onStatus: (PocketInstallStatus) -> Unit): PocketPack = withContext(Dispatchers.IO) {
        installedPack()?.let {
            update(PocketInstallStatus(PocketInstallPhase.INSTALLED, 100), onStatus)
            return@withContext it
        }
        val partial = File(cacheRoot, "${PocketModelSpec.ID}.zip.partial")
        val staging = File(pocketRoot, ".install-${UUID.randomUUID()}")
        try {
            update(PocketInstallStatus(PocketInstallPhase.DOWNLOADING, 0), onStatus)
            download(partial) { percent ->
                update(PocketInstallStatus(PocketInstallPhase.DOWNLOADING, percent), onStatus)
            }
            coroutineContext.ensureActive()
            update(PocketInstallStatus(PocketInstallPhase.VERIFYING, 100), onStatus)
            val actual = sha256(partial)
            if (!actual.equals(PocketModelSpec.ARCHIVE_SHA256, true))
                throw PocketVoiceException(PocketVoiceError.MODELO_CORRUPTO)
            staging.mkdirs()
            extract(partial, staging)
            val extracted = normalizeExtractedRoot(staging)
            val pack = readPack(extracted)
            if (!PocketInstallValidator.hasRequiredFiles(pack))
                throw PocketVoiceException(PocketVoiceError.MODELO_CORRUPTO)
            File(extracted, ".aria-package.sha256").writeText(PocketModelSpec.ARCHIVE_SHA256)
            packRoot.parentFile?.mkdirs()
            val old = File(packRoot.parentFile, ".old-${System.currentTimeMillis()}")
            if (packRoot.exists() && !packRoot.renameTo(old))
                throw PocketVoiceException(PocketVoiceError.MODELO_CORRUPTO)
            if (!extracted.renameTo(packRoot)) {
                old.renameTo(packRoot)
                throw PocketVoiceException(PocketVoiceError.MODELO_CORRUPTO)
            }
            old.deleteRecursively()
            staging.deleteRecursively()
            partial.delete()
            val installed = installedPack() ?: throw PocketVoiceException(PocketVoiceError.MODELO_CORRUPTO)
            update(PocketInstallStatus(PocketInstallPhase.INSTALLED, 100), onStatus)
            installed
        } catch (cancelled: CancellationException) {
            partial.delete()
            staging.deleteRecursively()
            update(PocketInstallStatus(PocketInstallPhase.NOT_INSTALLED), onStatus)
            throw cancelled
        } catch (error: Throwable) {
            partial.delete()
            staging.deleteRecursively()
            val code = (error as? PocketVoiceException)?.code ?: PocketVoiceError.DESCARGA_FALLIDA
            update(PocketInstallStatus(PocketInstallPhase.ERROR, detail = code.name), onStatus)
            throw if (error is PocketVoiceException) error else PocketVoiceException(code, error)
        }
    }

    fun deleteInstalled() {
        val target = packRoot.canonicalFile
        val allowed = File(pocketRoot, "models").canonicalFile
        require(target.parentFile == allowed)
        if (target.exists() && !target.deleteRecursively())
            throw IllegalStateException("No pude eliminar Pocket TTS")
        status = PocketInstallStatus(PocketInstallPhase.NOT_INSTALLED)
    }

    @Synchronized
    fun importVoice(uri: Uri): PocketVoice {
        val pack = installedPack() ?: throw PocketVoiceException(PocketVoiceError.MODELO_NO_INSTALADO)
        val original = displayName(uri).substringBeforeLast('.').ifBlank { "Voz personalizada" }
        val base = original.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
            .ifBlank { "voz" }
        var id = "custom-$base"
        var suffix = 2
        while (pack.voices.any { it.id == id }) id = "custom-$base-${suffix++}"
        val destination = File(pack.voicesDir, "$id.wav")
        destination.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= 64L * 1024 * 1024) { "La referencia supera 64 MiB" }
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw IllegalArgumentException("No pude abrir el WAV")
        if (!isWave(destination)) {
            destination.delete()
            throw IllegalArgumentException("La referencia no es un WAV válido")
        }
        val voice = PocketVoice(id, original, destination.name, custom = true)
        writeCustomMetadata(pack.root, readCustomMetadata(pack.root) + voice,
            newVoice = voice, referenceSha256 = sha256(destination))
        return voice
    }

    /** Imports a validated C++ EMB1 profile; the original WAV is not required at synthesis time. */
    @Synchronized
    fun importPocketVoiceProfile(uri: Uri, profile: PocketVoiceProfile): PocketVoice {
        val pack = installedPack() ?: throw PocketVoiceException(PocketVoiceError.MODELO_NO_INSTALADO)
        require(PocketVoiceProfileValidator.validateMetadata(profile)) { "Perfil Pocket incompatible" }
        require(pack.voices.none { it.id == profile.id }) { "Ya existe una voz con ese id" }
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("No pude abrir el perfil Pocket")
        val store = PocketVoiceProfileStore(pack.voicesDir)
        store.import(profile, input)
        val voice = PocketVoice(profile.id, profile.name, profile.voiceFileName, custom = true, profile = profile)
        try {
            writeCustomMetadata(pack.root, readCustomMetadata(pack.root) + voice)
            // A .kv belongs to a particular conditioning state. Never let a stale
            // snapshot from an earlier profile with the same id override this EMB1.
            File(pack.voicesDir, ".cache/${profile.id}.kv").delete()
            return voice
        } catch (error: Throwable) {
            File(pack.voicesDir, ".cache/${profile.id}.emb").delete()
            File(pack.voicesDir, ".cache/${profile.id}.kv").delete()
            throw error
        }
    }

    private suspend fun download(destination: File, progress: (Int) -> Unit) {
        destination.parentFile?.mkdirs()
        val connection = (URL(PocketModelSpec.DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ARIA-Mobile/${com.kura.aria.BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode !in 200..299)
                throw IllegalStateException("HTTP ${connection.responseCode}")
            val expected = connection.contentLengthLong.takeIf { it > 0 } ?: PocketModelSpec.DOWNLOAD_BYTES
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var received = 0L
                    var reported = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        val value = ((received * 100L) / expected).toInt().coerceIn(0, 100)
                        if (value != reported) { reported = value; progress(value) }
                    }
                }
            }
        } finally { connection.disconnect() }
    }

    private suspend fun extract(archive: File, staging: File) {
        var entries = 0
        var total = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = zip.nextEntry ?: break
                require(++entries <= 64) { "Demasiados archivos en el paquete" }
                val target = PocketInstallValidator.safeArchivePath(staging, entry.name)
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var entryBytes = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = zip.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            total += read
                            require(entryBytes <= 2L * 1024 * 1024 * 1024 && total <= 3L * 1024 * 1024 * 1024) {
                                "Paquete demasiado grande"
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun normalizeExtractedRoot(staging: File): File {
        if (File(staging, "manifest.json").isFile) return staging
        val children = staging.listFiles().orEmpty().filter { it.isDirectory }
        return children.singleOrNull { File(it, "manifest.json").isFile }
            ?: throw PocketVoiceException(PocketVoiceError.MODELO_CORRUPTO)
    }

    private fun readPack(root: File): PocketPack {
        val json = JSONObject(File(root, "manifest.json").readText())
        require(json.optInt("format", 0) == PocketModelSpec.FORMAT_VERSION)
        val voicesJson = json.optJSONArray("voices") ?: JSONArray()
        val bundled = (0 until voicesJson.length()).map { index ->
            val item = voicesJson.getJSONObject(index)
            PocketVoice(item.getString("id"), item.getString("name"), item.getString("file"))
        }
        val custom = readCustomMetadata(root).filter { File(root, "voices/${it.fileName}").isFile }
        return PocketPack(
            id = json.getString("id"),
            name = json.getString("name"),
            languageTag = json.getString("languageTag"),
            precision = json.optString("precision", "fp32"),
            temperature = json.optDouble("temperature", 0.7).toFloat(),
            lsdSteps = json.optInt("lsdSteps", 1),
            threads = json.optInt("threads", 4),
            root = root,
            voices = (bundled + custom).distinctBy { it.id }
        )
    }

    private fun readCustomMetadata(root: File): List<PocketVoice> {
        val values = runCatching { JSONArray(File(root, "custom-voices.json").readText()) }
            .getOrNull() ?: return emptyList()
        return (0 until values.length()).mapNotNull { index -> runCatching {
            val item = values.getJSONObject(index)
            val id = item.getString("id")
            val name = item.getString("name")
            val fileName = item.getString("file")
            require(fileName == File(fileName).name && !fileName.contains('\\'))
            val profileJson = item.optJSONObject("pocketProfile")
            if (profileJson != null) {
                val profile = PocketVoiceProfile(
                    id = profileJson.getString("id"),
                    name = profileJson.getString("name"),
                    formatId = profileJson.getString("formatId"),
                    runtimeId = profileJson.getString("runtimeId"),
                    modelId = profileJson.getString("modelId"),
                    embeddingFileName = profileJson.getString("embeddingFile"),
                    sampleRate = profileJson.getInt("sampleRate")
                )
                require(id == profile.id && name == profile.name && fileName == profile.voiceFileName)
                require(PocketVoiceProfileValidator.isInstalledProfileValid(profile, File(root, "voices")))
                PocketVoice(id, name, fileName, true, profile)
            } else {
                require(File(root, "voices/$fileName").isFile)
                PocketVoice(id, name, fileName, true)
            }
        }.getOrNull() }.distinctBy { it.id }
    }

    private fun writeCustomMetadata(
        root: File,
        voices: List<PocketVoice>,
        newVoice: PocketVoice? = null,
        referenceSha256: String? = null
    ) {
        val previous = runCatching { JSONArray(File(root, "custom-voices.json").readText()) }.getOrNull()
        val json = JSONArray()
        voices.filter { it.custom }.forEach {
            val old = (0 until (previous?.length() ?: 0)).mapNotNull { index ->
                previous?.optJSONObject(index)
            }.firstOrNull { item -> item.optString("id") == it.id }
            val entry = JSONObject().put("id", it.id).put("name", it.name).put("file", it.fileName)
                .put("profileVersion", 1)
                .put("model", PocketModelSpec.ID)
                .put("sampleRate", PocketModelSpec.SAMPLE_RATE)
                .put("referenceSha256", if (it == newVoice) referenceSha256 else old?.optString("referenceSha256"))
                .put("createdAt", if (it == newVoice) System.currentTimeMillis() else old?.optLong("createdAt"))
                .put("state", "READY")
            it.profile?.let { profile ->
                entry.put("pocketProfile", JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("formatId", profile.formatId)
                    .put("runtimeId", profile.runtimeId)
                    .put("modelId", profile.modelId)
                    .put("embeddingFile", profile.embeddingFileName)
                    .put("sampleRate", profile.sampleRate))
            }
            json.put(entry)
        }
        val target = File(root, "custom-voices.json")
        val staged = File(root, ".custom-voices-${UUID.randomUUID()}.json.tmp")
        try {
            FileOutputStream(staged).use { output ->
                output.write(json.toString(2).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { staged.delete() }
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "Voz personalizada.wav"
        }
        return uri.lastPathSegment ?: "Voz personalizada.wav"
    }

    private fun isWave(file: File): Boolean {
        if (file.length() < 44L) return false
        RandomAccessFile(file, "r").use { input ->
            val riff = ByteArray(4); input.readFully(riff)
            input.seek(8); val wave = ByteArray(4); input.readFully(wave)
            return riff.contentEquals("RIFF".toByteArray()) && wave.contentEquals("WAVE".toByteArray())
        }
    }

    private fun update(value: PocketInstallStatus, callback: (PocketInstallStatus) -> Unit) {
        status = value
        callback(value)
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
