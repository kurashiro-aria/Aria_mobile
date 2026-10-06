package com.kura.aria.voice.pocket

import java.io.File

/** Resolves the existing diagnostic WAV without copying or changing its bytes. */
internal object PocketDiagnosticWav {
    const val FILE_NAME = "pocket_audio_quality.wav"
    private const val DIRECTORY = "pocket-diagnostics"
    private const val WAV_HEADER_BYTES = 44L

    fun file(cacheDir: File): File = File(File(cacheDir, DIRECTORY), FILE_NAME)

    fun isShareable(file: File): Boolean = file.isFile && file.length() > WAV_HEADER_BYTES
}
