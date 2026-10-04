package com.kura.aria.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Official sherpa-onnx OfflineTts adapter for the selected Piper VITS model. */
class SherpaPiperBackend : PiperNeuralBackend {
    private val lifecycle = Any()
    private val generation = Mutex()
    private val cancelled = AtomicBoolean(false)
    @Volatile private var tts: OfflineTts? = null
    @Volatile var lastFirstAudioMs: Long? = null
        private set

    override suspend fun load(modelDirectory: File) = withContext(Dispatchers.Default) {
        synchronized(lifecycle) {
            if (tts != null) return@withContext
        }
        val config = OfflineTtsConfig.builder()
            .setModel(
                OfflineTtsModelConfig.builder()
                    .setNumThreads(2)
                    .setDebug(false)
                    .setVits(
                        OfflineTtsVitsModelConfig.builder()
                            .setModel(File(modelDirectory, PiperSpanishPrototype.MODEL_FILE).absolutePath)
                            .setTokens(File(modelDirectory, PiperSpanishPrototype.TOKENS_FILE).absolutePath)
                            .setDataDir(File(modelDirectory, PiperSpanishPrototype.DATA_DIR).absolutePath)
                            .build()
                    )
                    .build()
            )
            .setMaxNumSentences(1)
            .setSilenceScale(0.2f)
            .build()
        val created = OfflineTts(config)
        synchronized(lifecycle) {
            if (tts == null) tts = created else created.release()
        }
    }

    override suspend fun synthesize(request: SpeechSynthesisRequest): SpeechAudio =
        generation.withLock {
            withContext(Dispatchers.Default) {
                coroutineContext.ensureActive()
                val runtime = tts ?: error("sherpa-onnx aún no está cargado")
                cancelled.set(false)
                val started = System.nanoTime()
                var firstAudioMs: Long? = null
                val config = GenerationConfig().apply {
                    sid = 0
                    speed = request.speed.coerceIn(0.85f, 1.15f)
                    silenceScale = 0.2f
                }
                val audio = runtime.generateWithConfigAndCallback(
                    text = request.text,
                    config = config,
                    callback = { _ ->
                    if (cancelled.get()) {
                        0
                    } else {
                        if (firstAudioMs == null) firstAudioMs = (System.nanoTime() - started) / 1_000_000
                        1
                    }
                    }
                )
                if (cancelled.get()) throw CancellationException("Síntesis cancelada")
                val samples = audio.samples
                lastFirstAudioMs = firstAudioMs
                SpeechAudio(
                    pcm16 = ShortArray(samples.size) { index ->
                        (samples[index].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    },
                    sampleRateHz = audio.sampleRate,
                    synthesisMs = (System.nanoTime() - started) / 1_000_000,
                    firstAudioMs = firstAudioMs
                )
            }
        }

    override fun cancel() { cancelled.set(true) }

    override fun close() {
        synchronized(lifecycle) {
            cancelled.set(true)
            tts?.release()
            tts = null
        }
    }
}

/** Plays the returned mono PCM16 without creating a persistent player. */
class PiperAudioTrackPlayer {
    private val lock = Any()
    @Volatile private var activeTrack: AudioTrack? = null

    suspend fun play(audio: SpeechAudio): Long = withContext(Dispatchers.IO) {
        stop()
        val minBuffer = AudioTrack.getMinBufferSize(
            audio.sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(audio.sampleRateHz / 5)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(audio.sampleRateHz)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        synchronized(lock) { activeTrack = track }
        val started = System.nanoTime()
        try {
            track.play()
            var offset = 0
            while (offset < audio.pcm16.size) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val written = track.write(audio.pcm16, offset, audio.pcm16.size - offset, AudioTrack.WRITE_BLOCKING)
                check(written > 0) { "AudioTrack.write devolvió $written" }
                offset += written
            }
            track.stop()
            (System.nanoTime() - started) / 1_000_000
        } finally {
            synchronized(lock) { if (activeTrack === track) activeTrack = null }
            track.release()
        }
    }

    fun stop() {
        synchronized(lock) {
            activeTrack?.let { track ->
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.stop() }
                activeTrack = null
            }
        }
    }
}
