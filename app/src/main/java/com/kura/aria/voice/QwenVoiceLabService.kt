package com.kura.aria.voice

import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Debug
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.qwen.tts.studio.engine.QwenEngine
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * Experimental Qwen host. The manifest places it in :qwen_voice so a native
 * abort/OOM cannot terminate ARIA's UI/brain process.
 */
class QwenVoiceLabService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val generation = AtomicLong()
    private val activeTrack = AtomicReference<AudioTrack?>()
    private lateinit var store: QwenModelStore
    private var engine: QwenEngine? = null // Worker thread only.
    private var promptFile: File? = null // Worker thread only.
    private var state = QwenLabState.NOT_INSTALLED
    private var inferenceNumber = 0
    private var metrics = QwenVoiceLabMetrics()

    override fun onCreate() {
        super.onCreate()
        store = QwenModelStore(File(filesDir, "qwen-voice-lab/model-0.6b-q8"))
        state = if (store.isInstalled()) QwenLabState.INSTALLED else QwenLabState.NOT_INSTALLED
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            QwenVoiceLabContract.ACTION_QUERY -> publish()
            QwenVoiceLabContract.ACTION_DOWNLOAD -> submit("download") { download() }
            QwenVoiceLabContract.ACTION_CANCEL_DOWNLOAD -> {
                store.cancel()
                publish(detail = "Cancelando descarga…")
            }
            QwenVoiceLabContract.ACTION_DELETE_MODEL -> {
                stopSynthesis()
                submit("delete") { releaseEngine(); store.delete(); deleteProfile(); state = QwenLabState.NOT_INSTALLED; metrics = QwenVoiceLabMetrics(); publish() }
            }
            QwenVoiceLabContract.ACTION_LOAD_MODEL -> submit("load") { load() }
            QwenVoiceLabContract.ACTION_TEST_SHORT -> submit("synthesis") { synthesize(QwenVoiceLabContract.SHORT_TEXT) }
            QwenVoiceLabContract.ACTION_TEST_LONG -> submit("synthesis") { synthesize(QwenVoiceLabContract.LONG_TEXT) }
            QwenVoiceLabContract.ACTION_STOP -> stopSynthesis()
            QwenVoiceLabContract.ACTION_RELEASE -> {
                stopSynthesis()
                submit("release") { releaseEngine(); state = if (store.isInstalled()) QwenLabState.INSTALLED else QwenLabState.NOT_INSTALLED; publish(detail = "Modelo liberado de RAM") }
            }
        }
        return START_NOT_STICKY
    }

    private fun submit(stage: String, operation: () -> Unit) {
        worker.execute {
            try {
                operation()
            } catch (oom: OutOfMemoryError) {
                safeFailure(stage, "Memoria insuficiente")
                releaseEngine()
            } catch (cancelled: java.util.concurrent.CancellationException) {
                state = if (store.isInstalled()) QwenLabState.INSTALLED else QwenLabState.NOT_INSTALLED
                publish(detail = "Operación cancelada")
            } catch (error: Throwable) {
                safeFailure(stage, error.javaClass.simpleName)
            }
        }
    }

    private fun download() {
        if (store.isInstalled()) {
            state = QwenLabState.INSTALLED
            publish(detail = "Modelo ya instalado; no se descargó de nuevo")
            return
        }
        state = QwenLabState.DOWNLOADING
        val report = store.install { progress ->
            state = progress.state
            publish(
                detail = progress.detail,
                progress = progress.downloadedBytes.toDouble() / progress.totalBytes.coerceAtLeast(1L),
            )
        }
        metrics = metrics.copy(
            downloadMs = report.downloadMs,
            verifyMs = report.verifyMs,
            downloadedBytes = report.downloadedBytes,
            storageBytes = report.storageBytes,
        )
        state = QwenLabState.INSTALLED
        publish(detail = if (report.reusedExistingInstall) "Modelo existente reutilizado" else "Descarga verificada")
    }

    private fun load() {
        if (engine != null && promptFile?.isFile == true) {
            state = QwenLabState.READY
            publish(detail = "Modelo ya residente en RAM")
            return
        }
        require(store.isInstalled()) { "Modelo no instalado" }
        state = QwenLabState.VERIFYING
        publish(detail = "Verificando SHA-256")
        val verifyStarted = SystemClock.elapsedRealtime()
        check(store.verifyInstalled()) { "Modelo corrupto o SHA-256 inválido" }
        val verifyMs = SystemClock.elapsedRealtime() - verifyStarted
        state = QwenLabState.LOADING
        publish(detail = "Preparando perfil ARIA-B5C V1")
        val profileStarted = SystemClock.elapsedRealtime()
        val preparedPrompt = prepareVoiceProfile()
        val profileMs = SystemClock.elapsedRealtime() - profileStarted

        publish(detail = "Cargando Qwen3-TTS 0.6B Base Q8_0")
        val loadStarted = SystemClock.elapsedRealtime()
        val native = QwenEngine()
        native.setBackendPreference(QwenEngine.BACKEND_CPU)
        native.setCpuThreads(max(2, Runtime.getRuntime().availableProcessors().coerceAtMost(6)))
        try {
            check(native.loadModels(store.modelDirectory.absolutePath, Qwen06BAndroidPackage.TALKER_FILE)) {
                native.getLastError() ?: "Fallo de carga nativa"
            }
            val capabilities = native.getModelCapabilities()
            check(capabilities?.supportsCloning == true && capabilities.modelKind == 1) {
                "El modelo cargado no es Base con voice cloning"
            }
        } catch (error: Throwable) {
            native.close()
            throw error
        }
        engine = native
        promptFile = preparedPrompt
        metrics = metrics.copy(
            verifyMs = verifyMs,
            modelLoadMs = SystemClock.elapsedRealtime() - loadStarted,
            voiceProfileLoadMs = profileMs,
            storageBytes = store.storageBytes() + preparedPrompt.length(),
            peakRamMib = currentPssMib(),
        )
        state = QwenLabState.READY
        publish(detail = "Listo · streaming ICL real")
    }

    private fun prepareVoiceProfile(): File {
        val profile = AriaB5CVoiceProfile.V1
        val directory = File(filesDir, "qwen-voice-lab/profiles/${profile.id}-v${profile.version}").apply { mkdirs() }
        val reference = File(directory, profile.referenceAsset)
        if (!reference.isFile || sha256(reference) != profile.referenceSha256) {
            reference.delete()
            assets.open(profile.referenceAsset).use { input ->
                reference.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            check(sha256(reference) == profile.referenceSha256) { "ARIA-B5C MASTER no supera SHA-256" }
        }
        val prompt = File(directory, profile.promptFileName)
        val marker = File(directory, ".profile-v1")
        val expectedMarker = "${profile.referenceSha256}|${sha256(profile.referenceTranscript)}|${Qwen06BAndroidPackage.MODEL_REVISION}"
        if (prompt.isFile && prompt.length() > 0L && marker.readTextOrNull() == expectedMarker) return prompt

        val temporary = File(directory, ".${profile.promptFileName}.tmp")
        temporary.delete()
        val encoder = QwenEngine()
        encoder.setBackendPreference(QwenEngine.BACKEND_CPU)
        encoder.setCpuThreads(max(2, Runtime.getRuntime().availableProcessors().coerceAtMost(6)))
        try {
            check(encoder.loadIclPromptEncoder(store.modelDirectory.absolutePath, Qwen06BAndroidPackage.TALKER_FILE)) {
                encoder.getLastError() ?: "No se pudo cargar el encoder ICL"
            }
            check(encoder.extractIclPrompt(reference.absolutePath, profile.referenceTranscript, temporary.absolutePath)) {
                encoder.getLastError() ?: "No se pudo extraer el perfil ICL"
            }
        } finally {
            encoder.close()
        }
        check(temporary.isFile && temporary.length() > 0L) { "Perfil ICL vacío" }
        prompt.delete()
        check(temporary.renameTo(prompt)) { "No se pudo guardar el perfil ICL" }
        marker.writeText(expectedMarker)
        return prompt
    }

    private fun synthesize(text: String) {
        val native = engine
        val prompt = promptFile
        require(state == QwenLabState.READY && native != null && prompt?.isFile == true) {
            "Primero carga el modelo"
        }
        stopAudioOnly()
        val ticket = generation.incrementAndGet()
        val started = SystemClock.elapsedRealtime()
        var firstAudioMs: Long? = null
        var sampleRate = 0
        var samplesWritten = 0L
        var peakRam = currentPssMib()
        var player: AudioTrack? = null
        val callbackFailure = AtomicReference<String?>(null)
        inferenceNumber += 1
        publish(detail = "Generando inferencia $inferenceNumber…")

        watchdog.schedule({
            if (generation.compareAndSet(ticket, ticket + 1L)) {
                publishFailure("timeout", "La síntesis superó 150 s; cerrando solo el proceso Qwen")
                Process.killProcess(Process.myPid())
            }
        }, SYNTHESIS_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        val result = native.synthesizeWithIclPromptStreaming(
            text = text,
            iclPromptPath = prompt.absolutePath,
            params = QwenEngine.NativeParams(languageId = 2050, maxAudioTokens = 256),
            chunkSeconds = 1.0f,
            leftContextSeconds = 2.0f,
            collectAudio = false,
        ) { audio, rate, _, _, _, _, _, _, _, _ ->
            if (ticket != generation.get()) return@synthesizeWithIclPromptStreaming false
            if (!validPcm(audio, rate)) {
                callbackFailure.set("PCM inválido")
                return@synthesizeWithIclPromptStreaming false
            }
            try {
                val output = player ?: createTrack(rate).also {
                    player = it
                    activeTrack.set(it)
                    sampleRate = rate
                    it.play()
                }
                if (rate != sampleRate) {
                    callbackFailure.set("Sample rate cambió durante streaming")
                    return@synthesizeWithIclPromptStreaming false
                }
                var cursor = 0
                while (cursor < audio.size && ticket == generation.get()) {
                    val count = output.write(audio, cursor, audio.size - cursor, AudioTrack.WRITE_BLOCKING)
                    if (count <= 0) {
                        callbackFailure.set("AudioTrack rechazó PCM")
                        return@synthesizeWithIclPromptStreaming false
                    }
                    cursor += count
                    samplesWritten += count
                    if (firstAudioMs == null) {
                        firstAudioMs = SystemClock.elapsedRealtime() - started
                        publish(detail = "Primer audio reproducido")
                    }
                }
                peakRam = max(peakRam, currentPssMib())
                ticket == generation.get()
            } catch (error: Throwable) {
                callbackFailure.set(error.javaClass.simpleName)
                false
            }
        }
        val generationMs = SystemClock.elapsedRealtime() - started
        callbackFailure.get()?.let { error(it) }
        check(result.success && firstAudioMs != null && samplesWritten > 0L) {
            result.errorMsg ?: "Qwen no produjo audio"
        }
        waitForPlayback(player, samplesWritten, sampleRate, ticket)
        stopAudioOnly()
        val durationMs = samplesWritten * 1_000L / sampleRate.coerceAtLeast(1)
        metrics = metrics.copy(
            firstAudioMs = firstAudioMs,
            generationMs = generationMs,
            totalMs = SystemClock.elapsedRealtime() - started,
            peakRamMib = max(peakRam, currentPssMib()),
            audioDurationMs = durationMs,
            inferenceNumber = inferenceNumber,
        )
        state = QwenLabState.READY
        publish(detail = "Inferencia $inferenceNumber completa")
    }

    private fun stopSynthesis() {
        generation.incrementAndGet()
        stopAudioOnly()
        publish(detail = "Detenido")
    }

    private fun stopAudioOnly() {
        activeTrack.getAndSet(null)?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun releaseEngine() {
        engine?.let { runCatching { it.setProgressCallback(null); it.close() } }
        engine = null
        promptFile = null
        inferenceNumber = 0
        stopAudioOnly()
    }

    private fun deleteProfile() {
        File(filesDir, "qwen-voice-lab/profiles").deleteRecursively()
    }

    private fun validPcm(audio: FloatArray, sampleRate: Int): Boolean =
        audio.isNotEmpty() && sampleRate in 8_000..96_000 && audio.all { it.isFinite() && it in -1.25f..1.25f }

    private fun createTrack(sampleRate: Int): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        check(min > 0) { "AudioTrack no admite el formato PCM" }
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(min, sampleRate / 2 * Float.SIZE_BYTES))
            .build()
    }

    private fun waitForPlayback(track: AudioTrack?, samples: Long, sampleRate: Int, ticket: Long) {
        if (track == null || sampleRate <= 0) return
        val deadline = SystemClock.elapsedRealtime() + minOf(10_000L, samples * 1_000L / sampleRate + 1_000L)
        while (ticket == generation.get() && SystemClock.elapsedRealtime() < deadline &&
            track.playbackHeadPosition.toLong() < samples) Thread.sleep(20L)
    }

    private fun currentPssMib(): Int {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss / 1024
    }

    private fun publish(detail: String? = null, progress: Double? = null) {
        val intent = Intent(QwenVoiceLabContract.ACTION_STATUS).setPackage(packageName)
            .putExtra(QwenVoiceLabContract.EXTRA_STATE, state.name)
            .putExtra(QwenVoiceLabContract.EXTRA_DETAIL, detail)
            .putExtra(QwenVoiceLabContract.EXTRA_METRICS, formatMetrics())
        progress?.let { intent.putExtra(QwenVoiceLabContract.EXTRA_PROGRESS, it.coerceIn(0.0, 1.0)) }
        sendBroadcast(intent)
    }

    private fun formatMetrics(): String = buildString {
        append("Runtime: qwen3-tts.cpp ").append(Qwen06BAndroidPackage.RUNTIME_COMMIT.take(8))
        append(" · ARM64 CPU · Q8_0\n")
        append("Perfil: ").append(AriaB5CVoiceProfile.V1.displayName).append(" · full ICL\n")
        append("Streaming real: SÍ\n")
        append("T_download: ").append(metrics.downloadMs.ms()).append('\n')
        append("T_verify: ").append(metrics.verifyMs.ms()).append('\n')
        append("T_model_load: ").append(metrics.modelLoadMs.ms()).append('\n')
        append("T_voice_profile_load: ").append(metrics.voiceProfileLoadMs.ms()).append('\n')
        append("T_first_audio: ").append(metrics.firstAudioMs.ms())
            .append(" · ").append(metrics.firstAudioClassification).append('\n')
        append("T_generation: ").append(metrics.generationMs.ms()).append('\n')
        append("T_total: ").append(metrics.totalMs.ms()).append('\n')
        append("RAM pico aprox.: ").append(metrics.peakRamMib?.let { "$it MiB" } ?: "—").append('\n')
        append("Audio: ").append(metrics.audioDurationMs.ms()).append('\n')
        append("RTF: ").append(metrics.realTimeFactor?.let { String.format(Locale.US, "%.2f", it) } ?: "—").append('\n')
        append("Inferencia: ").append(metrics.inferenceNumber?.toString() ?: "—")
    }

    private fun Long?.ms(): String = this?.let { "$it ms" } ?: "—"

    private fun safeFailure(stage: String, reason: String) {
        state = QwenLabState.ERROR
        Log.e(LOG_TAG, "event=lab_failure stage=$stage type=$reason")
        publish(detail = "Error en $stage: $reason. ARIA y Piper siguen disponibles.")
    }

    private fun publishFailure(stage: String, reason: String) {
        state = QwenLabState.ERROR
        Log.e(LOG_TAG, "event=lab_failure stage=$stage type=Timeout")
        publish(detail = reason)
    }

    private fun sha256(file: File): String = file.inputStream().buffered().use { sha256(it) }

    private fun sha256(text: String): String = sha256(text.byteInputStream())

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use {
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    override fun onDestroy() {
        store.cancel()
        generation.incrementAndGet()
        releaseEngine()
        worker.shutdownNow()
        watchdog.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val LOG_TAG = "ARIA.QwenVoiceLab"
        const val SYNTHESIS_TIMEOUT_SECONDS = 150L
    }
}
