package com.kura.aria.voice.pocket

import android.content.Context
import android.net.Uri
import com.kura.aria.emotion.AriaEmotion
import com.kura.aria.personality.ExpressionStyle

internal class PocketVoiceController(context: Context) : AutoCloseable {
    val models = PocketModelManager(context.applicationContext)
    val preferences = PocketVoicePreferences(context.applicationContext)
    private val engine = PocketVoiceEngine(context.applicationContext)

    val installed: Boolean get() = models.installedPack() != null
    val voices: List<PocketVoice> get() = models.voices()
    val selectedVoice: PocketVoice?
        get() = PocketVoiceSelection.selected(voices, preferences.selectedVoiceId)
    val metrics: PocketMetrics get() = engine.lastMetrics

    fun selectVoice(id: String) {
        require(voices.any { it.id == id })
        preferences.selectedVoiceId = id
    }

    fun importVoice(uri: Uri): PocketVoice = models.importVoice(uri).also { selectVoice(it.id) }

    suspend fun load(onState: (String) -> Unit = {}): Long {
        val pack = models.installedPack()
            ?: throw PocketVoiceException(PocketVoiceError.MODELO_NO_INSTALADO)
        return engine.load(pack, onState)
    }

    suspend fun speak(
        text: String,
        emotion: AriaEmotion,
        expression: ExpressionStyle,
        onState: (String) -> Unit = {}
    ): PocketMetrics {
        val pack = models.installedPack()
            ?: throw PocketVoiceException(PocketVoiceError.MODELO_NO_INSTALADO)
        val voice = selectedVoice ?: throw PocketVoiceException(PocketVoiceError.VOZ_NO_DISPONIBLE)
        return engine.synthesize(pack, voice, text, emotion, expression, onState = onState)
    }

    suspend fun runAudioDiagnostic(onState: (String) -> Unit = {}): PocketMetrics {
        val pack = models.installedPack()
            ?: throw PocketVoiceException(PocketVoiceError.MODELO_NO_INSTALADO)
        val voice = selectedVoice ?: throw PocketVoiceException(PocketVoiceError.VOZ_NO_DISPONIBLE)
        return engine.synthesize(
            pack,
            voice,
            AUDIO_DIAGNOSTIC_TEXT,
            AriaEmotion.NEUTRAL,
            ExpressionStyle.NATURAL,
            engine.diagnosticFile(),
            onState
        )
    }

    fun stop() = engine.stop()
    suspend fun release() = engine.release()
    override fun close() = engine.close()

    companion object {
        const val AUDIO_DIAGNOSTIC_TEXT =
            "Hola Kura, soy Aria. Esta es una prueba de calidad de audio."
    }
}
