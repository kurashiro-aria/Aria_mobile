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
import kotlin.coroutines.coroutineContext

internal class PocketVoiceEngine(context: Context) : AutoCloseable {
    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "aria-pocket-tts").apply { priority = Thread.NORM_PRIORITY }
    }.asCoroutineDispatcher()
    @Volatile private var native: NativePocketTts? = null
    @Volatile private var loadedPackId: String? = null
    @Volatile private var loadedRuntimeConfig: PocketRuntimeConfig? = null
    @Volatile private var audioTrack: AudioTrack? = null
    private val cancelled = AtomicBoolean(false)
    @Volatile var lastMetrics = PocketMetrics()
        private set

    suspend fun load(
        pack: PocketPack,
        runtimeConfig: PocketRuntimeConfig = PocketRuntimeConfig.normal(pack),
        onState: (String) -> Unit = {}
    ): Long = withContext(dispatcher) {
        if (native != null && loadedPackId == pack.id && loadedRuntimeConfig == runtimeConfig) return@withContext 0L
        onState("CARGANDO")
        releaseNative()
        val started = SystemClock.elapsedRealtime()
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
            loadedPackId = pack.id
            loadedRuntimeConfig = runtimeConfig
            val elapsed = SystemClock.elapsedRealtime() - started
            lastMetrics = lastMetrics.copy(modelLoadMs = elapsed, state = "LISTO")
            onState("LISTO")
            elapsed
        } catch (error: Throwable) {
            releaseNative()
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
        runtimeConfig: PocketRuntimeConfig = PocketRuntimeConfig.normal(pack)
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
        cancelled.set(false)
        val requestStarted = SystemClock.elapsedRealtime()
        val ramBefore = Debug.getPss().toLong() / 1024L
        val modelLoad = load(pack, runtimeConfig, onState)
        coroutineContext.ensureActive()
        val prosody = AriaPocketProsodyDirector.resolve(emotion, expression, pack.temperature)
        Log.i(TAG, "SYNTHESIS_REQUESTED emotion=${prosody.emotion} expression=${prosody.expressionStyle} " +
            "temperature=${prosody.temperature} chars=${text.length}")
        onState("SINTETIZANDO")
        var firstAudioMs: Long? = null
        val generationStarted = SystemClock.elapsedRealtime()
        val track = createAudioTrack()
        audioTrack = track
        val underrunsBefore = track.underrunCount
        val pipeline = PocketPcm16Pipeline(
            writer = { bytes, offset, length ->
                track.write(bytes, offset, length, AudioTrack.WRITE_BLOCKING).also { written ->
                    if (written <= 0) throw PocketVoiceException(PocketVoiceError.AUDIO_FAILED)
                }
            },
            onPlaybackStart = {
                track.play()
                firstAudioMs = SystemClock.elapsedRealtime() - requestStarted
                onState("REPRODUCIENDO")
                Log.i(TAG, "FIRST_AUDIO firstAudioMs=$firstAudioMs prebufferMs=${PocketAudioFormat.PREBUFFER_MS}")
            },
            wavWriter = activeCapture?.stagedTargets?.pcm16File?.let(::PocketWavWriter),
            floatWavWriter = activeCapture?.stagedTargets?.float32File?.let(::PocketFloatWavWriter)
        )
        try {
            val runtime = native ?: throw PocketVoiceException(PocketVoiceError.MODELO_NO_CARGA)
            var callbackError: Throwable? = null
            val ok = runtime.synthesize(text, voice.fileName, NativePocketTts.AudioSink { samples ->
                if (cancelled.get() || Thread.currentThread().isInterrupted) return@AudioSink false
                if (samples.isEmpty()) return@AudioSink true
                runCatching { pipeline.accept(samples) }
                    .onFailure { callbackError = it }
                    .isSuccess && !cancelled.get()
            })
            if (cancelled.get()) throw CancellationException("Pocket TTS detenido")
            callbackError?.let { throw it }
            if (!ok || pipeline.inspector.snapshot().sampleCount <= 0L)
                throw PocketVoiceException(PocketVoiceError.SYNTHESIS_FAILED)
            val nativeMetrics = runtime.lastRunMetrics()
            pipeline.finish()
            val generationMs = SystemClock.elapsedRealtime() - generationStarted
            awaitPlaybackComplete(track, pipeline.writtenSamples)
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
            lastMetrics = lastMetrics.copy(state = "ERROR", error = code)
            onState("ERROR: ${code.name}")
            throw if (error is PocketVoiceException) error else PocketVoiceException(code, error)
        } finally {
            runCatching { track.stop() }
            runCatching { track.flush() }
            runCatching { track.release() }
            if (audioTrack === track) audioTrack = null
        }
    }

    fun stop() {
        cancelled.set(true)
        native?.stop()
        audioTrack?.let { runCatching { it.pause(); it.flush(); it.stop() } }
    }

    suspend fun release() = withContext(dispatcher) {
        stop()
        releaseNative()
        lastMetrics = PocketMetrics(state = "INSTALADO")
    }

    private fun releaseNative() {
        native?.close()
        native = null
        loadedPackId = null
        loadedRuntimeConfig = null
    }

    private fun createAudioTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            PocketModelSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) throw PocketVoiceException(PocketVoiceError.AUDIO_FAILED)
        return AudioTrack.Builder()
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
            .build()
    }

    private fun awaitPlaybackComplete(track: AudioTrack, writtenSamples: Long) {
        if (writtenSamples <= 0L || track.playState != AudioTrack.PLAYSTATE_PLAYING) return
        val played = Integer.toUnsignedLong(track.playbackHeadPosition)
        val remaining = (writtenSamples - played).coerceAtLeast(0L)
        val timeoutAt = SystemClock.elapsedRealtime() +
            (remaining * 1_000L / PocketAudioFormat.SAMPLE_RATE) + PLAYBACK_DRAIN_GRACE_MS
        while (!cancelled.get() && SystemClock.elapsedRealtime() < timeoutAt) {
            if (Integer.toUnsignedLong(track.playbackHeadPosition) >= writtenSamples) return
            SystemClock.sleep(10L)
        }
        if (!cancelled.get()) Log.w(TAG, "AUDIO_DRAIN_TIMEOUT writtenSamples=$writtenSamples " +
            "playedSamples=${Integer.toUnsignedLong(track.playbackHeadPosition)}")
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
    }
}
