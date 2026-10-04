package com.kura.aria.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class PiperGeneratedSamples(val samples: FloatArray, val sampleRateHz: Int)

/** Small seam around the JNI object so the critical generation path is testable. */
internal interface PiperOfflineTtsRuntime {
    fun generate(text: String, speed: Float, silenceScale: Float): PiperGeneratedSamples
    fun release()
}

internal fun interface PiperOfflineTtsRuntimeFactory {
    fun create(modelDirectory: File): PiperOfflineTtsRuntime
}

/** Official sherpa-onnx OfflineTts adapter for the selected Piper VITS model. */
internal class SherpaPiperBackend(
    private val runtimeFactory: PiperOfflineTtsRuntimeFactory = PiperOfflineTtsRuntimeFactory(::SherpaOfflineTtsRuntime)
) : PiperNeuralBackend {
    private val lifecycle = Any()
    private val generation = Mutex()
    private val cancelled = AtomicBoolean(false)
    @Volatile private var tts: PiperOfflineTtsRuntime? = null
    @Volatile var lastFirstAudioMs: Long? = null
        private set

    override suspend fun load(modelDirectory: File) = withContext(Dispatchers.Default) {
        synchronized(lifecycle) {
            if (tts != null) return@withContext
        }
        val created = runtimeFactory.create(modelDirectory)
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
                // Do not use generateWithConfigAndCallback here. sherpa-onnx v1.13.8
                // captures a thread-local JNIEnv and a local callback reference in
                // native code; Piper may call it from a worker thread, causing a
                // fatal JNI abort that Kotlin try/catch cannot intercept.
                val profile = request.performanceProfile
                val pieces = if (profile == null) listOf(request.text) else
                    request.text.split(Regex("(?<=[.!?])\\s+")).map(String::trim).filter(String::isNotEmpty)
                val chunks = pieces.map { piece ->
                    coroutineContext.ensureActive()
                    if (cancelled.get()) throw CancellationException("Síntesis cancelada")
                    runtime.generate(
                        text = piece,
                        speed = (profile?.speed ?: request.speed).coerceIn(0.85f, 1.15f),
                        silenceScale = 0.2f
                    )
                }
                if (cancelled.get()) throw CancellationException("Síntesis cancelada")
                require(chunks.map { it.sampleRateHz }.distinct().size == 1) { "Frecuencia PCM inconsistente" }
                val rate = chunks.first().sampleRateHz
                val pauseSize = if (profile == null || chunks.size < 2) 0
                    else (rate * profile.pauseBetweenSentencesMs / 1000f).toInt()
                val totalSamples = chunks.sumOf { it.samples.size } + pauseSize * (chunks.size - 1).coerceAtLeast(0)
                val pcm = ShortArray(totalSamples)
                var offset = 0
                chunks.forEachIndexed { index, chunk ->
                    chunk.samples.forEach { sample -> pcm[offset++] = (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
                    if (index < chunks.lastIndex && pauseSize > 0) offset += pauseSize
                }
                lastFirstAudioMs = null // Non-streaming generation has no first-audio callback.
                SpeechAudio(pcm, rate, (System.nanoTime() - started) / 1_000_000, null)
            }
        }

    override fun cancel() { cancelled.set(true) }

    override fun close() {
        synchronized(lifecycle) {
            cancelled.set(true)
            runCatching { tts?.release() }
            tts = null
        }
    }

    private class SherpaOfflineTtsRuntime(modelDirectory: File) : PiperOfflineTtsRuntime {
        private val tts = OfflineTts(config = getOfflineTtsConfig(
            modelDir = modelDirectory.absolutePath,
            modelName = PiperSpanishPrototype.MODEL_FILE,
            acousticModelName = "",
            vocoder = "",
            voices = "",
            lexicon = "",
            dataDir = File(modelDirectory, PiperSpanishPrototype.DATA_DIR).absolutePath,
            dictDir = "",
            ruleFsts = "",
            ruleFars = "",
            numThreads = 2,
        ))

        override fun generate(text: String, speed: Float, silenceScale: Float): PiperGeneratedSamples {
            val config = GenerationConfig().apply {
                sid = 0
                this.speed = speed
                this.silenceScale = silenceScale
            }
            // This blocking overload passes a null callback through JNI.
            val audio = tts.generateWithConfig(text, config)
            return PiperGeneratedSamples(audio.samples, audio.sampleRate)
        }

        override fun release() = tts.release()
    }

}

/** Plays the returned mono PCM16 without creating a persistent player. */
internal class PiperAudioTrackPlayer {
    private val lock = Any()
    @Volatile private var activeTrack: AudioTrack? = null

    suspend fun play(audio: SpeechAudio): Long = withContext(Dispatchers.IO) {
        validateSpeechAudio(audio)
        stop()
        val minBuffer = AudioTrack.getMinBufferSize(
            audio.sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "AudioTrack rechazó el formato PCM ($minBuffer)" }
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
        check(track.state == AudioTrack.STATE_INITIALIZED) {
            track.release()
            "AudioTrack no pudo inicializarse"
        }
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
