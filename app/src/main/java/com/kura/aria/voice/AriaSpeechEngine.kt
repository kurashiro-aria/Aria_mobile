package com.kura.aria.voice

import com.kura.aria.emotion.AriaEmotion
import com.kura.aria.personality.ExpressionStyle
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Common boundary for local speech engines.
 *
 * The existing Android TTS adapter remains the automatic fallback.  Neural
 * engines implement this boundary so a future runtime can be added without
 * coupling MainActivity to ONNX/sherpa classes.
 */
interface AriaSpeechEngine {
    val descriptor: SpeechEngineDescriptor
    val state: SpeechEngineState

    suspend fun initialize(): SpeechEngineState
    suspend fun synthesize(request: SpeechSynthesisRequest): Result<SpeechAudio>
    fun cancel()
    fun close()
}

enum class SpeechEngineState {
    UNINITIALIZED,
    LOADING,
    READY,
    UNAVAILABLE,
    ERROR,
    CLOSED
}

data class SpeechEngineDescriptor(
    val id: String,
    val displayName: String,
    val language: String,
    val model: String,
    val offline: Boolean,
    val supportsNativeEmotion: Boolean,
    val modelSizeBytes: Long? = null,
    val limitation: String? = null
)

data class SpeechSynthesisRequest(
    val text: String,
    val emotion: AriaEmotion = AriaEmotion.NEUTRAL,
    val expressionStyle: ExpressionStyle = ExpressionStyle.NATURAL,
    val speed: Float = 1f
)

data class SpeechAudio(
    val pcm16: ShortArray,
    val sampleRateHz: Int,
    val synthesisMs: Long
)

/**
 * Stable descriptor for the one model selected for the first prototype.
 * The files are intentionally external to Git/APK until their licensing and
 * size have been approved.
 */
object PiperSpanishPrototype {
    const val MODEL_ID = "es_MX-claude-high"
    const val MODEL_FILE = "es_MX-claude-high.onnx"
    const val TOKENS_FILE = "tokens.txt"
    const val DATA_DIR = "espeak-ng-data"
    const val MODEL_BYTES = 63_122_309L
    const val SAMPLE_RATE_HZ = 22_050

    val descriptor = SpeechEngineDescriptor(
        id = "piper.sherpa-onnx.$MODEL_ID",
        displayName = "Piper Spanish (sherpa-onnx)",
        language = "es-MX",
        model = MODEL_ID,
        offline = true,
        supportsNativeEmotion = false,
        modelSizeBytes = MODEL_BYTES,
        limitation = "VITS/Piper aporta prosodia aprendida, pero no expone controles neuronales de emoción; speed es el único control de inferencia seguro."
    )
}

interface PiperNeuralBackend {
    suspend fun load(modelDirectory: File)
    suspend fun synthesize(request: SpeechSynthesisRequest): SpeechAudio
    fun cancel()
    fun close()
}

/**
 * Adapter for sherpa-onnx's OfflineTts/Piper runtime.
 *
 * This commit deliberately does not bundle native libraries or a 63 MB model.
 * Until a reviewed ARM64 runtime is supplied, the engine reports UNAVAILABLE
 * and the caller must keep using Android TTS.  A fake backend can be injected
 * in tests without requiring Android, ONNX, or audio hardware.
 */
class PiperNeuralSpeechEngine(
    private val modelDirectory: File,
    private val backend: PiperNeuralBackend? = null
) : AriaSpeechEngine {
    override val descriptor: SpeechEngineDescriptor = PiperSpanishPrototype.descriptor
    override var state: SpeechEngineState = SpeechEngineState.UNINITIALIZED
        private set

    var lastError: String? = null
        private set
    var modelLoadMs: Long? = null
        private set

    override suspend fun initialize(): SpeechEngineState {
        if (state == SpeechEngineState.CLOSED) return state
        if (state == SpeechEngineState.READY) return state
        state = SpeechEngineState.LOADING
        val model = File(modelDirectory, PiperSpanishPrototype.MODEL_FILE)
        val tokens = File(modelDirectory, PiperSpanishPrototype.TOKENS_FILE)
        val dataDir = File(modelDirectory, PiperSpanishPrototype.DATA_DIR)
        if (!model.isFile || !tokens.isFile || !dataDir.isDirectory) {
            lastError = "Modelo no instalado; se requieren ${PiperSpanishPrototype.MODEL_FILE}, ${PiperSpanishPrototype.TOKENS_FILE} y ${PiperSpanishPrototype.DATA_DIR}/."
            state = SpeechEngineState.UNAVAILABLE
            return state
        }
        val runtime = backend
        if (runtime == null) {
            lastError = "Runtime sherpa-onnx ARM64 aún no está incluido en la aplicación."
            state = SpeechEngineState.UNAVAILABLE
            return state
        }
        return try {
            val started = System.nanoTime()
            runtime.load(modelDirectory)
            modelLoadMs = (System.nanoTime() - started) / 1_000_000
            lastError = null
            state = SpeechEngineState.READY
            state
        } catch (error: CancellationException) {
            state = SpeechEngineState.UNINITIALIZED
            throw error
        } catch (error: Throwable) {
            lastError = error.message ?: error.javaClass.simpleName
            state = SpeechEngineState.ERROR
            state
        }
    }

    override suspend fun synthesize(request: SpeechSynthesisRequest): Result<SpeechAudio> {
        if (state != SpeechEngineState.READY || backend == null) {
            return Result.failure(IllegalStateException(lastError ?: "Motor neuronal no disponible"))
        }
        val spokenText = spokenTextForCloud(request.text)
        if (spokenText.isBlank()) {
            return Result.failure(IllegalArgumentException("No hay texto hablable"))
        }
        return try {
            Result.success(backend.synthesize(request.copy(
                text = spokenText,
                speed = request.speed.coerceIn(0.85f, 1.15f)
            )))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    override fun cancel() { backend?.cancel() }

    override fun close() {
        if (state == SpeechEngineState.CLOSED) return
        backend?.close()
        state = SpeechEngineState.CLOSED
    }
}

/** The normal automatic fallback remains the existing Android TTS adapter. */
enum class SpeechEngineSelection { NEURAL, ANDROID_TTS_FALLBACK }

fun selectSpeechEngine(neuralState: SpeechEngineState): SpeechEngineSelection =
    if (neuralState == SpeechEngineState.READY) SpeechEngineSelection.NEURAL
    else SpeechEngineSelection.ANDROID_TTS_FALLBACK
