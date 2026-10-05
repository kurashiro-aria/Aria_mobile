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

internal class PocketVoiceEngine(@Suppress("UNUSED_PARAMETER") context: Context) : AutoCloseable {
    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "aria-pocket-tts").apply { priority = Thread.NORM_PRIORITY }
    }.asCoroutineDispatcher()
    @Volatile private var native: NativePocketTts? = null
    @Volatile private var loadedPackId: String? = null
    @Volatile private var audioTrack: AudioTrack? = null
    private val cancelled = AtomicBoolean(false)
    @Volatile var lastMetrics = PocketMetrics()
        private set

    suspend fun load(pack: PocketPack, onState: (String) -> Unit = {}): Long = withContext(dispatcher) {
        if (native != null && loadedPackId == pack.id) return@withContext 0L
        onState("CARGANDO")
        releaseNative()
        val started = SystemClock.elapsedRealtime()
        try {
            native = NativePocketTts(
                pack.modelsDir.absolutePath,
                pack.voicesDir.absolutePath,
                pack.precision,
                pack.temperature,
                pack.lsdSteps,
                pack.threads.coerceIn(1, 8),
                250,
                50
            )
            loadedPackId = pack.id
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
        onState: (String) -> Unit = {}
    ): PocketMetrics = withContext(dispatcher) {
        require(text.isNotBlank())
        if (!PocketInstallValidator.hasRequiredFiles(pack))
            throw PocketVoiceException(PocketVoiceError.MODELO_CORRUPTO)
        if (!java.io.File(pack.voicesDir, voice.fileName).isFile)
            throw PocketVoiceException(PocketVoiceError.VOZ_NO_DISPONIBLE)

        cancelled.set(false)
        val requestStarted = SystemClock.elapsedRealtime()
        val ramBefore = Debug.getPss().toLong() / 1024L
        val modelLoad = load(pack, onState)
        coroutineContext.ensureActive()
        val prosody = AriaPocketProsodyDirector.resolve(emotion, expression, pack.temperature)
        Log.i(TAG, "SYNTHESIS_REQUESTED emotion=${prosody.emotion} expression=${prosody.expressionStyle} " +
            "temperature=${prosody.temperature} chars=${text.length}")
        onState("SINTETIZANDO")
        var firstAudioMs: Long? = null
        var totalSamples = 0L
        val generationStarted = SystemClock.elapsedRealtime()
        val track = createAudioTrack()
        audioTrack = track
        try {
            val runtime = native ?: throw PocketVoiceException(PocketVoiceError.MODELO_NO_CARGA)
            val ok = runtime.synthesize(text, voice.fileName, NativePocketTts.AudioSink { samples ->
                if (cancelled.get() || Thread.currentThread().isInterrupted) return@AudioSink false
                if (samples.isEmpty()) return@AudioSink true
                if (firstAudioMs == null) {
                    track.play()
                    firstAudioMs = SystemClock.elapsedRealtime() - requestStarted
                    onState("REPRODUCIENDO")
                    Log.i(TAG, "FIRST_PCM samples=${samples.size} firstAudioMs=$firstAudioMs")
                }
                val bytes = PocketPcm.floatToPcm16(samples)
                var offset = 0
                while (offset < bytes.size && !cancelled.get()) {
                    val written = track.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) throw PocketVoiceException(PocketVoiceError.AUDIO_FAILED)
                    offset += written
                }
                totalSamples += samples.size
                !cancelled.get()
            })
            if (cancelled.get()) throw CancellationException("Pocket TTS detenido")
            if (!ok || totalSamples <= 0L) throw PocketVoiceException(PocketVoiceError.SYNTHESIS_FAILED)
            val generationMs = SystemClock.elapsedRealtime() - generationStarted
            val totalMs = SystemClock.elapsedRealtime() - requestStarted
            val durationMs = totalSamples * 1000L / PocketModelSpec.SAMPLE_RATE
            val metrics = PocketMetrics(
                modelLoadMs = if (modelLoad > 0) modelLoad else lastMetrics.modelLoadMs,
                voiceProfileMs = null,
                firstAudioMs = firstAudioMs,
                generationMs = generationMs,
                totalMs = totalMs,
                audioDurationMs = durationMs,
                rtf = if (durationMs > 0) generationMs.toDouble() / durationMs else null,
                ramBeforeMiB = ramBefore,
                ramAfterMiB = Debug.getPss().toLong() / 1024L,
                voice = voice.name,
                state = "COMPLETADO"
            )
            lastMetrics = metrics
            onState("COMPLETADO")
            Log.i(TAG, "SYNTHESIS_COMPLETED firstAudioMs=${metrics.firstAudioMs} generationMs=$generationMs " +
                "totalMs=$totalMs audioMs=$durationMs rtf=${metrics.rtf}")
            metrics
        } catch (cancelledError: CancellationException) {
            lastMetrics = lastMetrics.copy(state = "DETENIDO")
            onState("DETENIDO")
            throw cancelledError
        } catch (error: Throwable) {
            val code = (error as? PocketVoiceException)?.code ?: PocketVoiceError.SYNTHESIS_FAILED
            lastMetrics = lastMetrics.copy(state = "ERROR", error = code)
            onState("ERROR: ${code.name}")
            throw if (error is PocketVoiceException) error else PocketVoiceException(code, error)
        } finally {
            runCatching { track.stop() }
            track.flush()
            track.release()
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
            .setBufferSizeInBytes(maxOf(min * 2, PocketModelSpec.SAMPLE_RATE / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .build()
    }

    override fun close() {
        stop()
        runCatching { native?.close() }
        native = null
        loadedPackId = null
        dispatcher.close()
    }

    companion object { private const val TAG = "ARIA.PocketVoice" }
}
