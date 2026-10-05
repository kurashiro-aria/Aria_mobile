package com.qwen.tts.studio.engine

class QwenEngine : AutoCloseable {
    private var nativePtr: Long = 0

    companion object {
        const val BACKEND_AUTO: Int = 0
        const val BACKEND_CPU: Int = 1
        const val BACKEND_GPU: Int = 2
    }

    class NativeParams(
        val languageId: Int = 2050,
        val instruction: String? = null,
        val speaker: String? = null,
        val maxAudioTokens: Int = 512,
    )

    class NativeResult(
        val audio: FloatArray?,
        val sampleRate: Int,
        val success: Boolean,
        val errorMsg: String?,
        val timeMs: Long,
    ) {
        val tokenizeMs: Long = 0L
        val encodeMs: Long = 0L
        val generateMs: Long = 0L
        val decodeMs: Long = 0L
        val decodeFrames: Int = 0
        val decodeSamples: Long = 0L
        val decodeGraphComputeMs: Long = 0L
    }

    class NativeCapabilities(
        val loaded: Boolean,
        val supportsCloning: Boolean,
        val supportsNamedSpeakers: Boolean,
        val supportsInstruction: Boolean,
        val speakerEmbeddingDim: Int,
        val modelKind: Int,
        val speakerCount: Int,
    )

    fun interface ProgressCallback {
        fun onProgress(tokensGenerated: Int, maxTokens: Int)
    }

    init {
        System.loadLibrary("qwen3_tts_jni")
        nativePtr = nativeInit()
    }

    fun loadModels(modelDir: String, modelName: String? = null): Boolean =
        nativeLoadModels(nativePtr, modelDir, modelName)

    fun loadIclPromptEncoder(modelDir: String, modelName: String? = null): Boolean =
        nativeLoadIclPromptEncoder(nativePtr, modelDir, modelName)

    fun synthesize(
        text: String,
        referenceWav: String? = null,
        speakerEmbeddingPath: String? = null,
        params: NativeParams = NativeParams(),
    ): NativeResult =
        nativeSynthesize(nativePtr, text, referenceWav, speakerEmbeddingPath, params)

    fun synthesizeWithIclPrompt(
        text: String,
        iclPromptPath: String,
        params: NativeParams = NativeParams(),
    ): NativeResult = nativeSynthesizeWithIclPrompt(nativePtr, text, iclPromptPath, params)

    fun interface AudioChunkCallback {
        fun onAudioChunk(
            audio: FloatArray,
            sampleRate: Int,
            startSample: Long,
            endSample: Long,
            startFrame: Int,
            endFrame: Int,
            startTextByte: Int,
            endTextByte: Int,
            textAlignmentKind: Int,
            confidence: Float,
        ): Boolean
    }

    /**
     * Real incremental ICL synthesis. The callback is invoked by the native
     * decoder as PCM becomes available; it is not a split of a completed WAV.
     */
    fun synthesizeWithIclPromptStreaming(
        text: String,
        iclPromptPath: String,
        params: NativeParams = NativeParams(),
        chunkSeconds: Float = 1.0f,
        leftContextSeconds: Float = 2.0f,
        collectAudio: Boolean = false,
        callback: AudioChunkCallback,
    ): NativeResult = nativeSynthesizeWithIclPromptStreaming(
        nativePtr,
        text,
        iclPromptPath,
        params,
        chunkSeconds,
        leftContextSeconds,
        collectAudio,
        callback,
    )

    fun getLastError(): String? = nativeGetLastError(nativePtr)

    fun getActiveBackendName(): String? = nativeGetActiveBackendName()

    fun setBackendPreference(preference: Int): Boolean = nativeSetBackendPreference(preference)

    fun getCompiledBackendMask(): Int = nativeGetCompiledBackendMask()

    fun setCpuThreads(nThreads: Int): Boolean = nativeSetCpuThreads(nThreads)

    fun getCpuThreads(): Int = nativeGetCpuThreads()

    fun setProgressCallback(callback: ProgressCallback?): Boolean =
        nativeSetProgressCallback(nativePtr, callback)

    fun getModelCapabilities(): NativeCapabilities? =
        nativeGetModelCapabilities(nativePtr)

    fun extractSpeakerEmbedding(referenceWav: String, outputPath: String): Boolean =
        nativeExtractSpeakerEmbedding(nativePtr, referenceWav, outputPath)

    fun extractIclPrompt(referenceWav: String, referenceText: String, outputPath: String): Boolean =
        nativeExtractIclPrompt(nativePtr, referenceWav, referenceText, outputPath)

    fun getAvailableSpeakers(): List<String> {
        val raw = nativeGetAvailableSpeakers(nativePtr).orEmpty()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    override fun close() {
        if (nativePtr != 0L) {
            nativeFree(nativePtr)
            nativePtr = 0
        }
    }

    private external fun nativeInit(): Long
    private external fun nativeFree(ptr: Long)
    private external fun nativeSetBackendPreference(preference: Int): Boolean
    private external fun nativeGetCompiledBackendMask(): Int
    private external fun nativeSetCpuThreads(nThreads: Int): Boolean
    private external fun nativeGetCpuThreads(): Int
    private external fun nativeSetProgressCallback(ptr: Long, callback: ProgressCallback?): Boolean
    private external fun nativeGetActiveBackendName(): String?
    private external fun nativeLoadModels(ptr: Long, modelDir: String, modelName: String?): Boolean
    private external fun nativeLoadIclPromptEncoder(ptr: Long, modelDir: String, modelName: String?): Boolean
    private external fun nativeSynthesize(
        ptr: Long,
        text: String,
        referenceWav: String?,
        speakerEmbeddingPath: String?,
        params: NativeParams?,
    ): NativeResult
    private external fun nativeSynthesizeWithIclPrompt(
        ptr: Long,
        text: String,
        iclPromptPath: String,
        params: NativeParams?,
    ): NativeResult
    private external fun nativeSynthesizeWithIclPromptStreaming(
        ptr: Long,
        text: String,
        iclPromptPath: String,
        params: NativeParams?,
        chunkSeconds: Float,
        leftContextSeconds: Float,
        collectAudio: Boolean,
        callback: AudioChunkCallback,
    ): NativeResult
    private external fun nativeGetLastError(ptr: Long): String?
    private external fun nativeGetModelCapabilities(ptr: Long): NativeCapabilities?
    private external fun nativeExtractSpeakerEmbedding(ptr: Long, referenceWav: String, outputPath: String): Boolean
    private external fun nativeExtractIclPrompt(
        ptr: Long,
        referenceWav: String,
        referenceText: String,
        outputPath: String,
    ): Boolean
    private external fun nativeGetAvailableSpeakers(ptr: Long): String?
}
