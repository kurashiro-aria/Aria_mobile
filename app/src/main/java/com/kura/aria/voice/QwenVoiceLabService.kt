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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

private data class PreparedQwenVoiceProfile(
    val file: File,
    val summary: QwenIclPromptSummary,
    val cacheHit: Boolean,
    val cacheLoadMs: Long,
    val buildColdMs: Long?,
    val cacheWriteMs: Long?,
)

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
    @Volatile private var state = QwenLabState.NOT_INSTALLED
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
            QwenVoiceLabContract.ACTION_TEST_SHORT -> requestSynthesis(QwenVoiceLabContract.SHORT_TEXT)
            QwenVoiceLabContract.ACTION_TEST_LONG -> requestSynthesis(QwenVoiceLabContract.LONG_TEXT)
            QwenVoiceLabContract.ACTION_STOP -> stopSynthesis()
            QwenVoiceLabContract.ACTION_RELEASE -> {
                stopSynthesis()
                submit("release") { releaseEngine(); state = if (store.isInstalled()) QwenLabState.INSTALLED else QwenLabState.NOT_INSTALLED; publish(detail = "Modelo liberado de RAM") }
            }
        }
        return START_NOT_STICKY
    }

    @Synchronized
    private fun requestSynthesis(text: String) {
        if (state != QwenLabState.READY) {
            Log.w(LOG_TAG, "event=synthesis_rejected state=${state.name}")
            publish(detail = "La síntesis requiere el motor en estado LISTO")
            return
        }
        state = QwenLabState.SYNTHESIZING
        Log.i(LOG_TAG, "event=synthesis_stage stage=SYNTHESIS_REQUESTED")
        publish(detail = "SYNTHESIS_REQUESTED · SINTETIZANDO")
        submit("synthesis") { synthesize(text) }
    }

    private fun submit(stage: String, operation: () -> Unit) {
        worker.execute {
            try {
                operation()
            } catch (oom: OutOfMemoryError) {
                safeFailure(stage, oom)
                releaseEngine()
            } catch (cancelled: java.util.concurrent.CancellationException) {
                state = if (store.isInstalled()) QwenLabState.INSTALLED else QwenLabState.NOT_INSTALLED
                publish(detail = "Operación cancelada")
            } catch (error: Throwable) {
                safeFailure(stage, error)
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
        if (!store.isInstalled()) fail(QwenLabFailureCode.MODEL_FILE_MISSING, "Faltan archivos del modelo Qwen")
        state = QwenLabState.VERIFYING
        publish(detail = "Verificando SHA-256")
        val verifyStarted = SystemClock.elapsedRealtime()
        if (!store.verifyInstalled()) fail(QwenLabFailureCode.MODEL_SHA_INVALID, "El modelo no superó la verificación SHA-256")
        val verifyMs = SystemClock.elapsedRealtime() - verifyStarted
        state = QwenLabState.LOADING
        publish(detail = "Preparando perfil ARIA-B5C V1")
        val profileStarted = SystemClock.elapsedRealtime()
        val preparedProfile = prepareVoiceProfile()
        val profileMs = SystemClock.elapsedRealtime() - profileStarted

        publish(detail = if (preparedProfile.cacheHit) {
            "Perfil ARIA-B5C cargado desde caché"
        } else {
            "Perfil ARIA-B5C creado y guardado"
        })

        publish(detail = "Cargando Qwen3-TTS 0.6B Base Q8_0")
        val loadStarted = SystemClock.elapsedRealtime()
        val native = QwenEngine()
        native.setBackendPreference(QwenEngine.BACKEND_CPU)
        native.setCpuThreads(max(2, Runtime.getRuntime().availableProcessors().coerceAtMost(6)))
        try {
            if (!native.loadModels(store.modelDirectory.absolutePath, Qwen06BAndroidPackage.TALKER_FILE)) {
                fail(QwenLabFailureCode.LOAD_MODEL_FAILED, native.loadFailureDetail())
            }
            val capabilities = native.getModelCapabilities()
            if (capabilities?.supportsCloning != true || capabilities.modelKind != 1) {
                fail(QwenLabFailureCode.GGUF_INCOMPATIBLE, "El GGUF no expone Base con voice cloning")
            }
            if (preparedProfile.summary.speakerEmbeddingValues != capabilities.speakerEmbeddingDim ||
                preparedProfile.summary.referenceCodebooks != ICL_CODEBOOKS) {
                profileCache().invalidate()
                fail(QwenLabFailureCode.GGUF_INCOMPATIBLE, "El perfil ICL no coincide con el modelo Qwen fijado")
            }
        } catch (error: Throwable) {
            native.close()
            throw error
        }
        engine = native
        promptFile = preparedProfile.file
        metrics = metrics.copy(
            verifyMs = verifyMs,
            modelLoadMs = SystemClock.elapsedRealtime() - loadStarted,
            voiceProfileLoadMs = profileMs,
            profileBuildColdMs = preparedProfile.buildColdMs,
            profileCacheWriteMs = preparedProfile.cacheWriteMs,
            profileCacheLoadMs = preparedProfile.cacheLoadMs,
            profileCacheHit = preparedProfile.cacheHit,
            profileCacheBytes = preparedProfile.file.length(),
            storageBytes = store.storageBytes() + preparedProfile.file.length(),
            peakRamMib = currentPssMib(),
        )
        state = QwenLabState.READY
        Log.i(LOG_TAG, "event=synthesis_stage stage=READY")
        publish(detail = "Listo · streaming ICL real")
    }

    private fun prepareVoiceProfile(): PreparedQwenVoiceProfile {
        val profile = AriaB5CVoiceProfile.V1
        val directory = File(filesDir, "qwen-voice-lab/profiles/${profile.id}-v${profile.version}").apply { mkdirs() }
        val reference = File(directory, profile.referenceAsset)
        if (!reference.isFile || sha256(reference) != profile.referenceSha256) {
            reference.delete()
            assets.open(profile.referenceAsset).use { input ->
                reference.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            if (sha256(reference) != profile.referenceSha256) {
                fail(QwenLabFailureCode.VOICE_PROFILE_FAILED, "ARIA-B5C MASTER no superó SHA-256")
            }
        }
        val referenceFormat = QwenReferenceWavInspector.inspect(reference)
        if (!referenceFormat.isSupportedByPinnedRuntime) {
            fail(QwenLabFailureCode.VOICE_PROFILE_FAILED, "Formato WAV de ARIA-B5C no compatible con el runtime fijado")
        }
        if (referenceFormat.channels != 1 || referenceFormat.sampleRate != 24_000) {
            fail(QwenLabFailureCode.VOICE_PROFILE_FAILED, "ARIA-B5C debe ser WAV mono a 24 kHz")
        }
        val cache = profileCache(directory)
        val cacheStarted = SystemClock.elapsedRealtime()
        val lookup = cache.lookup()
        if (lookup.canUse && lookup.prompt != null && lookup.summary != null) {
            val validator = QwenEngine()
            val nativeValid = try {
                validator.validateIclPrompt(lookup.prompt.absolutePath)
            } finally {
                validator.close()
            }
            if (nativeValid) {
                var cacheWriteMs: Long? = null
                if (lookup.status == QwenIclCacheStatus.LEGACY_HIT) {
                    val writeStarted = SystemClock.elapsedRealtime()
                    cache.promoteLegacy(lookup.prompt, lookup.summary)
                    cacheWriteMs = SystemClock.elapsedRealtime() - writeStarted
                }
                return PreparedQwenVoiceProfile(
                    file = lookup.prompt,
                    summary = lookup.summary,
                    cacheHit = true,
                    cacheLoadMs = SystemClock.elapsedRealtime() - cacheStarted,
                    buildColdMs = null,
                    cacheWriteMs = cacheWriteMs,
                )
            }
            Log.w(LOG_TAG, "event=icl_cache_rejected reason=native_parser")
        } else if (lookup.status != QwenIclCacheStatus.MISS) {
            Log.w(LOG_TAG, "event=icl_cache_rejected reason=${lookup.detail}")
        }
        cache.invalidate()

        val temporary = File(directory, ".${profile.promptFileName}.tmp")
        temporary.delete()
        val buildStarted = SystemClock.elapsedRealtime()
        val encoder = QwenEngine()
        encoder.setBackendPreference(QwenEngine.BACKEND_CPU)
        encoder.setCpuThreads(max(2, Runtime.getRuntime().availableProcessors().coerceAtMost(6)))
        try {
            if (!encoder.loadIclPromptEncoder(store.modelDirectory.absolutePath, Qwen06BAndroidPackage.TALKER_FILE)) {
                fail(QwenLabFailureCode.ICL_FAILED, encoder.iclFailureDetail("No se pudo cargar el encoder ICL"))
            }
            if (!encoder.extractIclPrompt(reference.absolutePath, profile.referenceTranscript, temporary.absolutePath)) {
                fail(QwenLabFailureCode.ICL_FAILED, encoder.iclFailureDetail("No se pudo extraer el perfil ICL"))
            }
            if (!encoder.validateIclPrompt(temporary.absolutePath)) {
                fail(QwenLabFailureCode.ICL_FAILED, "El runtime rechazó el perfil ICL recién creado")
            }
        } finally {
            encoder.close()
        }
        val buildMs = SystemClock.elapsedRealtime() - buildStarted
        if (!temporary.isFile || temporary.length() <= 0L) fail(QwenLabFailureCode.ICL_FAILED, "El perfil ICL quedó vacío")
        val writeStarted = SystemClock.elapsedRealtime()
        val installed = try {
            cache.install(temporary)
        } catch (error: Throwable) {
            throw QwenLabException(QwenLabFailureCode.STORAGE_ACCESS_FAILED, "No se pudo guardar el perfil ICL", error)
        }
        val writeMs = SystemClock.elapsedRealtime() - writeStarted
        return PreparedQwenVoiceProfile(
            file = installed.prompt ?: fail(QwenLabFailureCode.ICL_FAILED, "La caché ICL no quedó disponible"),
            summary = installed.summary ?: fail(QwenLabFailureCode.ICL_FAILED, "La caché ICL quedó incompleta"),
            cacheHit = false,
            cacheLoadMs = SystemClock.elapsedRealtime() - cacheStarted - buildMs - writeMs,
            buildColdMs = buildMs,
            cacheWriteMs = writeMs,
        )
    }

    private fun profileCache(
        directory: File = File(filesDir, "qwen-voice-lab/profiles/${AriaB5CVoiceProfile.V1.id}-v${AriaB5CVoiceProfile.V1.version}"),
    ): QwenIclProfileCache = QwenIclProfileCache(
        directory = directory,
        promptFileName = AriaB5CVoiceProfile.V1.promptFileName,
        identity = QwenIclCacheIdentity.forProfile(AriaB5CVoiceProfile.V1),
    )

    private fun synthesize(text: String) {
        val native = engine
        val prompt = promptFile
        require(state == QwenLabState.SYNTHESIZING && native != null && prompt?.isFile == true) {
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
        val generationStarted = AtomicBoolean(false)
        inferenceNumber += 1
        publish(detail = "SYNTHESIS_REQUESTED · inferencia $inferenceNumber")

        val timeout = watchdog.schedule({
            if (generation.compareAndSet(ticket, ticket + 1L)) {
                publishFailure("timeout", "La síntesis superó 150 s; cerrando solo el proceso Qwen")
                Process.killProcess(Process.myPid())
            }
        }, SYNTHESIS_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        native.setProgressCallback { _, _ ->
            if (generationStarted.compareAndSet(false, true)) {
                Log.i(LOG_TAG, "event=synthesis_stage stage=GENERATION_STARTED inference=$inferenceNumber")
                publish(detail = "GENERATION_STARTED · inferencia $inferenceNumber")
            }
        }
        Log.i(LOG_TAG, "event=synthesis_stage stage=JNI_ENTERED inference=$inferenceNumber")
        publish(detail = "JNI_ENTERED · inferencia $inferenceNumber")
        val result = try {
            native.synthesizeWithIclPromptStreaming(
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
                        Log.i(LOG_TAG, "event=synthesis_stage stage=FIRST_PCM inference=$inferenceNumber")
                        it.play()
                        Log.i(LOG_TAG, "event=synthesis_stage stage=PLAYING inference=$inferenceNumber")
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
                            publish(detail = "PLAYING · primer PCM reproducido")
                        }
                    }
                    peakRam = max(peakRam, currentPssMib())
                    ticket == generation.get()
                } catch (error: Throwable) {
                    callbackFailure.set(error.javaClass.simpleName)
                    false
                }
            }
        } finally {
            native.setProgressCallback(null)
            timeout.cancel(false)
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
        Log.i(LOG_TAG, "event=synthesis_stage stage=COMPLETED inference=$inferenceNumber")
        publish(detail = "Inferencia $inferenceNumber completa")
    }

    private fun stopSynthesis() {
        generation.incrementAndGet()
        stopAudioOnly()
        if (engine != null && promptFile?.isFile == true) state = QwenLabState.READY
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
        append("T_model_load_cold: ").append(metrics.modelLoadMs.ms()).append('\n')
        append("T_profile_build_cold: ").append(metrics.profileBuildColdMs.ms()).append('\n')
        append("T_profile_cache_write: ").append(metrics.profileCacheWriteMs.ms()).append('\n')
        append("T_profile_cache_load: ").append(metrics.profileCacheLoadMs.ms()).append('\n')
        append("Perfil ICL: ").append(when (metrics.profileCacheHit) {
            true -> "WARM RUN · CACHE HIT"
            false -> "FIRST RUN · CACHE MISS"
            null -> "—"
        }).append(" · ").append(metrics.profileCacheBytes?.let { "$it bytes" } ?: "—").append('\n')
        append("T_voice_profile_total: ").append(metrics.voiceProfileLoadMs.ms()).append('\n')
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

    private fun safeFailure(stage: String, error: Throwable) {
        val failure = QwenVoiceLabDiagnostics.classify(stage, error)
        state = QwenLabState.ERROR
        Log.e(LOG_TAG, "event=lab_failure stage=$stage code=${failure.code} type=${failure.throwableType}")
        publish(detail = "${failure.code}: ${failure.safeDetail}. ARIA y Piper siguen disponibles.")
    }

    private fun QwenEngine.loadFailureDetail(): String {
        val nativeError = getLastError().orEmpty()
        return when {
            nativeError.contains("vocoder", ignoreCase = true) || nativeError.contains("tokenizer", ignoreCase = true) ->
                "No se pudo cargar tokenizer/códec Qwen"
            nativeError.contains("open TTS model", ignoreCase = true) -> "No se pudo abrir el GGUF talker"
            else -> "El runtime nativo rechazó el modelo Qwen"
        }
    }

    private fun QwenEngine.iclFailureDetail(fallback: String): String {
        val nativeError = getLastError().orEmpty()
        return when {
            nativeError.contains("reference audio", ignoreCase = true) -> "El runtime no pudo leer el WAV ARIA-B5C"
            nativeError.contains("speaker encoder", ignoreCase = true) -> "No se pudo cargar el encoder de voz B5C"
            nativeError.contains("speech tokenizer", ignoreCase = true) -> "No se pudo cargar el encoder del códec Qwen"
            else -> fallback
        }
    }

    private fun publishFailure(stage: String, reason: String) {
        state = QwenLabState.ERROR
        Log.e(LOG_TAG, "event=lab_failure stage=$stage type=Timeout")
        publish(detail = reason)
    }

    private fun sha256(file: File): String = file.inputStream().buffered().use { sha256(it) }

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
        const val ICL_CODEBOOKS = 16
    }
}
