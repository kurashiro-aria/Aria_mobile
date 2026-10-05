package com.kura.aria.voice.pocket

/** Lifetime-safe JNI wrapper around the streaming PocketTTS.cpp C API. */
internal class NativePocketTts(
    modelsDir: String,
    voicesDir: String,
    precision: String,
    temperature: Float,
    lsdSteps: Int,
    threads: Int,
    sentencePauseMs: Int,
    maxTextTokens: Int
) : AutoCloseable {
    private var handle = 0L

    init {
        System.loadLibrary("pockettts_jni")
        handle = nativeCreate(modelsDir, voicesDir, precision, temperature, lsdSteps, threads,
            sentencePauseMs, maxTextTokens)
        if (handle == 0L) throw PocketVoiceException(PocketVoiceError.MODELO_NO_CARGA)
    }

    fun synthesize(text: String, voiceFile: String, sink: AudioSink): Boolean =
        handle != 0L && nativeSynthesize(handle, text, voiceFile, sink)

    fun stop() { if (handle != 0L) nativeStop(handle) }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    internal fun interface AudioSink { fun onAudio(samples: FloatArray): Boolean }

    private external fun nativeCreate(
        modelsDir: String,
        voicesDir: String,
        precision: String,
        temperature: Float,
        lsdSteps: Int,
        threads: Int,
        sentencePauseMs: Int,
        maxTextTokens: Int
    ): Long
    private external fun nativeSynthesize(handle: Long, text: String, voiceFile: String, sink: AudioSink): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)
}
