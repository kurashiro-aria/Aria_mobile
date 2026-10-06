package com.kura.aria.voice.pocket

import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Metadata paired with PocketTTS.cpp's native Mimi embedding cache (.emb). */
internal data class PocketVoiceProfile(
    val id: String,
    val name: String,
    val formatId: String,
    val runtimeId: String,
    val modelId: String,
    val embeddingFileName: String,
    val sampleRate: Int = PocketModelSpec.SAMPLE_RATE,
    val state: PocketVoiceProfileState = PocketVoiceProfileState.VALID
) {
    val voiceFileName: String get() = "$id.wav"
    val requiredFiles: List<String> get() = listOf(embeddingFileName)
}

internal enum class PocketVoiceProfileState { VALID, INVALID }

internal object PocketBuiltinVoiceProfile {
    const val ID = "aria-d-b2"
    const val NAME = "ARIA-D-B2"
    const val EMBEDDING_SHA256 = "c3e2f606431b3655852a3b3256896adf1ff6b8fe1e1e54c04914ad6d8c34b599"
    const val EMBEDDING_BYTES = 1_540_128L
    val profile = PocketVoiceProfile(
        id = ID,
        name = NAME,
        formatId = PocketVoiceProfileFormat.ID,
        runtimeId = PocketModelSpec.PROFILE_RUNTIME_ID,
        modelId = PocketModelSpec.ID,
        embeddingFileName = "$ID.emb"
    )
}

internal object PocketVoiceProfileFormat {
    const val ID = "pocket-cpp-emb1"
    const val MAGIC = 0x31424D45 // "EMB1" as little-endian uint32
    private const val RANK = 3
    private const val BATCH = 1L
    private const val FEATURE_SIZE = 1024L
    // Pocket's pinned Mimi encoder accepts at most 30 s at 24 kHz and emits
    // at most 376 conditioning frames for that input. Axis 1 is dynamic;
    // the final axis is the encoder's fixed 1024-wide conditioning feature.
    private const val MAX_FRAMES = 376L
    private const val HEADER_BYTES = 8L + RANK * 8L
    const val MAX_BYTES = 8 + 3 * 8 + 376 * 1024 * 4

    /** The pinned C++ cache is [magic:u32][rank:i32][shape:i64*rank][float32*elements]. */
    fun validateEmbedding(file: File): Boolean = runCatching {
        val length = file.length()
        if (!file.isFile || length !in (HEADER_BYTES + FEATURE_SIZE * Float.SIZE_BYTES)..MAX_BYTES.toLong()) return false
        val bytes = file.inputStream().use { input ->
            val output = ByteArrayOutputStream(length.toInt())
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > MAX_BYTES.toLong()) return false
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (file.length() != length || bytes.size.toLong() != length) return false
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (input.remaining() < HEADER_BYTES.toInt()) return false
        if (input.int != MAGIC) return false
        val rank = input.int
        if (rank != RANK) return false
        val shape = LongArray(rank) { input.long }
        if (shape[0] != BATCH || shape[1] !in 1L..MAX_FRAMES || shape[2] != FEATURE_SIZE) return false
        val elements = Math.multiplyExact(Math.multiplyExact(shape[0], shape[1]), shape[2])
        val payloadBytes = Math.multiplyExact(elements, Float.SIZE_BYTES.toLong())
        val expectedBytes = Math.addExact(HEADER_BYTES, payloadBytes)
        if (expectedBytes != length || expectedBytes != bytes.size.toLong()) return false
        while (input.hasRemaining()) if (!input.float.isFinite()) return false
        true
    }.getOrDefault(false)
}

internal object PocketVoiceProfileValidator {
    private val safeId = Regex("^[a-z0-9][a-z0-9_-]{0,47}$")

    fun validateMetadata(profile: PocketVoiceProfile): Boolean =
        safeId.matches(profile.id) && profile.name.isNotBlank() && profile.name.length <= 80 &&
            profile.formatId == PocketVoiceProfileFormat.ID &&
            profile.runtimeId == PocketModelSpec.PROFILE_RUNTIME_ID &&
            profile.modelId == PocketModelSpec.ID &&
            profile.sampleRate == PocketModelSpec.SAMPLE_RATE &&
            profile.embeddingFileName == "${profile.id}.emb" &&
            profile.state == PocketVoiceProfileState.VALID

    fun embeddingFile(profile: PocketVoiceProfile, voicesDir: File): File? {
        if (!validateMetadata(profile)) return null
        val root = File(voicesDir, ".cache").canonicalFile
        val target = File(root, profile.embeddingFileName).canonicalFile
        return target.takeIf { it.parentFile == root }
    }

    fun isInstalledProfileValid(profile: PocketVoiceProfile, voicesDir: File): Boolean {
        val embedding = embeddingFile(profile, voicesDir) ?: return false
        return PocketVoiceProfileFormat.validateEmbedding(embedding)
    }
}

/** Stages and validates one .emb, then publishes it atomically in Pocket's cache directory. */
internal class PocketVoiceProfileStore(private val voicesDir: File) {
    enum class BundledInstall { INSTALLED, REUSED, REPAIRED }

    fun import(profile: PocketVoiceProfile, source: InputStream): PocketVoiceProfile {
        require(PocketVoiceProfileValidator.validateMetadata(profile)) { "Perfil Pocket incompatible" }
        val destination = PocketVoiceProfileValidator.embeddingFile(profile, voicesDir)
            ?: throw IllegalArgumentException("Ruta del perfil inválida")
        val cacheDir = destination.parentFile ?: throw IllegalArgumentException("Directorio de caché inválido")
        require(cacheDir.mkdirs() || cacheDir.isDirectory)
        val canonicalRoot = File(voicesDir, ".cache").canonicalFile
        require(cacheDir.canonicalFile == canonicalRoot && destination.parentFile?.canonicalFile == canonicalRoot) {
            "Ruta del perfil fuera de la caché Pocket"
        }
        val staging = File(cacheDir, ".${profile.id}-${UUID.randomUUID()}.emb.part")
        try {
            source.use { input ->
                staging.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= PocketVoiceProfileFormat.MAX_BYTES) { "Perfil Pocket demasiado grande" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(PocketVoiceProfileFormat.validateEmbedding(staging)) { "Archivo .emb inválido" }
            synchronized(PUBLISH_LOCK) {
                require(!destination.exists()) { "Ya existe un perfil Pocket con ese id" }
                try {
                    Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    if (!staging.renameTo(destination)) throw IllegalStateException("No pude publicar el perfil Pocket")
                }
            }
            return profile
        } catch (error: Throwable) {
            staging.delete()
            throw error
        }
    }

    /** Installs the packaged canonical profile, repairing only a missing or invalid copy. */
    fun installBundled(profile: PocketVoiceProfile, source: InputStream, expectedSha256: String): BundledInstall {
        require(PocketVoiceProfileValidator.validateMetadata(profile)) { "Perfil Pocket incompatible" }
        val destination = PocketVoiceProfileValidator.embeddingFile(profile, voicesDir)
            ?: throw IllegalArgumentException("Ruta del perfil inválida")
        val cacheDir = destination.parentFile ?: throw IllegalArgumentException("Directorio de caché inválido")
        require(cacheDir.mkdirs() || cacheDir.isDirectory)
        val canonicalRoot = File(voicesDir, ".cache").canonicalFile
        require(cacheDir.canonicalFile == canonicalRoot && destination.parentFile?.canonicalFile == canonicalRoot) {
            "Ruta del perfil fuera de la caché Pocket"
        }
        synchronized(PUBLISH_LOCK) {
            val hadDestination = destination.exists()
            if (hadDestination && PocketVoiceProfileFormat.validateEmbedding(destination) &&
                sha256(destination).equals(expectedSha256, ignoreCase = true)) {
                source.close()
                return BundledInstall.REUSED
            }
            val staging = File(cacheDir, ".${profile.id}-${UUID.randomUUID()}.emb.part")
            try {
                source.use { input ->
                    staging.outputStream().use { output ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= PocketVoiceProfileFormat.MAX_BYTES) { "Perfil Pocket demasiado grande" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                        require(total == PocketBuiltinVoiceProfile.EMBEDDING_BYTES &&
                            digest.digest().joinToString("") { "%02x".format(it) }.equals(expectedSha256, true)) {
                            "El perfil Pocket empaquetado no coincide con su SHA-256 esperado"
                        }
                    }
                }
                require(PocketVoiceProfileFormat.validateEmbedding(staging)) { "Archivo .emb inválido" }
                try {
                    val options = if (hadDestination) arrayOf(
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                    ) else arrayOf(StandardCopyOption.ATOMIC_MOVE)
                    Files.move(staging.toPath(), destination.toPath(), *options)
                } catch (_: AtomicMoveNotSupportedException) {
                    if (hadDestination) Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    else if (!staging.renameTo(destination)) throw IllegalStateException("No pude publicar el perfil Pocket")
                }
                return if (hadDestination) BundledInstall.REPAIRED else BundledInstall.INSTALLED
            } catch (error: Throwable) {
                staging.delete()
                throw error
            }
        }
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val PUBLISH_LOCK = Any()
    }
}
