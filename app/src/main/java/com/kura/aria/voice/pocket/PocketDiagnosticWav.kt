package com.kura.aria.voice.pocket

import java.io.File

/** Resolves diagnostic WAVs without copying or changing their bytes. */
internal object PocketDiagnosticWav {
    const val PCM_FILE_NAME = "pocket_audio_quality.wav"
    const val FLOAT_FILE_NAME = "pocket_audio_pre_pcm_f32.wav"
    private const val DIRECTORY = "pocket-diagnostics"
    private const val WAV_HEADER_BYTES = 44L

    fun file(cacheDir: File): File = File(File(cacheDir, DIRECTORY), PCM_FILE_NAME)
    fun floatFile(cacheDir: File): File = File(File(cacheDir, DIRECTORY), FLOAT_FILE_NAME)

    /** The order is FLOAT32 source followed by the corresponding playback PCM16 WAV. */
    fun files(cacheDir: File): List<File> = listOf(floatFile(cacheDir), file(cacheDir))

    fun isShareable(file: File): Boolean = file.isFile && file.length() > WAV_HEADER_BYTES
    fun areShareable(files: List<File>): Boolean = files.size == 2 && files.all(::isShareable)
}
