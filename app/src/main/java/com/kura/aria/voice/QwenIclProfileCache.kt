package com.kura.aria.voice

import java.io.File
import java.security.MessageDigest

enum class QwenIclCacheStatus {
    HIT,
    LEGACY_HIT,
    MISS,
    CORRUPT,
    INCOMPATIBLE,
}

data class QwenIclPromptSummary(
    val format: String,
    val speakerEmbeddingValues: Int,
    val referenceTokenValues: Int,
    val referenceFrames: Int,
    val referenceCodebooks: Int,
    val referenceCodeValues: Int,
)

data class QwenIclCacheIdentity(
    val profileId: String,
    val profileVersion: Int,
    val masterSha256: String,
    val transcriptSha256: String,
    val runtimeCommit: String,
    val modelRevision: String,
    val modelPackageFingerprint: String,
) {
    val fingerprint: String
        get() = sha256(
            listOf(
                CACHE_SCHEMA.toString(),
                PROMPT_FORMAT,
                profileId,
                profileVersion.toString(),
                masterSha256,
                transcriptSha256,
                runtimeCommit,
                modelRevision,
                modelPackageFingerprint,
            ).joinToString("|"),
        )

    companion object {
        const val CACHE_SCHEMA = 2
        const val PROMPT_FORMAT = "qwen3_tts_icl_prompt_v1"

        fun forProfile(profile: ReplaceableVoiceProfile): QwenIclCacheIdentity = QwenIclCacheIdentity(
            profileId = profile.id,
            profileVersion = profile.version,
            masterSha256 = profile.referenceSha256,
            transcriptSha256 = sha256(profile.referenceTranscript),
            runtimeCommit = Qwen06BAndroidPackage.RUNTIME_COMMIT,
            modelRevision = Qwen06BAndroidPackage.MODEL_REVISION,
            modelPackageFingerprint = Qwen06BAndroidPackage.packageFingerprint,
        )
    }
}

data class QwenIclCacheLookup(
    val status: QwenIclCacheStatus,
    val prompt: File? = null,
    val summary: QwenIclPromptSummary? = null,
    val detail: String,
) {
    val canUse: Boolean
        get() = status == QwenIclCacheStatus.HIT || status == QwenIclCacheStatus.LEGACY_HIT
}

/**
 * Persistent, versioned cache for the native qwen3_tts_icl_prompt_v1 artifact.
 * It never treats the reference WAV itself as a cache. A hit requires the
 * extracted prompt, an exact compatibility identity, length and SHA-256.
 */
class QwenIclProfileCache(
    private val directory: File,
    private val promptFileName: String,
    private val identity: QwenIclCacheIdentity,
) {
    val promptFile: File get() = File(directory, promptFileName)
    private val metadataFile: File get() = File(directory, ".aria-icl-cache-v2")
    private val legacyMarker: File get() = File(directory, ".profile-v1")

    fun lookup(): QwenIclCacheLookup {
        val prompt = promptFile
        if (!prompt.isFile) {
            return QwenIclCacheLookup(QwenIclCacheStatus.MISS, detail = "prompt_missing")
        }
        val summary = runCatching { QwenIclPromptInspector.inspect(prompt) }.getOrElse {
            return QwenIclCacheLookup(QwenIclCacheStatus.CORRUPT, detail = "prompt_invalid")
        }
        if (!metadataFile.isFile) {
            return if (legacyMarker.readTextOrNull()?.trim() == legacyFingerprint()) {
                QwenIclCacheLookup(QwenIclCacheStatus.LEGACY_HIT, prompt, summary, "legacy_0.2.47")
            } else {
                QwenIclCacheLookup(QwenIclCacheStatus.CORRUPT, detail = "metadata_missing")
            }
        }
        val metadata = readMetadata(metadataFile)
            ?: return QwenIclCacheLookup(QwenIclCacheStatus.CORRUPT, detail = "metadata_invalid")
        if (metadata["schema"] != QwenIclCacheIdentity.CACHE_SCHEMA.toString() ||
            metadata["identity"] != identity.fingerprint ||
            metadata["prompt_format"] != QwenIclCacheIdentity.PROMPT_FORMAT) {
            return QwenIclCacheLookup(QwenIclCacheStatus.INCOMPATIBLE, detail = "identity_mismatch")
        }
        val expectedBytes = metadata["prompt_bytes"]?.toLongOrNull()
            ?: return QwenIclCacheLookup(QwenIclCacheStatus.CORRUPT, detail = "size_missing")
        if (prompt.length() != expectedBytes) {
            return QwenIclCacheLookup(QwenIclCacheStatus.CORRUPT, detail = "size_mismatch")
        }
        if (metadata["prompt_sha256"] != sha256(prompt)) {
            return QwenIclCacheLookup(QwenIclCacheStatus.CORRUPT, detail = "sha_mismatch")
        }
        if (metadata["speaker_values"]?.toIntOrNull() != summary.speakerEmbeddingValues ||
            metadata["reference_frames"]?.toIntOrNull() != summary.referenceFrames ||
            metadata["reference_codebooks"]?.toIntOrNull() != summary.referenceCodebooks) {
            return QwenIclCacheLookup(QwenIclCacheStatus.CORRUPT, detail = "shape_mismatch")
        }
        return QwenIclCacheLookup(QwenIclCacheStatus.HIT, prompt, summary, "validated_v2")
    }

    fun install(extractedPrompt: File): QwenIclCacheLookup {
        directory.mkdirs()
        val summary = QwenIclPromptInspector.inspect(extractedPrompt)
        val target = promptFile
        if (extractedPrompt.canonicalFile != target.canonicalFile) {
            target.delete()
            check(extractedPrompt.renameTo(target)) { "No se pudo instalar el perfil ICL" }
        }
        writeMetadata(target, summary)
        legacyMarker.delete()
        return lookup().also { check(it.status == QwenIclCacheStatus.HIT) }
    }

    fun promoteLegacy(prompt: File, summary: QwenIclPromptSummary) {
        check(prompt.canonicalFile == promptFile.canonicalFile)
        writeMetadata(prompt, summary)
        legacyMarker.delete()
    }

    fun invalidate() {
        promptFile.delete()
        metadataFile.delete()
        legacyMarker.delete()
    }

    private fun writeMetadata(prompt: File, summary: QwenIclPromptSummary) {
        val temporary = File(directory, ".aria-icl-cache-v2.tmp")
        temporary.writeText(
            buildString {
                append("schema=").append(QwenIclCacheIdentity.CACHE_SCHEMA).append('\n')
                append("identity=").append(identity.fingerprint).append('\n')
                append("prompt_format=").append(summary.format).append('\n')
                append("prompt_bytes=").append(prompt.length()).append('\n')
                append("prompt_sha256=").append(sha256(prompt)).append('\n')
                append("speaker_values=").append(summary.speakerEmbeddingValues).append('\n')
                append("reference_tokens=").append(summary.referenceTokenValues).append('\n')
                append("reference_frames=").append(summary.referenceFrames).append('\n')
                append("reference_codebooks=").append(summary.referenceCodebooks).append('\n')
                append("reference_codes=").append(summary.referenceCodeValues).append('\n')
            },
        )
        metadataFile.delete()
        check(temporary.renameTo(metadataFile)) { "No se pudo guardar metadata del perfil ICL" }
    }

    private fun legacyFingerprint(): String =
        "${identity.masterSha256}|${identity.transcriptSha256}|${identity.modelRevision}"

    private fun readMetadata(file: File): Map<String, String>? = runCatching {
        file.useLines { lines ->
            lines.filter { it.isNotBlank() }.associate { line ->
                val separator = line.indexOf('=')
                require(separator > 0)
                line.substring(0, separator) to line.substring(separator + 1)
            }
        }
    }.getOrNull()

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()
}

object QwenIclPromptInspector {
    private const val MAX_PROMPT_BYTES = 16L * 1024L * 1024L

    fun inspect(file: File): QwenIclPromptSummary {
        require(file.isFile && file.length() in 1..MAX_PROMPT_BYTES) { "Perfil ICL vacío o demasiado grande" }
        val json = file.readText()
        val format = stringField(json, "format")
        require(format == QwenIclCacheIdentity.PROMPT_FORMAT) { "Formato ICL incompatible" }
        val speakerValues = arrayCount(json, "speaker_embedding")
        val referenceTokens = arrayCount(json, "reference_token_ids")
        val referenceText = stringField(json, "reference_text")
        val frames = intField(json, "frames")
        val codebooks = intField(json, "codebooks")
        val codes = arrayCount(json, "codes")
        require(speakerValues > 0) { "Perfil sin speaker embedding" }
        require(referenceText.isNotEmpty() || referenceTokens > 0) { "Perfil sin transcripción" }
        require(frames > 0 && codebooks > 0 && codes == frames * codebooks) { "Códigos ICL inválidos" }
        return QwenIclPromptSummary(format, speakerValues, referenceTokens, frames, codebooks, codes)
    }

    private fun stringField(json: String, name: String): String {
        val key = json.indexOf("\"$name\"")
        require(key >= 0) { "Campo $name ausente" }
        val colon = json.indexOf(':', key)
        val start = json.indexOf('"', colon + 1)
        require(colon >= 0 && start >= 0) { "Campo $name inválido" }
        var cursor = start + 1
        var escaped = false
        while (cursor < json.length) {
            val char = json[cursor]
            if (char == '"' && !escaped) return json.substring(start + 1, cursor)
            escaped = char == '\\' && !escaped
            if (char != '\\') escaped = false
            cursor += 1
        }
        error("Campo $name sin cierre")
    }

    private fun intField(json: String, name: String): Int {
        val key = json.indexOf("\"$name\"")
        require(key >= 0) { "Campo $name ausente" }
        val colon = json.indexOf(':', key)
        require(colon >= 0)
        var start = colon + 1
        while (start < json.length && json[start].isWhitespace()) start += 1
        var end = start
        while (end < json.length && (json[end].isDigit() || json[end] == '-')) end += 1
        return json.substring(start, end).toInt()
    }

    private fun arrayCount(json: String, name: String): Int {
        val key = json.indexOf("\"$name\"")
        require(key >= 0) { "Campo $name ausente" }
        val start = json.indexOf('[', key)
        val end = json.indexOf(']', start + 1)
        require(start >= 0 && end > start) { "Array $name inválido" }
        var values = 0
        var hasValue = false
        for (index in start + 1 until end) {
            when {
                json[index] == ',' && hasValue -> {
                    values += 1
                    hasValue = false
                }
                !json[index].isWhitespace() -> hasValue = true
            }
        }
        if (hasValue) values += 1
        return values
    }
}

internal fun sha256(file: File): String = file.inputStream().buffered().use { sha256(it.readBytes()) }

internal fun sha256(text: String): String = sha256(text.toByteArray())

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
