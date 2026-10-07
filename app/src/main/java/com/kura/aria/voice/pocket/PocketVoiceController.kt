package com.kura.aria.voice.pocket

import android.content.Context
import android.net.Uri
import com.kura.aria.emotion.AriaEmotion
import com.kura.aria.personality.ExpressionStyle

internal class PocketVoiceController(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    val models = PocketModelManager(appContext)
    val preferences = PocketVoicePreferences(appContext)
    private val engine = PocketVoiceEngine(appContext)
    private val isolationGate = PocketIsolationGate()

    val installed: Boolean get() = models.installedPack() != null
    val voices: List<PocketVoice> get() = models.voices().also { available ->
        if (preferences.selectedVoiceId == null) {
            (available.firstOrNull { it.id == PocketBuiltinVoiceProfile.ID } ?: available.firstOrNull())
                ?.let { preferences.selectedVoiceId = it.id }
        }
    }
    val selectedVoice: PocketVoice?
        get() = PocketVoiceSelection.selected(voices, preferences.selectedVoiceId)
    val metrics: PocketMetrics get() = engine.lastMetrics

    fun selectVoice(id: String) {
        require(voices.any { it.id == id })
        preferences.selectedVoiceId = id
    }

    fun importVoice(uri: Uri): PocketVoice = models.importVoice(uri).also { selectVoice(it.id) }

    fun importPocketVoiceProfile(uri: Uri, profile: PocketVoiceProfile): PocketVoice =
        models.importPocketVoiceProfile(uri, profile).also { selectVoice(it.id) }

    suspend fun load(onState: (String) -> Unit = {}): Long {
        val pack = models.installedPack()
            ?: throw PocketVoiceException(PocketVoiceError.MODELO_NO_INSTALADO)
        return engine.load(pack, onState = onState)
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
        return engine.synthesize(
            pack,
            voice,
            text,
            emotion,
            expression,
            onState = onState,
            diagnosticTargets = PocketDiagnosticWav.forNormalChat(appContext.cacheDir)
        )
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
            onState = onState,
            diagnosticTargets = PocketDiagnosticWav.forVoiceTest(appContext.cacheDir)
        )
    }

    suspend fun runIsolationLab(
        variant: PocketIsolationVariant,
        transportMode: PocketTransportMode = PocketTransportMode.CURRENT_WRITES,
        diagnostic: PocketLabDiagnostic = PocketLabDiagnostic(variant),
        onState: (String) -> Unit = {}
    ): PocketMetrics {
        check(isolationGate.tryEnter()) { "Ya hay una prueba Pocket en ejecución" }
        try {
            val pack = models.installedPack()
                ?: throw PocketVoiceException(PocketVoiceError.MODELO_NO_INSTALADO)
            val plan = PocketIsolationPlan.create(pack, voices, variant)
            val capture = PocketIsolationLabWav.beginCapture(appContext.cacheDir, variant)
            diagnostic.transportMode(transportMode)
            diagnostic.mark(PocketLabStage.LAB_CAPTURE_READY)
            return engine.synthesize(
                pack = pack,
                voice = plan.voice,
                text = plan.text,
                emotion = AriaEmotion.NEUTRAL,
                expression = ExpressionStyle.NATURAL,
                onState = onState,
                diagnosticCapture = capture,
                transportMode = transportMode,
                runtimeConfig = plan.runtimeConfig,
                labDiagnostic = diagnostic
            )
        } finally {
            isolationGate.leave()
        }
    }

    val isolationLabRunning: Boolean get() = isolationGate.isRunning

    fun stop(diagnostic: PocketLabDiagnostic? = null) = engine.stop(diagnostic)
    suspend fun release() = engine.release()
    override fun close() = engine.close()

    companion object {
        const val AUDIO_DIAGNOSTIC_TEXT =
            "Hola Kura, soy Aria. Esta es una prueba de calidad de audio."
    }
}
