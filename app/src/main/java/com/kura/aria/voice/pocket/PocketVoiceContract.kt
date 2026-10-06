package com.kura.aria.voice.pocket

import com.kura.aria.emotion.AriaEmotion
import com.kura.aria.personality.ExpressionStyle
import java.io.File

internal object PocketModelSpec {
    const val ID = "pocket-spanish-fp32-v052"
    const val DISPLAY_NAME = "Pocket TTS · Español FP32"
    const val DOWNLOAD_URL = "https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine/releases/download/v0.5.2/PocketTTS-spanish-FP32.zip"
    const val DOWNLOAD_BYTES = 207_487_086L
    const val ARCHIVE_SHA256 = "f83dc41bd0d5c7634d385fdadef496881d398ca99d1886ff25d90cb59e384412"
    const val RUNTIME = "PocketTTS.cpp e801e7d + ONNX Runtime Android 1.20.0"
    const val PROFILE_RUNTIME_ID = "pocketcpp-e801e7d6c2692121a39e80ae525cb5265174a495-pocket-spanish-fp32-v052"
    const val SAMPLE_RATE = PocketAudioFormat.SAMPLE_RATE
    const val FORMAT_VERSION = 1

    val sharedFiles = listOf("mimi_encoder.onnx", "text_conditioner.onnx", "tokenizer.model")
    val fp32Files = listOf("flow_lm_flow.onnx", "flow_lm_main.onnx", "mimi_decoder.onnx")
}

internal enum class PocketInstallPhase {
    NOT_INSTALLED, DOWNLOADING, VERIFYING, INSTALLED, LOADING, READY, ERROR
}

internal data class PocketInstallStatus(
    val phase: PocketInstallPhase,
    val progress: Int = 0,
    val detail: String? = null
)

internal enum class LocalVoiceEngine { POCKET, ANDROID_FALLBACK }

internal data class PocketVoice(
    val id: String,
    val name: String,
    val fileName: String,
    val custom: Boolean = false,
    val profile: PocketVoiceProfile? = null
)

internal data class PocketPack(
    val id: String,
    val name: String,
    val languageTag: String,
    val precision: String,
    val temperature: Float,
    val lsdSteps: Int,
    val threads: Int,
    val root: File,
    val voices: List<PocketVoice>
) {
    val modelsDir: File get() = File(root, "models")
    val voicesDir: File get() = File(root, "voices")
}

internal data class PocketProsody(
    val emotion: AriaEmotion,
    val expressionStyle: ExpressionStyle,
    val temperature: Float
)

/**
 * Pocket exposes sampling temperature, not documented per-utterance pitch,
 * speed or emotion controls. We preserve ARIA's resolved performance context
 * without pretending those unsupported controls exist.
 */
internal object AriaPocketProsodyDirector {
    fun resolve(emotion: AriaEmotion, expression: ExpressionStyle, modelDefault: Float): PocketProsody =
        PocketProsody(emotion, expression, modelDefault.coerceIn(0f, 2f))
}

internal data class PocketMetrics(
    val modelLoadMs: Long? = null,
    val voiceProfileMs: Long? = null,
    val firstAudioMs: Long? = null,
    val generationMs: Long? = null,
    val totalMs: Long? = null,
    val audioDurationMs: Long? = null,
    val rtf: Double? = null,
    val ramBeforeMiB: Long? = null,
    val ramAfterMiB: Long? = null,
    val pcmStats: PocketPcmStats? = null,
    val audioUnderruns: Int? = null,
    val diagnosticWav: String? = null,
    val model: String = PocketModelSpec.DISPLAY_NAME,
    val voice: String? = null,
    val state: String = "NO INSTALADO",
    val error: PocketVoiceError? = null
)

internal enum class PocketVoiceError(val userMessage: String) {
    MODELO_NO_INSTALADO("Pocket TTS todavía no está instalado"),
    MODELO_CORRUPTO("El modelo Pocket está incompleto o corrupto"),
    DESCARGA_FALLIDA("No se pudo descargar Pocket TTS"),
    MODELO_NO_CARGA("Pocket TTS no pudo cargar el modelo"),
    VOZ_NO_DISPONIBLE("La voz seleccionada ya no está disponible"),
    SYNTHESIS_FAILED("Pocket TTS no pudo sintetizar esta respuesta"),
    AUDIO_FAILED("Android no pudo reproducir el audio de Pocket")
}

internal class PocketVoiceException(
    val code: PocketVoiceError,
    cause: Throwable? = null
) : IllegalStateException(code.userMessage, cause)

internal object PocketInstallValidator {
    fun requiredModelNames(precision: String): List<String> {
        val suffix = if (precision.equals("int8", true)) "_int8" else ""
        return PocketModelSpec.sharedFiles + listOf(
            "flow_lm_flow$suffix.onnx", "flow_lm_main$suffix.onnx", "mimi_decoder$suffix.onnx"
        )
    }

    fun hasRequiredFiles(pack: PocketPack): Boolean =
        pack.languageTag.startsWith("es", ignoreCase = true) &&
            pack.voices.isNotEmpty() &&
            requiredModelNames(pack.precision).all { File(pack.modelsDir, it).isFile && File(pack.modelsDir, it).length() > 0L } &&
            pack.voices.all { isVoiceAvailable(pack.voicesDir, it) }

    fun isVoiceAvailable(voicesDir: File, voice: PocketVoice): Boolean = voice.profile?.let {
        PocketVoiceProfileValidator.isInstalledProfileValid(it, voicesDir)
    } ?: File(voicesDir, voice.fileName).let { it.isFile && it.length() > 44L }

    fun safeArchivePath(root: File, entry: String): File {
        val target = File(root, entry).canonicalFile
        require(target != root.canonicalFile && target.path.startsWith(root.canonicalPath + File.separator)) {
            "Ruta ZIP inválida"
        }
        return target
    }
}

internal object PocketUiPolicy {
    fun canDownload(phase: PocketInstallPhase): Boolean =
        phase == PocketInstallPhase.NOT_INSTALLED || phase == PocketInstallPhase.ERROR

    fun canSynthesize(phase: PocketInstallPhase, voices: List<PocketVoice>): Boolean =
        phase in setOf(PocketInstallPhase.INSTALLED, PocketInstallPhase.READY) && voices.isNotEmpty()
}
