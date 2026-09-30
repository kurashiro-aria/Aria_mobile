package com.kura.aria.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import com.qwen.tts.studio.engine.QwenEngine
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Optional, isolated offline synthesizer. Downloads models once; never sends text or audio. */
class QwenB2SpeechOutput(context: Context, private val onStatus: (String) -> Unit,
                         private val onSpeechFailure: (String) -> Unit) {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val modelDir = File(app.filesDir, "aria-voice-qwen3")
    private val generation = AtomicLong()
    private val finishedSynthesis = AtomicLong()
    private val speechTimeoutMs = 45_000L
    @Volatile private var closed = false
    @Volatile private var track: AudioTrack? = null
    private var engine: QwenEngine? = null // Accessed only by worker.
    private var profile: File? = null
    @Volatile private var preparing = false

    private data class ModelFile(val name: String, val bytes: Long)
    private val modelFiles = listOf(
        ModelFile("qwen-tokenizer-12hz-Q4_K_M.gguf", 254_974_752L),
        ModelFile("qwen-talker-0.6b-base-Q4_K_M.gguf", 628_905_056L),
    )
    private val baseUrl = "https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/"

    private fun status(message: String) = main.post { if (!closed) onStatus(message) }
    fun modelsReady(): Boolean = modelFiles.all { File(modelDir, it.name).length() == it.bytes }

    /** Called only after the user requests the 884 MB experimental voice package. */
    fun prepare(onReady: (Boolean) -> Unit) {
        if (preparing) { status("B2 sigue preparando sus archivos; espera el resultado"); return }
        preparing = true
        worker.execute {
            val result = runCatching {
                if (profile != null && engine != null) return@runCatching
                modelDir.mkdirs()
                modelFiles.forEach { download(it) }
                status("Archivos listos. Cargando el motor B2; puede tardar varios minutos")
                val native = engine ?: QwenEngine().also { engine = it }
                native.setBackendPreference(QwenEngine.BACKEND_CPU)
                native.setCpuThreads(4)
                if (!native.loadModels(modelDir.absolutePath, modelFiles[1].name))
                    error(native.getLastError() ?: "No pude cargar B2. Cierra otras aplicaciones e inténtalo de nuevo")
                if (native.getModelCapabilities()?.supportsCloning != true)
                    error("Este modelo no permite crear el perfil B2")
                val reference = File(modelDir, "aria-b2-reference.wav")
                if (!reference.exists()) app.assets.open("aria-b2-reference.wav").use { input ->
                    reference.outputStream().use { input.copyTo(it) }
                }
                val speaker = File(modelDir, "aria-b2-speaker.json")
                if (!speaker.exists() || speaker.length() == 0L) {
                    if (!native.extractSpeakerEmbedding(reference.absolutePath, speaker.absolutePath))
                        error(native.getLastError() ?: "No pude preparar la voz B2")
                }
                profile = speaker
            }
            preparing = false
            status(result.exceptionOrNull()?.let { "Error B2: ${it.message ?: it.javaClass.simpleName}" }
                ?: "Voz B2 lista para probar")
            main.post { if (!closed) onReady(result.isSuccess) }
        }
    }

    private fun download(model: ModelFile) {
        val target = File(modelDir, model.name)
        if (target.length() == model.bytes) return
        val part = File(modelDir, "${model.name}.part")
        if (part.length() > model.bytes) part.delete()
        var offset = part.length()
        val available = StatFs(modelDir.absolutePath).availableBytes
        require(available >= model.bytes - offset + 128_000_000L) {
            "Falta espacio para B2: libera al menos ${(model.bytes - offset + 128_000_000L - available) / 1_000_000 + 1} MB"
        }
        status("B2: ${model.name} · ${offset / 1_000_000} / ${model.bytes / 1_000_000} MB")
        val connection = URL(baseUrl + model.name + "?download=true").openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
        try {
            val code = connection.responseCode
            if (code !in listOf(200, 206)) error("Descarga de voz: HTTP $code. Revisa la conexión e inténtalo de nuevo")
            if (offset > 0 && code != 206) { part.delete(); offset = 0 }
            connection.inputStream.use { source ->
                FileOutputStream(part, offset > 0).buffered().use { sink ->
                    val buffer = ByteArray(128 * 1024)
                    var lastShown = offset
                    var lastTime = System.currentTimeMillis()
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        offset += count
                        val now = System.currentTimeMillis()
                        if (offset - lastShown >= 5_000_000 || now - lastTime >= 2_000) {
                            lastShown = offset
                            lastTime = now
                            status("B2: ${model.name} · ${offset / 1_000_000} / ${model.bytes / 1_000_000} MB")
                        }
                    }
                }
            }
            if (part.length() != model.bytes) error("Descarga incompleta de ${model.name}: ${part.length() / 1_000_000} / ${model.bytes / 1_000_000} MB; toca Probar B2 para continuar")
            if (!part.renameTo(target)) error("No pude guardar ${model.name}")
        } finally { connection.disconnect() }
    }

    fun speak(text: String) {
        if (closed || text.isBlank()) return
        val ticket = generation.incrementAndGet()
        track?.pause(); track?.flush()
        status("B2: preparando audio…")
        main.postDelayed({
            if (!closed && ticket == generation.get() && finishedSynthesis.get() != ticket) {
                generation.incrementAndGet()
                onStatus("Error de voz B2: tardó más de 45 s; usando voz Android")
                onSpeechFailure("La síntesis B2 tardó demasiado")
            }
        }, speechTimeoutMs)
        worker.execute {
            if (closed || ticket != generation.get()) return@execute
            val native = engine
            val embedding = profile
            if (native == null || embedding == null) {
                failSpeech(ticket, "B2 aún no está preparada")
                return@execute
            }
            var lastProgress = 0
            native.setProgressCallback { tokens, _ ->
                if (tokens - lastProgress >= 12) {
                    lastProgress = tokens
                    if (ticket == generation.get()) status("B2: generando audio · $tokens fragmentos")
                }
            }
            val result = runCatching {
                native.synthesize(text, speakerEmbeddingPath = embedding.absolutePath,
                    params = QwenEngine.NativeParams(languageId = 2054, maxAudioTokens = 256))
            }.getOrElse {
                native.setProgressCallback(null)
                failSpeech(ticket, it.message ?: it.javaClass.simpleName)
                return@execute
            }
            native.setProgressCallback(null)
            if (!result.success || result.audio == null) {
                failSpeech(ticket, result.errorMsg ?: "sin audio")
                return@execute
            }
            if (closed || ticket != generation.get()) return@execute
            finishedSynthesis.set(ticket)
            status("B2 generó audio en ${result.timeMs / 1000.0} s")
            runCatching { play(result.audio, result.sampleRate, ticket) }
                .onFailure { failSpeech(ticket, it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun failSpeech(ticket: Long, reason: String) {
        main.post { if (!closed && generation.compareAndSet(ticket, ticket + 1)) {
            onStatus("Error de voz B2: $reason")
            onSpeechFailure(reason)
        } }
    }

    private fun play(audio: FloatArray, sampleRate: Int, ticket: Long) {
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val buffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT).coerceAtLeast(16384)
        val player = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(format).setBufferSizeInBytes(buffer).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = player
        try {
            player.play()
            var cursor = 0
            while (cursor < audio.size && ticket == generation.get() && !closed) {
                val wrote = player.write(audio, cursor, minOf(4096, audio.size - cursor), AudioTrack.WRITE_BLOCKING)
                if (wrote <= 0) break
                cursor += wrote
            }
        } finally {
            track = null
            player.stop()
            player.release()
        }
    }

    fun stop() {
        generation.incrementAndGet()
        runCatching { track?.pause(); track?.flush() }
    }
    fun close() {
        closed = true
        stop()
        worker.execute { engine?.close(); engine = null }
        worker.shutdown()
    }
}
