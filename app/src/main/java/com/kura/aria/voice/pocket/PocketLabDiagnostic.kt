package com.kura.aria.voice.pocket

internal data class PocketLabWriteSnapshot(
    val index: Int,
    val requestedBytes: Int,
    val result: Int,
    val trackId: Long?,
    val trackState: Int?,
    val playState: Int?,
    val beforePlaybackHeadPosition: Long?,
    val beforeUnderrunCount: Int?,
    val playbackHeadPosition: Long?,
    val underrunCount: Int?,
    val cancelled: Boolean,
    val timestampMs: Long
)

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
    AUDIO_TRACK_WRITE_FAILED,
    AUDIO_TRACK_PLAY_REQUEST,
    AUDIO_TRACK_PLAY_OK,
    AUDIO_TRACK_PLAY_FAILED,
    PIPELINE_FINISH_REQUEST,
    PIPELINE_FINISH_OK,
    PIPELINE_FINISH_FAILED,
    PLAYBACK_DRAIN_REQUEST,
    PLAYBACK_DRAIN_OK,
    PLAYBACK_DRAIN_TIMEOUT,
    PLAYBACK_DRAIN_FAILED,
    TRACK_STOP_REQUEST,
    TRACK_STOP_OK,
    TRACK_STOP_FAILED,
    TRACK_RELEASE_OK,
    TRACK_RELEASE_FAILED,
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
    val writeCount: Int = 0,
    val totalBytesRequested: Long = 0L,
    val totalBytesWritten: Long = 0L,
    val failedWriteIndex: Int? = null,
    val failedWriteRequestedBytes: Int? = null,
    val failedWriteResult: Int? = null,
    val failedWriteTrackState: Int? = null,
    val failedWritePlayState: Int? = null,
    val trackId: Long? = null,
    val lastWriteTrackId: Long? = null,
    val lastWriteThread: String? = null,
    val lastWriteCancelled: Boolean? = null,
    val lastWritePlaybackHeadPosition: Long? = null,
    val lastWriteUnderrunCount: Int? = null,
    val lastWriteBeforePlaybackHeadPosition: Long? = null,
    val lastWriteBeforeUnderrunCount: Int? = null,
    val failedWritePlaybackHeadPosition: Long? = null,
    val failedWriteUnderrunCount: Int? = null,
    val failedWriteBeforePlaybackHeadPosition: Long? = null,
    val failedWriteBeforeUnderrunCount: Int? = null,
    val trackSessionId: Int? = null,
    val routedDevice: String? = null,
    val writeHistory: List<PocketLabWriteSnapshot> = emptyList(),
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
            val terminalError = it.state == "ERROR" || it.error != null
            val preserveTerminalStage = terminalError || it.stage == PocketLabStage.SYNTHESIS_COMPLETE
            it.copy(
                state = if (stage == PocketLabStage.SYNTHESIS_COMPLETE && it.error == null) "COMPLETADO" else it.state,
                stage = when {
                    stage == PocketLabStage.SYNTHESIS_COMPLETE && it.error != null -> PocketLabStage.ERROR
                    preserveTerminalStage -> it.stage
                    else -> stage
                },
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

    fun trackCreated(
        id: Long,
        state: Int,
        playState: Int,
        bufferFrames: Int?,
        sessionId: Int? = null,
        routedDevice: String? = null
    ) {
        update {
            it.copy(
                stage = PocketLabStage.CREATE_AUDIO_TRACK_OK,
                trackId = id,
                lastWriteTrackId = id,
                trackState = state,
                playState = playState,
                bufferSizeInFrames = bufferFrames,
                trackSessionId = sessionId,
                routedDevice = routedDevice
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

    fun writeAttempt(
        requestedBytes: Int,
        result: Int,
        trackState: Int?,
        playState: Int?,
        trackId: Long? = null,
        cancelled: Boolean = false,
        beforePlaybackHeadPosition: Long? = null,
        beforeUnderrunCount: Int? = null,
        playbackHeadPosition: Long? = null,
        underrunCount: Int? = null,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        update {
            val index = it.writeCount + 1
            val failed = result <= 0 && it.failedWriteIndex == null
            val event = PocketLabWriteSnapshot(
                index, requestedBytes, result, trackId, trackState, playState,
                beforePlaybackHeadPosition, beforeUnderrunCount,
                playbackHeadPosition, underrunCount, cancelled, timestampMs
            )
            it.copy(
                writeCount = index,
                totalBytesRequested = it.totalBytesRequested + requestedBytes,
                totalBytesWritten = it.totalBytesWritten + result.coerceAtLeast(0),
                failedWriteIndex = if (failed) index else it.failedWriteIndex,
                failedWriteRequestedBytes = if (failed) requestedBytes else it.failedWriteRequestedBytes,
                failedWriteResult = if (failed) result else it.failedWriteResult,
                failedWriteTrackState = if (failed) trackState else it.failedWriteTrackState,
                failedWritePlayState = if (failed) playState else it.failedWritePlayState,
                failedWritePlaybackHeadPosition = if (failed) playbackHeadPosition else it.failedWritePlaybackHeadPosition,
                failedWriteUnderrunCount = if (failed) underrunCount else it.failedWriteUnderrunCount,
                failedWriteBeforePlaybackHeadPosition = if (failed) beforePlaybackHeadPosition else it.failedWriteBeforePlaybackHeadPosition,
                failedWriteBeforeUnderrunCount = if (failed) beforeUnderrunCount else it.failedWriteBeforeUnderrunCount,
                lastWriteTrackId = trackId ?: it.lastWriteTrackId,
                lastWriteThread = Thread.currentThread().name,
                lastWriteCancelled = cancelled,
                lastWritePlaybackHeadPosition = playbackHeadPosition,
                lastWriteUnderrunCount = underrunCount,
                lastWriteBeforePlaybackHeadPosition = beforePlaybackHeadPosition,
                lastWriteBeforeUnderrunCount = beforeUnderrunCount,
                writeHistory = (it.writeHistory + event).takeLast(MAX_WRITE_HISTORY)
            )
        }
    }

    fun audioPlayRequested() = mark(PocketLabStage.AUDIO_TRACK_PLAY_REQUEST)
    fun audioPlayOk() = mark(PocketLabStage.AUDIO_TRACK_PLAY_OK)
    fun audioPlayFailed(detail: String) = mark(PocketLabStage.AUDIO_TRACK_PLAY_FAILED, detail)
    fun pipelineFinishRequested() = mark(PocketLabStage.PIPELINE_FINISH_REQUEST)
    fun pipelineFinishOk() = mark(PocketLabStage.PIPELINE_FINISH_OK)
    fun pipelineFinishFailed(detail: String) = mark(PocketLabStage.PIPELINE_FINISH_FAILED, detail)
    fun playbackDrainRequested() = mark(PocketLabStage.PLAYBACK_DRAIN_REQUEST)
    fun playbackDrainOk() = mark(PocketLabStage.PLAYBACK_DRAIN_OK)
    fun playbackDrainTimeout(detail: String) = mark(PocketLabStage.PLAYBACK_DRAIN_TIMEOUT, detail)
    fun playbackDrainFailed(detail: String) = mark(PocketLabStage.PLAYBACK_DRAIN_FAILED, detail)
    fun trackStopRequested() = mark(PocketLabStage.TRACK_STOP_REQUEST)
    fun trackStopOk() = mark(PocketLabStage.TRACK_STOP_OK)
    fun trackStopFailed(detail: String) = mark(PocketLabStage.TRACK_STOP_FAILED, detail)
    fun trackReleaseOk() = mark(PocketLabStage.TRACK_RELEASE_OK)
    fun trackReleaseFailed(detail: String) = mark(PocketLabStage.TRACK_RELEASE_FAILED, detail)

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
            append("Track id: ").append(value.trackId ?: "—")
                .append(" · Last write id: ").append(value.lastWriteTrackId ?: "—").append('\n')
            append("First write: ").append(value.firstWriteResult?.toString() ?: "—")
                .append(" / ").append(value.firstWriteRequestedBytes?.toString() ?: "—").append('\n')
            append("Writes: ").append(value.writeCount).append(" · requested=").append(value.totalBytesRequested)
                .append(" · written=").append(value.totalBytesWritten).append('\n')
            value.failedWriteIndex?.let {
                append("Failed write #").append(it).append(": ").append(value.failedWriteResult)
                    .append(" / ").append(value.failedWriteRequestedBytes)
                    .append(" · state=").append(value.failedWriteTrackState)
                    .append(" play=").append(value.failedWritePlayState).append('\n')
                append("Failed head=").append(value.failedWritePlaybackHeadPosition ?: "—")
                    .append(" underruns=").append(value.failedWriteUnderrunCount ?: "—").append('\n')
                append("Failed before head=").append(value.failedWriteBeforePlaybackHeadPosition ?: "—")
                    .append(" underruns=").append(value.failedWriteBeforeUnderrunCount ?: "—").append('\n')
            }
            value.lastWriteThread?.let { append("Write thread: ").append(it).append('\n') }
            value.lastWriteCancelled?.let { append("Cancelled at write: ").append(it).append('\n') }
            value.lastWritePlaybackHeadPosition?.let { append("Last head: ").append(it).append('\n') }
            value.lastWriteUnderrunCount?.let { append("Last underruns: ").append(it).append('\n') }
            value.lastWriteBeforePlaybackHeadPosition?.let { append("Last before head: ").append(it).append('\n') }
            value.lastWriteBeforeUnderrunCount?.let { append("Last before underruns: ").append(it).append('\n') }
            value.trackSessionId?.let { append("Session: ").append(it).append('\n') }
            value.routedDevice?.let { append("Route: ").append(it).append('\n') }
            if (value.writeHistory.isNotEmpty()) {
                append("Write trace: ").append(value.writeHistory.joinToString(" | ") {
                    "#${it.index} ${it.requestedBytes}/${it.result} head=${it.playbackHeadPosition ?: "—"} underruns=${it.underrunCount ?: "—"}"
                }).append('\n')
            }
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

    private companion object {
        const val MAX_WRITE_HISTORY = 12
    }
}

/** Numeric events emitted by JNI; normal synthesis passes no observer. */
internal object PocketNativeStage {
    const val STREAM_START_REQUEST = 1
    const val STREAM_START_OK = 2
    const val STREAM_START_FAILED = 3
    const val STREAM_READ_FIRST = 4
}
