package com.kura.aria.voice.pocket

/** Stages exposed only by the temporary KV/LSD isolation laboratory. */
internal enum class PocketLabStage {
    IDLE,
    LAB_REQUEST,
    STOP_LOCAL_VOICE,
    LAB_CAPTURE_READY,
    RUNTIME_LOAD_REQUEST,
    OLD_RUNTIME_RELEASE,
    NATIVE_CREATE_REQUEST,
    NATIVE_CREATE_OK,
    NATIVE_CREATE_FAILED,
    SYNTHESIS_REQUEST,
    STREAM_START_REQUEST,
    STREAM_START_OK,
    STREAM_START_FAILED,
    STREAM_READ_FIRST,
    FIRST_FLOAT_CALLBACK,
    FIRST_PCM_PIPELINE_ACCEPT,
    FIRST_AUDIO_TRACK_WRITE,
    FIRST_AUDIO_TRACK_WRITE_OK,
    FIRST_AUDIO_TRACK_WRITE_FAILED,
    CREATE_AUDIO_TRACK_REQUEST,
    CREATE_AUDIO_TRACK_OK,
    CREATE_AUDIO_TRACK_FAILED,
    SYNTHESIS_COMPLETE,
    ERROR
}

internal data class PocketLabDiagnosticSnapshot(
    val variant: PocketIsolationVariant,
    val state: String = "LISTO",
    val stage: PocketLabStage = PocketLabStage.IDLE,
    val error: PocketVoiceError? = null,
    val technicalDetail: String? = null,
    val callbacks: Int = 0,
    val samples: Long = 0L,
    val minBufferSize: Int? = null,
    val trackState: Int? = null,
    val playState: Int? = null,
    val bufferSizeInFrames: Int? = null,
    val firstWriteRequestedBytes: Int? = null,
    val firstWriteResult: Int? = null,
    val nativeConfig: String? = null,
    val previousTrackDetail: String? = null,
    val pipelineAccepted: Boolean = false
)

/** Thread-safe, small diagnostic state shared by one laboratory run and its UI. */
internal class PocketLabDiagnostic(val variant: PocketIsolationVariant) {
    @Volatile var onChanged: ((PocketLabDiagnosticSnapshot) -> Unit)? = null

    private var current = PocketLabDiagnosticSnapshot(variant)

    fun snapshot(): PocketLabDiagnosticSnapshot = synchronized(this) { current }

    fun mark(stage: PocketLabStage, detail: String? = null) {
        update {
            it.copy(
                state = if (stage == PocketLabStage.SYNTHESIS_COMPLETE) "COMPLETADO" else it.state,
                stage = stage,
                technicalDetail = detail ?: it.technicalDetail
            )
        }
    }

    fun fail(stage: PocketLabStage, error: PocketVoiceError?, detail: String? = null) {
        update {
            it.copy(state = "ERROR", stage = stage, error = error, technicalDetail = detail ?: it.technicalDetail)
        }
    }

    fun preserveError(error: Throwable, fallbackStage: PocketLabStage? = null) {
        val code = (error as? PocketVoiceException)?.code
        update {
            it.copy(
                state = "ERROR",
                stage = if (it.stage == PocketLabStage.IDLE) fallbackStage ?: PocketLabStage.ERROR else it.stage,
                error = it.error ?: code,
                technicalDetail = it.technicalDetail ?: error.javaClass.simpleName
            )
        }
    }

    fun nativeConfig(kvMode: PocketKvMode, lsdSteps: Int, seedFixed: Boolean) {
        update { it.copy(nativeConfig = "KV=${kvMode.name} LSD=$lsdSteps seed=${if (seedFixed) "FIXED" else "TEMPORAL"}") }
    }

    fun callback(sampleCount: Int) {
        update {
            it.copy(
                stage = if (it.callbacks == 0) PocketLabStage.FIRST_FLOAT_CALLBACK else it.stage,
                callbacks = it.callbacks + 1,
                samples = it.samples + sampleCount
            )
        }
    }

    fun pipelineAccepted(ok: Boolean, detail: String? = null) {
        update {
            if (it.pipelineAccepted) it
            else it.copy(
                state = if (ok) it.state else "ERROR",
                stage = PocketLabStage.FIRST_PCM_PIPELINE_ACCEPT,
                technicalDetail = detail ?: it.technicalDetail,
                pipelineAccepted = true
            )
        }
    }

    fun audioTrackRequested(minBuffer: Int, sampleRate: Int, channels: Int, encoding: Int) {
        update {
            it.copy(
                stage = PocketLabStage.CREATE_AUDIO_TRACK_REQUEST,
                minBufferSize = minBuffer,
                technicalDetail = "sr=$sampleRate channels=$channels encoding=$encoding"
            )
        }
    }

    fun audioTrackCreated(state: Int, playState: Int, bufferFrames: Int?) {
        update {
            it.copy(
                stage = PocketLabStage.CREATE_AUDIO_TRACK_OK,
                trackState = state,
                playState = playState,
                bufferSizeInFrames = bufferFrames
            )
        }
    }

    fun audioTrackCreateFailed(detail: String) = fail(PocketLabStage.CREATE_AUDIO_TRACK_FAILED, PocketVoiceError.AUDIO_FAILED, detail)

    fun firstWrite(requestedBytes: Int, result: Int) {
        update {
            if (it.firstWriteResult != null) it
            else it.copy(
                stage = if (result > 0) PocketLabStage.FIRST_AUDIO_TRACK_WRITE_OK
                else PocketLabStage.FIRST_AUDIO_TRACK_WRITE_FAILED,
                firstWriteRequestedBytes = requestedBytes,
                firstWriteResult = result
            )
        }
    }

    fun previousTrack(detail: String) = update { it.copy(previousTrackDetail = detail) }

    fun summary(): String {
        val value = snapshot()
        return buildString {
            append("DIAGNÓSTICO LAB\n")
            append("Variante: ").append(value.variant.name.substringBefore('_')).append('\n')
            append("Estado: ").append(value.state).append('\n')
            append("Etapa: ").append(value.stage.name).append('\n')
            append("Error: ").append(value.error?.name ?: "—").append('\n')
            append("Callbacks: ").append(value.callbacks).append(" · Samples: ").append(value.samples).append('\n')
            append("Min buffer: ").append(value.minBufferSize ?: "—").append('\n')
            append("Track state: ").append(value.trackState ?: "—")
                .append(" · Play: ").append(value.playState ?: "—").append('\n')
            append("First write: ").append(value.firstWriteResult?.toString() ?: "—")
                .append(" / ").append(value.firstWriteRequestedBytes?.toString() ?: "—").append('\n')
            value.nativeConfig?.let { append(it).append('\n') }
            value.technicalDetail?.let { append(it).append('\n') }
        }.trimEnd()
    }

    private fun update(transform: (PocketLabDiagnosticSnapshot) -> PocketLabDiagnosticSnapshot) {
        val next = synchronized(this) {
            current = transform(current)
            current
        }
        onChanged?.invoke(next)
    }
}

/** Numeric events emitted by JNI; normal synthesis passes no observer. */
internal object PocketNativeStage {
    const val STREAM_START_REQUEST = 1
    const val STREAM_START_OK = 2
    const val STREAM_START_FAILED = 3
    const val STREAM_READ_FIRST = 4
}
