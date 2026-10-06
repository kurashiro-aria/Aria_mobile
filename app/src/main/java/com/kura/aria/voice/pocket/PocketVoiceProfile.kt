package com.kura.aria.voice.pocket

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

internal object PocketVoiceProfileFormat {
    const val ID = "pocket-cpp-emb1"
    const val MAGIC = 0x31424D45 // "EMB1" as little-endian uint32
    const val MAX_BYTES = 1 shl 20
    private const val EXPECTED_CHANNELS = 8
    private const val MAX_FRAMES = 4096

    /** The pinned C++ cache is [magic:u32][rank:i32][shape:i64*rank][float32*elements]. */
    fun validateEmbedding(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() !in 36L..MAX_BYTES.toLong()) return false
        val bytes = file.readBytes()
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (input.int != MAGIC) return false
        val rank = input.int
        if (rank != 3) return false
        val shape = LongArray(rank) { input.long }
        if (shape[0] != 1L || shape[1] != EXPECTED_CHANNELS.toLong() ||
            shape[2] !in 1L..MAX_FRAMES.toLong()) return false
        val elements = shape.fold(1L) { acc, dim -> Math.multiplyExact(acc, dim) }
        val expectedBytes = 8L + rank * 8L + elements * 4L
        if (expectedBytes != bytes.size.toLong()) return false
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

    private companion object {
        val PUBLISH_LOCK = Any()
    }
}
