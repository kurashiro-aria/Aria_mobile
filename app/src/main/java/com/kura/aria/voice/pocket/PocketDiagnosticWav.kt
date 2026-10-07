package com.kura.aria.voice.pocket

import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Final locations and staging files for one Pocket diagnostic inference. */
internal data class PocketDiagnosticTargets(
    val float32File: File,
    val pcm16File: File
) {
    fun files(): List<File> = listOf(float32File, pcm16File)
}

internal interface PocketWavCapture {
    val targets: PocketDiagnosticTargets
    val stagedTargets: PocketDiagnosticTargets
    fun publish(): Boolean
    fun abort()
}

/** Resolves, stages, and shares diagnostic WAVs without changing their bytes. */
internal object PocketDiagnosticWav {
    const val PCM_FILE_NAME = "pocket_audio_quality.wav"
    const val FLOAT_FILE_NAME = "pocket_audio_pre_pcm_f32.wav"
    const val SHARE_ACTION = "android.intent.action.SEND_MULTIPLE"
    const val SHARE_MIME_TYPE = "audio/wav"
    private const val DIRECTORY = "pocket-diagnostics"
    private const val READY_FILE_NAME = ".pocket-diagnostics.ready"
    private const val WAV_HEADER_BYTES = 44L

    fun targets(cacheDir: File): PocketDiagnosticTargets {
        val dir = File(cacheDir, DIRECTORY)
        return PocketDiagnosticTargets(File(dir, FLOAT_FILE_NAME), File(dir, PCM_FILE_NAME))
    }

    /** Both normal chat and the explicit voice test capture the same pair contract. */
    fun forNormalChat(cacheDir: File): PocketDiagnosticTargets = targets(cacheDir)
    fun forVoiceTest(cacheDir: File): PocketDiagnosticTargets = targets(cacheDir)

    fun files(cacheDir: File): List<File> = targets(cacheDir).files()

    fun isShareable(file: File): Boolean = file.isFile && file.length() > WAV_HEADER_BYTES

    fun areShareable(files: List<File>): Boolean {
        if (files.size != 2) return false
        val floatFile = files[0]
        val pcmFile = files[1]
        if (floatFile.name != FLOAT_FILE_NAME || pcmFile.name != PCM_FILE_NAME) return false
        val directory = floatFile.parentFile ?: return false
        if (directory.canonicalFile != pcmFile.parentFile?.canonicalFile) return false
        if (!isShareable(floatFile) || !isShareable(pcmFile)) return false
        val marker = File(directory, READY_FILE_NAME)
        if (!marker.isFile) return false
        val fields = runCatching { marker.readText(Charsets.US_ASCII).trim().split('\n') }.getOrNull()
            ?: return false
        return fields.size == 4 &&
            fields[0] == "v1" &&
            fields[1].isNotBlank() &&
            fields[2].toLongOrNull() == floatFile.length() &&
            fields[3].toLongOrNull() == pcmFile.length()
    }

    fun missingMessage(cacheDir: File): String? {
        val pair = targets(cacheDir)
        val floatReady = isShareable(pair.float32File)
        val pcmReady = isShareable(pair.pcm16File)
        if (!floatReady && !pcmReady) return "Faltan ambos WAV diagnósticos."
        if (!floatReady) return "Falta WAV FLOAT32 diagnóstico."
        if (!pcmReady) return "Falta WAV PCM16 diagnóstico."
        if (!areShareable(pair.files())) return "El par de WAV diagnósticos no está completo."
        return null
    }

    /** Creates exactly two distinct content URIs in FLOAT32, then PCM16 order. */
    fun <T : Any> shareUris(files: List<File>, uriForFile: (File) -> T): List<T> {
        require(files.size == 2 && files[0].name == FLOAT_FILE_NAME && files[1].name == PCM_FILE_NAME)
        require(areShareable(files)) { "Pocket diagnostic pair is not committed" }
        val uris = files.map(uriForFile)
        require(uris.size == 2 && uris.distinct().size == 2) { "Pocket diagnostic URIs must be distinct" }
        return uris
    }

    fun beginCapture(targets: PocketDiagnosticTargets): PocketDiagnosticCapture =
        PocketDiagnosticCapture.begin(targets)

    internal fun readyMarker(cacheDir: File): File = File(File(cacheDir, DIRECTORY), READY_FILE_NAME)
}

/**
 * A pair is visible to the share flow only after both staged WAVs are renamed and
 * the commit marker is written last. Cancellation/failure invalidates the whole pair.
 */
internal class PocketDiagnosticCapture private constructor(
    override val targets: PocketDiagnosticTargets,
    override val stagedTargets: PocketDiagnosticTargets,
    private val marker: File,
    private val markerPartial: File,
    private val generationId: String
) : PocketWavCapture {
    override fun publish(): Boolean {
        return try {
            check(PocketDiagnosticWav.isShareable(stagedTargets.float32File))
            check(PocketDiagnosticWav.isShareable(stagedTargets.pcm16File))
            check(stagedTargets.float32File.renameTo(targets.float32File)) { "Could not publish FLOAT32 diagnostic WAV" }
            check(stagedTargets.pcm16File.renameTo(targets.pcm16File)) { "Could not publish PCM16 diagnostic WAV" }
            val markerText = "v1\n$generationId\n${targets.float32File.length()}\n${targets.pcm16File.length()}\n"
            FileOutputStream(markerPartial).use { output ->
                output.write(markerText.toByteArray(Charsets.US_ASCII))
                output.fd.sync()
            }
            check(markerPartial.renameTo(marker)) { "Could not commit Pocket diagnostic pair" }
            true
        } catch (_: Throwable) {
            abort()
            false
        }
    }

    override fun abort() {
        marker.delete()
        markerPartial.delete()
        targets.float32File.delete()
        targets.pcm16File.delete()
        stagedTargets.float32File.delete()
        stagedTargets.pcm16File.delete()
        File(stagedTargets.float32File.parentFile, "${stagedTargets.float32File.name}.partial").delete()
        File(stagedTargets.pcm16File.parentFile, "${stagedTargets.pcm16File.name}.partial").delete()
    }

    companion object {
        private const val STAGING_SUFFIX = ".pending"

        fun begin(targets: PocketDiagnosticTargets): PocketDiagnosticCapture {
            require(targets.float32File.name == PocketDiagnosticWav.FLOAT_FILE_NAME)
            require(targets.pcm16File.name == PocketDiagnosticWav.PCM_FILE_NAME)
            val directory = targets.float32File.parentFile?.canonicalFile
                ?: error("Missing Pocket diagnostics directory")
            require(targets.pcm16File.parentFile?.canonicalFile == directory)
            require(targets.float32File.canonicalFile != targets.pcm16File.canonicalFile)
            check(directory.mkdirs() || directory.isDirectory)
            val marker = File(directory, ".pocket-diagnostics.ready")
            val markerPartial = File(directory, "${marker.name}.partial")
            val stagedFloat = File(directory, "${targets.float32File.name}$STAGING_SUFFIX")
            val stagedPcm = File(directory, "${targets.pcm16File.name}$STAGING_SUFFIX")
            marker.delete()
            markerPartial.delete()
            targets.float32File.delete()
            targets.pcm16File.delete()
            stagedFloat.delete()
            stagedPcm.delete()
            File(directory, "${stagedFloat.name}.partial").delete()
            File(directory, "${stagedPcm.name}.partial").delete()
            return PocketDiagnosticCapture(
                targets,
                PocketDiagnosticTargets(stagedFloat, stagedPcm),
                marker,
                markerPartial,
                UUID.randomUUID().toString()
            )
        }
    }
}
