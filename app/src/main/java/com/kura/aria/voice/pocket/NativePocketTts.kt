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
    maxTextTokens: Int,
    kvMode: PocketKvMode = PocketKvMode.CURRENT_SINGLE_BUFFER,
    randomSeed: Long = 0L
) : AutoCloseable {
    private var handle = 0L

    init {
        System.loadLibrary("pockettts_jni")
        handle = nativeCreate(modelsDir, voicesDir, precision, temperature, lsdSteps, threads,
            sentencePauseMs, maxTextTokens, kvMode.nativeValue, randomSeed)
        if (handle == 0L) throw PocketVoiceException(PocketVoiceError.MODELO_NO_CARGA)
    }

    fun synthesize(
        text: String,
        voiceFile: String,
        sink: AudioSink,
        observer: NativeStageObserver? = null
    ): Boolean = handle != 0L && nativeSynthesize(handle, text, voiceFile, sink, observer)

    fun lastRunMetrics(): NativeRunMetrics {
        val values = if (handle != 0L) nativeLastRunMetrics(handle) else null
        return NativeRunMetrics(
            conditioningMs = values?.getOrNull(0) ?: 0L,
            callbackCount = (values?.getOrNull(1) ?: 0L).toInt(),
            segmentCount = (values?.getOrNull(2) ?: 0L).toInt(),
            mimiFrames = (values?.getOrNull(3) ?: 0L).toInt()
        )
    }

    fun stop() { if (handle != 0L) nativeStop(handle) }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    internal fun interface AudioSink { fun onAudio(samples: FloatArray): Boolean }

    internal fun interface NativeStageObserver { fun onStage(stage: Int, value: Int) }

    private external fun nativeCreate(
        modelsDir: String,
        voicesDir: String,
        precision: String,
        temperature: Float,
        lsdSteps: Int,
        threads: Int,
        sentencePauseMs: Int,
        maxTextTokens: Int,
        kvMode: Int,
        randomSeed: Long
    ): Long
    private external fun nativeSynthesize(
        handle: Long,
        text: String,
        voiceFile: String,
        sink: AudioSink,
        observer: NativeStageObserver?
    ): Boolean
    private external fun nativeLastRunMetrics(handle: Long): LongArray?
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)
}

internal data class NativeRunMetrics(
    val conditioningMs: Long,
    val callbackCount: Int,
    val segmentCount: Int,
    val mimiFrames: Int
)
