package com.kura.aria.voice

import java.io.File
import java.io.RandomAccessFile

enum class QwenLabFailureCode {
    LOAD_MODEL_FAILED,
    JNI_LIBRARY_FAILED,
    MODEL_FILE_MISSING,
    MODEL_SHA_INVALID,
    CODEC_FILE_MISSING,
    GGUF_INCOMPATIBLE,
    ABI_MISMATCH,
    VOICE_PROFILE_FAILED,
    ICL_FAILED,
    STORAGE_ACCESS_FAILED,
    OUT_OF_MEMORY,
    UNKNOWN_FAILURE,
}

class QwenLabException(
    val code: QwenLabFailureCode,
    safeDetail: String,
    cause: Throwable? = null,
) : IllegalStateException(safeDetail, cause)

data class QwenLabFailure(
    val code: QwenLabFailureCode,
    val safeDetail: String,
    val throwableType: String,
)

object QwenVoiceLabDiagnostics {
    fun classify(stage: String, error: Throwable): QwenLabFailure {
        if (error is QwenLabException) {
            return QwenLabFailure(error.code, error.message.orEmpty(), error.javaClass.simpleName)
        }
        if (error is OutOfMemoryError) {
            return QwenLabFailure(QwenLabFailureCode.OUT_OF_MEMORY, "Memoria insuficiente al cargar Qwen", error.javaClass.simpleName)
        }
        if (error is UnsatisfiedLinkError) {
            val code = if (error.message.orEmpty().contains("wrong ELF", ignoreCase = true)) {
                QwenLabFailureCode.ABI_MISMATCH
            } else {
                QwenLabFailureCode.JNI_LIBRARY_FAILED
            }
            return QwenLabFailure(code, "No se pudo iniciar el runtime nativo ARM64", error.javaClass.simpleName)
        }
        if (error is SecurityException) {
            return QwenLabFailure(QwenLabFailureCode.STORAGE_ACCESS_FAILED, "El proceso Qwen no pudo acceder al almacenamiento privado", error.javaClass.simpleName)
        }
        val code = if (stage == "load") QwenLabFailureCode.LOAD_MODEL_FAILED else QwenLabFailureCode.UNKNOWN_FAILURE
        return QwenLabFailure(code, "La operación $stage no pudo completarse", error.javaClass.simpleName)
    }
}

data class QwenReferenceWavFormat(
    val audioFormat: Int,
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val hasData: Boolean,
) {
    val isSupportedByPinnedRuntime: Boolean
        get() = channels > 0 && sampleRate > 0 && hasData &&
            ((audioFormat == PCM && bitsPerSample in setOf(16, 24, 32)) ||
                (audioFormat == IEEE_FLOAT && bitsPerSample == 32))

    companion object {
        const val PCM = 1
        const val IEEE_FLOAT = 3
    }
}

/** Reads only the non-sensitive WAV container metadata needed before JNI. */
object QwenReferenceWavInspector {
    fun inspect(file: File): QwenReferenceWavFormat {
        if (!file.isFile) fail(QwenLabFailureCode.VOICE_PROFILE_FAILED, "Falta la referencia ARIA-B5C")
        try {
            RandomAccessFile(file, "r").use { wav ->
                if (wav.length() < 44L || wav.readFourCc() != "RIFF") invalid()
                wav.readLeUInt32()
                if (wav.readFourCc() != "WAVE") invalid()

                var format: QwenReferenceWavFormat? = null
                var hasData = false
                while (wav.filePointer + 8L <= wav.length()) {
                    val id = wav.readFourCc()
                    val size = wav.readLeUInt32()
                    val payload = wav.filePointer
                    if (payload + size > wav.length()) invalid()
                    when (id) {
                        "fmt " -> {
                            if (size < 16L) invalid()
                            val audioFormat = wav.readLeUInt16()
                            val channels = wav.readLeUInt16()
                            val sampleRate = wav.readLeUInt32().toInt()
                            wav.skipBytes(6)
                            val bits = wav.readLeUInt16()
                            format = QwenReferenceWavFormat(audioFormat, channels, sampleRate, bits, hasData)
                        }
                        "data" -> hasData = size > 0L
                    }
                    wav.seek(payload + size + (size and 1L))
                }
                val parsed = format ?: invalid()
                return parsed.copy(hasData = hasData)
            }
        } catch (error: QwenLabException) {
            throw error
        } catch (error: Throwable) {
            throw QwenLabException(QwenLabFailureCode.VOICE_PROFILE_FAILED, "No se pudo leer la referencia ARIA-B5C", error)
        }
    }

    private fun RandomAccessFile.readFourCc(): String {
        val bytes = ByteArray(4)
        if (read(bytes) != bytes.size) invalid()
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun RandomAccessFile.readLeUInt16(): Int {
        val b0 = read()
        val b1 = read()
        if (b0 < 0 || b1 < 0) invalid()
        return b0 or (b1 shl 8)
    }

    private fun RandomAccessFile.readLeUInt32(): Long {
        val b0 = read()
        val b1 = read()
        val b2 = read()
        val b3 = read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) invalid()
        return b0.toLong() or (b1.toLong() shl 8) or (b2.toLong() shl 16) or (b3.toLong() shl 24)
    }

    private fun invalid(): Nothing = fail(QwenLabFailureCode.VOICE_PROFILE_FAILED, "La referencia ARIA-B5C no es un WAV válido")
}

internal fun fail(code: QwenLabFailureCode, safeDetail: String): Nothing =
    throw QwenLabException(code, safeDetail)
