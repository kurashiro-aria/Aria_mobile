package com.kura.aria.voice.pocket

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal enum class PocketIsolationVariant(
    val label: String,
    val kvMode: PocketKvMode,
    val lsdSteps: Int,
    val floatFileName: String,
    val pcmFileName: String
) {
    A_CONTROL(
        "A — CONTROL KV ACTUAL / LSD1",
        PocketKvMode.CURRENT_SINGLE_BUFFER,
        1,
        "pocket_lab_A_control_f32.wav",
        "pocket_lab_A_control_pcm16.wav"
    ),
    B_KV_SEPARATE(
        "B — KV SEPARADO / LSD1",
        PocketKvMode.SEPARATE_INPUT_OUTPUT,
        1,
        "pocket_lab_B_kv_separate_f32.wav",
        "pocket_lab_B_kv_separate_pcm16.wav"
    ),
    C_KV_SEPARATE_LSD3(
        "C — KV SEPARADO / LSD3",
        PocketKvMode.SEPARATE_INPUT_OUTPUT,
        3,
        "pocket_lab_C_kv_separate_lsd3_f32.wav",
        "pocket_lab_C_kv_separate_lsd3_pcm16.wav"
    );

    fun runtimeConfig(): PocketRuntimeConfig = PocketRuntimeConfig(kvMode, lsdSteps, LAB_SEED)

    companion object {
        // Fixed across A/B/C so random sampling is not an uncontrolled variable.
        const val LAB_SEED = 0x41524941444232L
    }
}

/** AudioTrack write strategy used only by the temporary isolation lab. */
internal enum class PocketTransportMode(
    val label: String,
    val maxWriteBytes: Int?
) {
    CURRENT_WRITES("A — CURRENT WRITES", null),
    FRAGMENTED_8192("B — FRAGMENTED 8192 BYTES", 8_192)
}

internal object PocketIsolationLabSpec {
    const val TEXT =
        "Hola Kura. Soy Aria y esta es una prueba controlada de mi voz. " +
            "Quiero comprobar si puedo hablar de forma limpia, natural y continua, sin ruidos ni " +
            "interferencias durante toda esta frase. Seguiremos probando hasta encontrar exactamente " +
            "qué está ocurriendo con mi voz."
    const val TEMPERATURE = 0.7f
    const val SAMPLE_RATE = 24_000
}

internal data class PocketIsolationPlan(
    val variant: PocketIsolationVariant,
    val text: String,
    val voice: PocketVoice,
    val temperature: Float,
    val sampleRate: Int,
    val runtimeConfig: PocketRuntimeConfig
) {
    companion object {
        fun create(pack: PocketPack, voices: List<PocketVoice>, variant: PocketIsolationVariant): PocketIsolationPlan {
            require(pack.temperature == PocketIsolationLabSpec.TEMPERATURE)
            require(PocketModelSpec.SAMPLE_RATE == PocketIsolationLabSpec.SAMPLE_RATE)
            val voice = voices.firstOrNull { it.id == PocketBuiltinVoiceProfile.ID }
                ?: throw PocketVoiceException(PocketVoiceError.VOZ_NO_DISPONIBLE)
            return PocketIsolationPlan(
                variant,
                PocketIsolationLabSpec.TEXT,
                voice,
                pack.temperature,
                PocketModelSpec.SAMPLE_RATE,
                variant.runtimeConfig()
            )
        }
    }
}

/** Rejects overlapping lab requests instead of silently queueing a second inference. */
internal class PocketIsolationGate {
    private val running = AtomicBoolean(false)
    fun tryEnter(): Boolean = running.compareAndSet(false, true)
    fun leave() { running.set(false) }
    val isRunning: Boolean get() = running.get()
}

internal object PocketIsolationLabWav {
    const val SHARE_ACTION = "android.intent.action.SEND_MULTIPLE"
    const val SHARE_MIME_TYPE = "audio/wav"
    private const val DIRECTORY = "pocket-kv-lsd-lab"
    private const val HEADER_BYTES = 44L

    fun targets(cacheDir: File, variant: PocketIsolationVariant): PocketDiagnosticTargets {
        val directory = File(cacheDir, DIRECTORY)
        return PocketDiagnosticTargets(
            File(directory, variant.floatFileName),
            File(directory, variant.pcmFileName)
        )
    }

    fun beginCapture(cacheDir: File, variant: PocketIsolationVariant): PocketIsolationLabCapture =
        PocketIsolationLabCapture.begin(targets(cacheDir, variant), variant)

    fun committedFiles(cacheDir: File): List<File> = PocketIsolationVariant.entries.flatMap { variant ->
        val targets = targets(cacheDir, variant)
        if (isCommitted(targets, variant)) targets.files() else emptyList()
    }

    fun isCommitted(targets: PocketDiagnosticTargets, variant: PocketIsolationVariant): Boolean {
        if (targets.float32File.name != variant.floatFileName || targets.pcm16File.name != variant.pcmFileName) return false
        if (!isWav(targets.float32File) || !isWav(targets.pcm16File)) return false
        val marker = marker(targets, variant)
        val values = runCatching { marker.readLines(Charsets.US_ASCII) }.getOrNull() ?: return false
        return marker.isFile && values.size == 4 && values[0] == "v1" && values[1].isNotBlank() &&
            values[2].toLongOrNull() == targets.float32File.length() &&
            values[3].toLongOrNull() == targets.pcm16File.length()
    }

    fun <T : Any> shareUris(files: List<File>, uriForFile: (File) -> T): List<T> {
        require(files.isNotEmpty()) { "No hay WAV del laboratorio para compartir" }
        require(files.all(::isWav)) { "WAV de laboratorio incompleto" }
        val names = PocketIsolationVariant.entries.flatMap { listOf(it.floatFileName, it.pcmFileName) }.toSet()
        require(files.all { it.name in names } && files.map { it.canonicalPath }.distinct().size == files.size)
        val uris = files.map(uriForFile)
        require(uris.distinct().size == uris.size) { "Las URI del laboratorio deben ser distintas" }
        return uris
    }

    internal fun marker(targets: PocketDiagnosticTargets, variant: PocketIsolationVariant) =
        File(targets.float32File.parentFile, ".${variant.name}.ready")

    private fun isWav(file: File): Boolean = file.isFile && file.length() > HEADER_BYTES
}

internal class PocketIsolationLabCapture private constructor(
    override val targets: PocketDiagnosticTargets,
    override val stagedTargets: PocketDiagnosticTargets,
    private val marker: File,
    private val markerPartial: File,
    private val generationId: String
) : PocketWavCapture {
    override fun publish(): Boolean = try {
        check(stagedTargets.files().all { it.isFile && it.length() > 44L })
        check(stagedTargets.float32File.renameTo(targets.float32File))
        check(stagedTargets.pcm16File.renameTo(targets.pcm16File))
        FileOutputStream(markerPartial).use { output ->
            output.write("v1\n$generationId\n${targets.float32File.length()}\n${targets.pcm16File.length()}\n"
                .toByteArray(Charsets.US_ASCII))
            output.fd.sync()
        }
        check(markerPartial.renameTo(marker))
        true
    } catch (_: Throwable) {
        abort()
        false
    }

    override fun abort() {
        marker.delete()
        markerPartial.delete()
        targets.files().forEach(File::delete)
        stagedTargets.files().forEach(File::delete)
        stagedTargets.files().forEach { File(it.parentFile, "${it.name}.partial").delete() }
    }

    companion object {
        fun begin(targets: PocketDiagnosticTargets, variant: PocketIsolationVariant): PocketIsolationLabCapture {
            require(targets.float32File.name == variant.floatFileName)
            require(targets.pcm16File.name == variant.pcmFileName)
            val directory = targets.float32File.parentFile?.canonicalFile ?: error("Directorio de laboratorio ausente")
            require(targets.pcm16File.parentFile?.canonicalFile == directory)
            check(directory.mkdirs() || directory.isDirectory)
            val marker = PocketIsolationLabWav.marker(targets, variant)
            val markerPartial = File(directory, "${marker.name}.partial")
            val staged = PocketDiagnosticTargets(
                File(directory, "${targets.float32File.name}.pending"),
                File(directory, "${targets.pcm16File.name}.pending")
            )
            marker.delete()
            markerPartial.delete()
            targets.files().forEach(File::delete)
            staged.files().forEach(File::delete)
            staged.files().forEach { File(it.parentFile, "${it.name}.partial").delete() }
            return PocketIsolationLabCapture(targets, staged, marker, markerPartial, UUID.randomUUID().toString())
        }
    }
}
