package com.kura.aria.voice.pocket

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.kura.aria.emotion.AriaEmotion
import com.kura.aria.personality.ExpressionStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

internal object PocketPlaybackDrain {
    /** True only when there is no pending audio or the playback head reached it. */
    fun isComplete(writtenSamples: Long, playbackHead: Long): Boolean =
        writtenSamples <= 0L || playbackHead >= writtenSamples
}

internal class PocketVoiceEngine(context: Context) : AutoCloseable {
    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "aria-pocket-tts").apply { priority = Thread.NORM_PRIORITY }
    }.asCoroutineDispatcher()
    @Volatile private var native: NativePocketTts? = null
    @Volatile private var loadedPackId: String? = null
    @Volatile private var loadedRuntimeConfig: PocketRuntimeConfig? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var activeLabTrackId: Long? = null
    @Volatile private var pendingLabStopDiagnostic: PocketLabDiagnostic? = null
    private val cancelled = AtomicBoolean(false)
    private val nextLabTrackId = AtomicLong(1L)
    @Volatile var lastMetrics = PocketMetrics()
        private set

    suspend fun load(
        pack: PocketPack,
        runtimeConfig: PocketRuntimeConfig = PocketRuntimeConfig.normal(pack),
        onState: (String) -> Unit = {},
        labDiagnostic: PocketLabDiagnostic? = null
    ): Long = withContext(dispatcher) {
        if (native != null && loadedPackId == pack.id && loadedRuntimeConfig == runtimeConfig) return@withContext 0L
        onState("CARGANDO")
        labDiagnostic?.mark(PocketLabStage.RUNTIME_LOAD_REQUEST,
            "KV=${runtimeConfig.kvMode.name} LSD=${runtimeConfig.lsdSteps} " +
                "seed=${if (runtimeConfig.randomSeed == 0L) "TEMPORAL" else "FIXED"}")
        if (native != null) labDiagnostic?.mark(PocketLabStage.OLD_RUNTIME_RELEASE)
        releaseNative(labDiagnostic)
        val started = SystemClock.elapsedRealtime()
        labDiagnostic?.nativeConfig(runtimeConfig.kvMode, runtimeConfig.lsdSteps, runtimeConfig.randomSeed != 0L)
        labDiagnostic?.mark(PocketLabStage.NATIVE_CREATE_REQUEST)
        try {
            native = NativePocketTts(
                pack.modelsDir.absolutePath,
                pack.voicesDir.absolutePath,
                pack.precision,
                pack.temperature,
                runtimeConfig.lsdSteps,
                pack.threads.coerceIn(1, 8),
                250,
                50,
                runtimeConfig.kvMode,
                runtimeConfig.randomSeed
            )
            labDiagnostic?.mark(PocketLabStage.NATIVE_CREATE_OK)
            loadedPackId = pack.id
            loadedRuntimeConfig = runtimeConfig
            val elapsed = SystemClock.elapsedRealtime() - started
            lastMetrics = lastMetrics.copy(modelLoadMs = elapsed, state = "LISTO")
            onState("LISTO")
            elapsed
        } catch (error: Throwable) {
            labDiagnostic?.fail(PocketLabStage.NATIVE_CREATE_FAILED, PocketVoiceError.MODELO_NO_CARGA,
                error.javaClass.simpleName)
            releaseNative(labDiagnostic)
            lastMetrics = lastMetrics.copy(state = "ERROR", error = PocketVoiceError.MODELO_NO_CARGA)
            throw if (error is PocketVoiceException) error
            else PocketVoiceException(PocketVoiceError.MODELO_NO_CARGA, error)
        }
    }

    suspend fun synthesize(
        pack: PocketPack,
        voice: PocketVoice,
        text: String,
        emotion: AriaEmotion,
        expression: ExpressionStyle,
        onState: (String) -> Unit = {},
        diagnosticTargets: PocketDiagnosticTargets? = null,
        diagnosticCapture: PocketWavCapture? = null,
        runtimeConfig: PocketRuntimeConfig = PocketRuntimeConfig.normal(pack),
        transportMode: PocketTransportMode = PocketTransportMode.CURRENT_WRITES,
        labDiagnostic: PocketLabDiagnostic? = null
    ): PocketMetrics = withContext(dispatcher) {
        require(text.isNotBlank())
        if (!PocketInstallValidator.hasRequiredFiles(pack))
            throw PocketVoiceException(PocketVoiceError.MODELO_CORRUPTO)
        if (!PocketInstallValidator.isVoiceAvailable(pack.voicesDir, voice))
            throw PocketVoiceException(PocketVoiceError.VOZ_NO_DISPONIBLE)

        require(diagnosticTargets == null || diagnosticCapture == null)
        val activeCapture = diagnosticCapture ?: diagnosticTargets?.let { targets ->
            runCatching { PocketDiagnosticWav.beginCapture(targets) }
                .onFailure { error -> Log.w(TAG, "Pocket diagnostic capture initialization failed: ${error.javaClass.simpleName}") }
                .getOrNull()
        }
        labDiagnostic?.mark(PocketLabStage.SYNTHESIS_REQUEST)
        cancelled.set(false)
        val requestStarted = SystemClock.elapsedRealtime()
        val ramBefore = Debug.getPss().toLong() / 1024L
        val modelLoad = load(pack, runtimeConfig, onState, labDiagnostic)
        coroutineContext.ensureActive()
        val prosody = AriaPocketProsodyDirector.resolve(emotion, expression, pack.temperature)
        Log.i(TAG, "SYNTHESIS_REQUESTED emotion=${prosody.emotion} expression=${prosody.expressionStyle} " +
            "temperature=${prosody.temperature} chars=${text.length}")
        onState("SINTETIZANDO")
        var firstAudioMs: Long? = null
        val generationStarted = SystemClock.elapsedRealtime()
        // The laboratory is entered immediately after stopLocalVoice(). That stop
        // intentionally leaves the old track in STOPPED state until the serialized
        // engine dispatcher is back on this thread. Release it here, before asking
        // Android for another track, so the lab cannot race an old AudioTrack
        // ownership transition. Normal chat keeps its established path unchanged.
        if (labDiagnostic != null) {
            releasePreviousLabAudioTrack(labDiagnostic)
        }
        val track = createAudioTrack(labDiagnostic)
        val labTrackId = if (labDiagnostic != null) nextLabTrackId.getAndIncrement() else null
        if (labDiagnostic != null) {
            labDiagnostic.trackCreated(
                labTrackId!!,
                track.state,
                track.playState,
                runCatching { track.bufferSizeInFrames }.getOrNull(),
                runCatching { track.audioSessionId }.getOrNull(),
                trackRoute(track)
            )
            labDiagnostic.audioEnvironment(
                trackVolume = trackVolume(track),
                musicVolume = runCatching { audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull(),
                mode = runCatching { audioManager?.mode }.getOrNull(),
                sampleRate = PocketAudioFormat.SAMPLE_RATE,
                channelMask = AudioFormat.CHANNEL_OUT_MONO,
                encoding = AudioFormat.ENCODING_PCM_16BIT
            )
            labDiagnostic.trace(
                "AUDIO_TRACK_CREATED",
                "state=${track.state} play=${track.playState} " +
                    "head=${trackPlaybackHead(track) ?: "—"} session=${runCatching { track.audioSessionId }.getOrNull() ?: "—"} " +
                    "route=${trackRoute(track) ?: "—"} volume=${trackVolume(track) ?: "—"} " +
                    "musicVolume=${audioManager?.let { runCatching { it.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull() } ?: "—"} " +
                    "mode=${audioManager?.mode ?: "—"}"
            )
            labDiagnostic.trace("ROUTE_INFO", "route=${trackRoute(track) ?: "—"} session=${runCatching { track.audioSessionId }.getOrNull() ?: "—"}")
        }
        activeLabTrackId = labTrackId
        audioTrack = track
        val underrunsBefore = track.underrunCount
        var writeIndex = 0
        val pipeline = PocketPcm16Pipeline(
            writer = { bytes, offset, length ->
                val firstWrite = labDiagnostic?.snapshot()?.firstWriteResult == null
                if (firstWrite) {
                    labDiagnostic?.mark(PocketLabStage.FIRST_AUDIO_TRACK_WRITE)
                    labDiagnostic?.trace("FIRST_WRITE", "requestedBytes=$length", notify = false)
                }
                val beforeHead = trackPlaybackHead(track)
                val beforeUnderruns = trackUnderruns(track)
                val written = try {
                    track.write(bytes, offset, length, AudioTrack.WRITE_BLOCKING)
                } catch (error: Throwable) {
                    labDiagnostic?.trace("AUDIO_FAILED_ORIGIN", "operation=AudioTrack.write exception=${error.javaClass.simpleName}")
                    writeIndex++
                    labDiagnostic?.writeAttempt(
                        length, -1, track.state, track.playState, labTrackId, cancelled.get(),
                        beforeHead, beforeUnderruns, trackPlaybackHead(track), trackUnderruns(track)
                    )
                    if (firstWrite) {
                        labDiagnostic?.firstWrite(length, -1)
                    }
                    labDiagnostic?.fail(
                        if (firstWrite) PocketLabStage.FIRST_AUDIO_TRACK_WRITE_FAILED
                        else PocketLabStage.AUDIO_TRACK_WRITE_FAILED,
                        PocketVoiceError.AUDIO_FAILED,
                        "write#$writeIndex threw ${error.javaClass.simpleName}: ${error.message ?: "no message"} " +
                            "track=${labTrackId ?: "—"} thread=${Thread.currentThread().name} cancelled=${cancelled.get()}"
                    )
                    throw error
                }
                writeIndex++
                labDiagnostic?.writeAttempt(
                    length, written, track.state, track.playState, labTrackId, cancelled.get(),
                    beforeHead, beforeUnderruns, trackPlaybackHead(track), trackUnderruns(track)
                )
                labDiagnostic?.trace(
                    "WRITE_COMPLETE",
                    "index=$writeIndex requested=$length result=$written head=${trackPlaybackHead(track) ?: "—"} " +
                        "underruns=${trackUnderruns(track) ?: "—"}",
                    notify = false
                )
                if (firstWrite) labDiagnostic?.firstWrite(length, written)
                written.also {
                    if (written <= 0) {
                        labDiagnostic?.trace("AUDIO_FAILED_ORIGIN",
                            "operation=AudioTrack.write result=$written state=${track.state} play=${track.playState}")
                        labDiagnostic?.fail(if (firstWrite) PocketLabStage.FIRST_AUDIO_TRACK_WRITE_FAILED
                            else PocketLabStage.AUDIO_TRACK_WRITE_FAILED,
                            PocketVoiceError.AUDIO_FAILED,
                            "write#$writeIndex result=$written (${writeResultName(written)}) requested=$length " +
                            "track=${labTrackId ?: "—"} state=${track.state} play=${track.playState} " +
                                "head=${trackPlaybackHead(track) ?: "—"} underruns=${trackUnderruns(track) ?: "—"} " +
                                "thread=${Thread.currentThread().name} cancelled=${cancelled.get()}")
                        throw PocketVoiceException(PocketVoiceError.AUDIO_FAILED)
                    }
                }
            },
            onPlaybackStart = {
                labDiagnostic?.audioPlayRequested()
                labDiagnostic?.trace("PLAY_BEGIN", "state=${track.state} play=${track.playState}")
                try {
                    track.play()
                    labDiagnostic?.audioPlayOk()
                    labDiagnostic?.trace("PLAY_END", "state=${track.state} play=${track.playState} head=${trackPlaybackHead(track) ?: "—"}")
                } catch (error: Throwable) {
                    labDiagnostic?.audioPlayFailed("${error.javaClass.simpleName}: ${error.message ?: "no message"}")
                    labDiagnostic?.trace("AUDIO_FAILED_ORIGIN", "operation=AudioTrack.play exception=${error.javaClass.simpleName}")
                    throw error
                }
                firstAudioMs = SystemClock.elapsedRealtime() - requestStarted
                onState("REPRODUCIENDO")
                Log.i(TAG, "FIRST_AUDIO firstAudioMs=$firstAudioMs prebufferMs=${PocketAudioFormat.PREBUFFER_MS}")
            },
            transportMode = transportMode,
            wavWriter = activeCapture?.stagedTargets?.pcm16File?.let(::PocketWavWriter),
            floatWavWriter = activeCapture?.stagedTargets?.float32File?.let(::PocketFloatWavWriter),
            onCallbackTrace = { index, samples, pcmBytes, preBefore, preAfter, startedBefore, startedAfter,
                                firstWriteAttempt, lastWriteAttempt ->
                labDiagnostic?.callbackTrace(
                    PocketLabCallbackSnapshot(
                        index, samples, pcmBytes, preBefore, preAfter,
                        startedBefore, startedAfter, firstWriteAttempt, lastWriteAttempt
                    )
                )
            },
            onWriteTrace = { callbackStart, callbackEnd, arraySize, offset, length, end, result ->
                labDiagnostic?.writeRangeTrace(
                    PocketLabWriteRangeSnapshot(
                        callbackStart, callbackEnd, arraySize, offset, length, end, result
                    )
                )
            }
        )
        try {
            val runtime = native ?: throw PocketVoiceException(PocketVoiceError.MODELO_NO_CARGA)
            var callbackError: Throwable? = null
            val ok = runtime.synthesize(text, voice.fileName, NativePocketTts.AudioSink { samples ->
                if (cancelled.get() || Thread.currentThread().isInterrupted) return@AudioSink false
                if (samples.isEmpty()) return@AudioSink true
                labDiagnostic?.callback(samples.size)
                runCatching {
                    pipeline.accept(samples)
                    labDiagnostic?.pipelineAccepted(true)
                }.onFailure {
                    callbackError = it
                    labDiagnostic?.pipelineAccepted(false, it.javaClass.simpleName)
                }.isSuccess && !cancelled.get()
            }, NativePocketTts.NativeStageObserver { stage, value ->
                when (stage) {
                    PocketNativeStage.STREAM_START_REQUEST -> labDiagnostic?.mark(PocketLabStage.STREAM_START_REQUEST)
                    PocketNativeStage.STREAM_START_OK -> labDiagnostic?.mark(PocketLabStage.STREAM_START_OK)
                    PocketNativeStage.STREAM_START_FAILED -> labDiagnostic?.fail(PocketLabStage.STREAM_START_FAILED,
                        PocketVoiceError.SYNTHESIS_FAILED)
                    PocketNativeStage.STREAM_READ_FIRST -> labDiagnostic?.mark(PocketLabStage.STREAM_READ_FIRST,
                        "result=$value")
                }
            }.takeIf { labDiagnostic != null })
            if (cancelled.get()) throw CancellationException("Pocket TTS detenido")
            callbackError?.let { throw it }
            if (!ok || pipeline.inspector.snapshot().sampleCount <= 0L)
                throw PocketVoiceException(PocketVoiceError.SYNTHESIS_FAILED)
            labDiagnostic?.trace("SYNTHESIS_RETURN", "ok=$ok")
            val nativeMetrics = runtime.lastRunMetrics()
            labDiagnostic?.pipelineFinishRequested()
            labDiagnostic?.trace("PIPELINE_FINISH_BEGIN")
            try {
                pipeline.finish()
                labDiagnostic?.pipelineFinishOk()
                labDiagnostic?.trace("PIPELINE_FINISH_END", "writtenSamples=${pipeline.writtenSamples}")
                labDiagnostic?.trace("PCM_WRITE_COMPLETE", "writtenSamples=${pipeline.writtenSamples}")
            } catch (error: Throwable) {
                labDiagnostic?.pipelineFinishFailed("${error.javaClass.simpleName}: ${error.message ?: "no message"}")
                throw error
            }
            val generationMs = SystemClock.elapsedRealtime() - generationStarted
            labDiagnostic?.playbackDrainRequested()
            labDiagnostic?.trace("DRAIN_BEGIN", "writtenSamples=${pipeline.writtenSamples}")
            try {
                val drained = awaitPlaybackComplete(track, pipeline.writtenSamples, labDiagnostic)
                if (drained) {
                    labDiagnostic?.playbackDrainOk()
                    labDiagnostic?.trace("DRAIN_END", "played=${Integer.toUnsignedLong(track.playbackHeadPosition)}")
                } else {
                    val detail = "written=${pipeline.writtenSamples} played=${Integer.toUnsignedLong(track.playbackHeadPosition)}"
                    labDiagnostic?.playbackDrainTimeout(detail)
                    labDiagnostic?.trace("DRAIN_TIMEOUT", detail)
                    labDiagnostic?.trace("AUDIO_FAILED_ORIGIN", "operation=playbackDrain detail=$detail")
                    throw PocketVoiceException(PocketVoiceError.AUDIO_FAILED)
                }
            } catch (error: Throwable) {
                labDiagnostic?.playbackDrainFailed("${error.javaClass.simpleName}: ${error.message ?: "no message"}")
                throw error
            }
            if (cancelled.get()) throw CancellationException("Pocket TTS detenido")
            val diagnosticPublished = activeCapture?.publish() == true
            activeCapture?.targets?.let { targets ->
                val floatFile = targets.float32File
                val pcmFile = targets.pcm16File
                Log.i(TAG, "Pocket diagnostic generation: float32 exists=${floatFile.isFile} " +
                    "float32 bytes=${floatFile.length()} pcm16 exists=${pcmFile.isFile} " +
                    "pcm16 bytes=${pcmFile.length()} sameInference=$diagnosticPublished")
            }
            val totalMs = SystemClock.elapsedRealtime() - requestStarted
            val pcmStats = pipeline.inspector.snapshot()
            val underruns = (track.underrunCount - underrunsBefore).coerceAtLeast(0)
            val metrics = PocketMetrics(
                modelLoadMs = modelLoad,
                voiceProfileMs = null,
                firstAudioMs = firstAudioMs,
                generationMs = generationMs,
                totalMs = totalMs,
                audioDurationMs = pcmStats.durationMs,
                rtf = if (pcmStats.durationMs > 0) generationMs.toDouble() / pcmStats.durationMs else null,
                ramBeforeMiB = ramBefore,
                ramAfterMiB = Debug.getPss().toLong() / 1024L,
                pcmStats = pcmStats,
                floatStats = pipeline.floatInspector?.snapshot(),
                pcm16Stats = pipeline.pcm16Inspector?.snapshot(),
                conversionStats = pipeline.quantizationInspector?.snapshot(),
                audioUnderruns = underruns,
                diagnosticWav = activeCapture?.targets?.pcm16File?.takeIf { diagnosticPublished }?.absolutePath,
                diagnosticFloatWav = activeCapture?.targets?.float32File?.takeIf { diagnosticPublished }?.absolutePath,
                conditioningMs = nativeMetrics.conditioningMs,
                callbackCount = nativeMetrics.callbackCount,
                segmentCount = nativeMetrics.segmentCount,
                mimiFrames = nativeMetrics.mimiFrames,
                effectiveLsdSteps = runtimeConfig.lsdSteps,
                effectiveKvMode = runtimeConfig.kvMode,
                voice = voice.name,
                state = "COMPLETADO"
            )
            lastMetrics = metrics
            labDiagnostic?.mark(PocketLabStage.SYNTHESIS_COMPLETE)
            labDiagnostic?.trace("SYNTHESIS_COMPLETE", "pcmWriteComplete=true drain=${if (diagnosticPublished) "published" else "not-published"}")
            onState("COMPLETADO")
            Log.i(TAG, "SYNTHESIS_COMPLETED firstAudioMs=${metrics.firstAudioMs} generationMs=$generationMs " +
                "totalMs=$totalMs audioMs=${pcmStats.durationMs} rtf=${metrics.rtf} samples=${pcmStats.sampleCount} " +
                "min=${pcmStats.minimum} max=${pcmStats.maximum} peak=${pcmStats.peakAbsolute} rms=${pcmStats.rms} " +
                "dc=${pcmStats.dcOffset} nan=${pcmStats.nanCount} inf=${pcmStats.infiniteCount} " +
                "clipped=${pcmStats.clippedSamples} underruns=$underruns " +
                "conditioningMs=${nativeMetrics.conditioningMs} callbacks=${nativeMetrics.callbackCount} " +
                "segments=${nativeMetrics.segmentCount} mimiFrames=${nativeMetrics.mimiFrames} " +
                "kvMode=${runtimeConfig.kvMode} lsdSteps=${runtimeConfig.lsdSteps}")
            metrics
        } catch (cancelledError: CancellationException) {
            pipeline.abort()
            activeCapture?.abort()
            lastMetrics = lastMetrics.copy(state = "DETENIDO")
            onState("DETENIDO")
            throw cancelledError
        } catch (error: Throwable) {
            pipeline.abort()
            activeCapture?.abort()
            val code = (error as? PocketVoiceException)?.code ?: PocketVoiceError.SYNTHESIS_FAILED
            if (code == PocketVoiceError.AUDIO_FAILED) {
                labDiagnostic?.trace("AUDIO_FAILED_ORIGIN", "operation=propagated exception=${error.javaClass.simpleName}")
            }
            labDiagnostic?.preserveError(error)
            lastMetrics = lastMetrics.copy(state = "ERROR", error = code)
            onState("ERROR: ${code.name}")
            throw if (error is PocketVoiceException) error else PocketVoiceException(code, error)
        } finally {
            labDiagnostic?.trackStopRequested()
            labDiagnostic?.trace("TRACK_STOP_BEGIN")
            val stopResult = runCatching { track.stop() }
            if (stopResult.isSuccess) labDiagnostic?.trackStopOk()
            else labDiagnostic?.trackStopFailed(stopResult.exceptionOrNull()?.javaClass?.simpleName ?: "stop failed")
            labDiagnostic?.trace("TRACK_STOP_END", stopResult.exceptionOrNull()?.javaClass?.simpleName)
            labDiagnostic?.trace("TRACK_FLUSH_BEGIN")
            val flushResult = runCatching { track.flush() }
            labDiagnostic?.trace("TRACK_FLUSH_END", flushResult.exceptionOrNull()?.javaClass?.simpleName)
            labDiagnostic?.trace("TRACK_RELEASE_BEGIN")
            val releaseResult = runCatching { track.release() }
            if (releaseResult.isSuccess) labDiagnostic?.trackReleaseOk()
            else labDiagnostic?.trackReleaseFailed(releaseResult.exceptionOrNull()?.javaClass?.simpleName ?: "release failed")
            labDiagnostic?.trace("TRACK_RELEASE_END", releaseResult.exceptionOrNull()?.javaClass?.simpleName)
            labDiagnostic?.trace("ENGINE_FINALLY")
            if (audioTrack === track) audioTrack = null
            if (activeLabTrackId == labTrackId) activeLabTrackId = null
            pendingLabStopDiagnostic?.let { diagnostic ->
                diagnostic.previousTrack("release=OK")
                pendingLabStopDiagnostic = null
            }
        }
    }

    fun stop(labDiagnostic: PocketLabDiagnostic? = null) {
        labDiagnostic?.mark(PocketLabStage.STOP_LOCAL_VOICE,
            "audioTrack=${if (audioTrack == null) "NONE" else "PRESENT"}")
        pendingLabStopDiagnostic = labDiagnostic
        cancelled.set(true)
        native?.stop()
        // AudioTrack belongs to the Pocket dispatcher. Scheduling lifecycle
        // operations there prevents the UI thread from racing a blocking write
        // or releasing the track while aria-pocket-tts is using it.
        val track = audioTrack ?: return
        dispatcher.executor.execute {
            if (audioTrack !== track) return@execute
            var pause = "FAIL"
            var flush = "NOT_RUN"
            var stop = "NOT_RUN"
            runCatching {
                track.pause(); pause = "OK"
                track.flush(); flush = "OK"
                track.stop(); stop = "OK"
            }
            labDiagnostic?.previousTrack(
                "track=${activeLabTrackId ?: "—"} thread=${Thread.currentThread().name} " +
                    "pause=$pause flush=$flush stop=$stop release=DEFERRED cancelled=${cancelled.get()}"
            )
        }
    }

    suspend fun release() = withContext(dispatcher) {
        cancelled.set(true)
        native?.stop()
        audioTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
            if (audioTrack === track) audioTrack = null
        }
        releaseNative()
        lastMetrics = PocketMetrics(state = "INSTALADO")
    }

    private fun releaseNative(labDiagnostic: PocketLabDiagnostic? = null) {
        if (native != null) labDiagnostic?.mark(PocketLabStage.OLD_RUNTIME_RELEASE)
        native?.close()
        native = null
        loadedPackId = null
        loadedRuntimeConfig = null
    }

    /** Runs on the Pocket dispatcher, after any prior synthesis has yielded. */
    private fun releasePreviousLabAudioTrack(diagnostic: PocketLabDiagnostic) {
        val previous = audioTrack
        if (previous == null) {
            pendingLabStopDiagnostic = null
            return
        }
        var pause = "NOT_RUN"
        var flush = "NOT_RUN"
        var stop = "NOT_RUN"
        var release = "FAIL"
        runCatching { previous.pause(); pause = "OK" }
        runCatching { previous.flush(); flush = "OK" }
        runCatching { previous.stop(); stop = "OK" }
        runCatching { previous.release(); release = "OK" }
        if (audioTrack === previous) audioTrack = null
        pendingLabStopDiagnostic = null
        diagnostic.previousTrack(
            "track=${activeLabTrackId ?: "—"} thread=${Thread.currentThread().name} " +
                "pause=$pause flush=$flush stop=$stop release=$release"
        )
    }

    private fun createAudioTrack(labDiagnostic: PocketLabDiagnostic? = null): AudioTrack {
        labDiagnostic?.mark(PocketLabStage.CREATE_AUDIO_TRACK_REQUEST,
            "sr=${PocketAudioFormat.SAMPLE_RATE} channels=${AudioFormat.CHANNEL_OUT_MONO} encoding=${AudioFormat.ENCODING_PCM_16BIT}")
        val min = AudioTrack.getMinBufferSize(
            PocketModelSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        labDiagnostic?.audioTrackRequested(min, PocketAudioFormat.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) {
            labDiagnostic?.trace("AUDIO_FAILED_ORIGIN", "operation=getMinBufferSize result=$min")
            labDiagnostic?.audioTrackCreateFailed("minBufferSize=$min")
            throw PocketVoiceException(PocketVoiceError.AUDIO_FAILED)
        }
        return try {
            AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(PocketModelSpec.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            // Two seconds of capacity: one second is prefilled before play(), leaving
            // room for the next decoder chunk without blocking the native producer.
            .setBufferSizeInBytes(maxOf(min * 2, PocketAudioFormat.SAMPLE_RATE *
                PocketAudioFormat.PCM16_BYTES_PER_SAMPLE * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                .build().also { track ->
                    labDiagnostic?.audioTrackCreated(
                        track.state,
                        track.playState,
                        runCatching { track.bufferSizeInFrames }.getOrNull()
                    )
                }
        } catch (error: Throwable) {
            labDiagnostic?.trace("AUDIO_FAILED_ORIGIN", "operation=AudioTrack.Builder exception=${error.javaClass.simpleName}")
            labDiagnostic?.audioTrackCreateFailed(error.javaClass.simpleName)
            throw error
        }
    }

    private fun trackPlaybackHead(track: AudioTrack): Long? =
        runCatching { Integer.toUnsignedLong(track.playbackHeadPosition) }.getOrNull()

    private fun trackUnderruns(track: AudioTrack): Int? =
        runCatching { track.underrunCount }.getOrNull()

    private fun trackRoute(track: AudioTrack): String? = runCatching {
        track.routedDevice?.let { "type=${it.type} address=${it.address}" }
    }.getOrNull()

    private fun trackVolume(track: AudioTrack): Float? = runCatching {
        AudioTrack::class.java.getMethod("getVolume").invoke(track) as? Float
    }.getOrNull()

    private fun awaitPlaybackComplete(
        track: AudioTrack,
        writtenSamples: Long,
        labDiagnostic: PocketLabDiagnostic?
    ): Boolean {
        val initialHead = trackPlaybackHead(track)
        labDiagnostic?.trace("DRAIN_STATE", "state=${track.state} play=${track.playState} head=${initialHead ?: "—"}")
        if (writtenSamples <= 0L) return true
        val played = Integer.toUnsignedLong(track.playbackHeadPosition)
        if (PocketPlaybackDrain.isComplete(writtenSamples, played)) return true
        // A stopped/paused track with pending frames is not drained. Treating
        // this as success lets finally{} flush/release audible audio that was
        // never consumed by AudioTrack.
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return false
        val remaining = (writtenSamples - played).coerceAtLeast(0L)
        val timeoutAt = SystemClock.elapsedRealtime() +
            (remaining * 1_000L / PocketAudioFormat.SAMPLE_RATE) + PLAYBACK_DRAIN_GRACE_MS
        val startedAt = SystemClock.elapsedRealtime()
        var iterations = 0L
        var lastHead = played
        while (!cancelled.get() && SystemClock.elapsedRealtime() < timeoutAt) {
            iterations++
            val currentHead = Integer.toUnsignedLong(track.playbackHeadPosition)
            if (currentHead != lastHead || iterations == 1L) {
                lastHead = currentHead
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                labDiagnostic?.trace("HEAD_PROGRESS", "head=$currentHead target=$writtenSamples iter=$iterations elapsedMs=$elapsed", notify = false)
                labDiagnostic?.playbackHead(currentHead, iterations, elapsed, notify = false)
            }
            if (currentHead >= writtenSamples) return true
            SystemClock.sleep(10L)
        }
        if (!cancelled.get()) Log.w(TAG, "AUDIO_DRAIN_TIMEOUT writtenSamples=$writtenSamples " +
            "playedSamples=${Integer.toUnsignedLong(track.playbackHeadPosition)}")
        return false
    }

    override fun close() {
        stop()
        runCatching { native?.close() }
        native = null
        loadedPackId = null
        loadedRuntimeConfig = null
        dispatcher.close()
    }

    companion object {
        private const val TAG = "ARIA.PocketVoice"
        private const val PLAYBACK_DRAIN_GRACE_MS = 2_000L

        private fun writeResultName(result: Int): String = when (result) {
            -32 -> "DEAD_OBJECT_NATIVE"
            AudioTrack.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
            AudioTrack.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
            AudioTrack.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
            AudioTrack.ERROR -> "ERROR"
            0 -> "NO_PROGRESS"
            else -> "UNKNOWN"
        }
    }
}
