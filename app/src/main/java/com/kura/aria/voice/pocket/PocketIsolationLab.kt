package com.kura.aria.voice.pocket

import java.io.File
import java.io.FileOutputStream
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
    const val SHARE_MIME_TYPE = "*/*"
    private const val DIRECTORY = "pocket-kv-lsd-lab"
    private const val HEADER_BYTES = 44L

    fun targets(cacheDir: File, variant: PocketIsolationVariant, runId: String): PocketDiagnosticTargets {
        val directory = File(cacheDir, DIRECTORY)
        require(runId.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "runId inválido" }
        val prefix = variant.floatFileName.removeSuffix("_f32.wav")
        return PocketDiagnosticTargets(
            File(directory, "${prefix}_${runId}_f32.wav"),
            File(directory, "${prefix}_${runId}_pcm16.wav")
        )
    }

    fun beginCapture(cacheDir: File, variant: PocketIsolationVariant, runId: String): PocketIsolationLabCapture =
        PocketIsolationLabCapture.begin(targets(cacheDir, variant, runId), variant, runId)

    fun committedFiles(cacheDir: File, runId: String? = null): List<File> {
        val directory = File(cacheDir, DIRECTORY)
        return directory.listFiles()?.filter { it.name.startsWith(".pocket_lab_") && it.name.endsWith(".ready") }
            .orEmpty().flatMap { marker ->
                val values = runCatching { marker.readLines(Charsets.US_ASCII) }.getOrNull().orEmpty()
                if (values.size != 7 || values[0] != "v3" || runId != null && values[1] != runId)
                    return@flatMap emptyList()
                val floatFile = File(directory, values[3])
                val pcmFile = File(directory, values[4])
                val pair = PocketDiagnosticTargets(floatFile, pcmFile)
                if (isCommitted(pair, marker, values)) pair.files() else emptyList()
            }.sortedBy { it.name }
    }

    fun isCommitted(targets: PocketDiagnosticTargets, variant: PocketIsolationVariant): Boolean {
        if (!targets.float32File.name.startsWith(variant.floatFileName.removeSuffix("_f32.wav") + "_") ||
            !targets.pcm16File.name.startsWith(variant.pcmFileName.removeSuffix("_pcm16.wav") + "_")) return false
        if (!isFloatWav(targets.float32File) || !isPcm16Wav(targets.pcm16File)) return false
        val marker = marker(targets)
        val values = runCatching { marker.readLines(Charsets.US_ASCII) }.getOrNull() ?: return false
        return marker.isFile && isCommitted(targets, marker, values)
    }

    private fun isCommitted(targets: PocketDiagnosticTargets, marker: File, values: List<String>): Boolean =
        values.size == 7 && values[0] == "v3" && values[1].isNotBlank() &&
            values[2] in setOf("complete", "partial") &&
            values[3] == targets.float32File.name && values[4] == targets.pcm16File.name &&
            values[5].toLongOrNull() == targets.float32File.length() &&
            values[6].toLongOrNull() == targets.pcm16File.length() &&
            isFloatWav(targets.float32File) && isPcm16Wav(targets.pcm16File) &&
            isCommittedWavPair(targets)

    fun <T : Any> shareUris(files: List<File>, uriForFile: (File) -> T): List<T> {
        require(files.isNotEmpty()) { "No hay WAV del laboratorio para compartir" }
        require(files.all { it.name.contains("_") && (it.name.endsWith("_f32.wav") && isFloatWav(it) ||
            it.name.endsWith("_pcm16.wav") && isPcm16Wav(it)) }) {
            "WAV de laboratorio incompleto"
        }
        require(files.map { it.canonicalPath }.distinct().size == files.size)
        val runIds = files.map { file ->
            val suffix = if (file.name.endsWith("_f32.wav")) "_f32.wav" else "_pcm16.wav"
            file.name.removeSuffix(suffix).substringAfterLast('_')
        }
        require(runIds.distinct().size == 1 && files.count { it.name.endsWith("_f32.wav") } == 1 &&
            files.count { it.name.endsWith("_pcm16.wav") } == 1) { "Los WAV deben pertenecer al mismo runId" }
        val uris = files.map(uriForFile)
        require(uris.distinct().size == uris.size) { "Las URI del laboratorio deben ser distintas" }
        return uris
    }

    internal fun marker(targets: PocketDiagnosticTargets) =
        File(targets.float32File.parentFile, ".${targets.float32File.name.removeSuffix("_f32.wav")}.ready")

    internal fun isCommittedWavPair(targets: PocketDiagnosticTargets): Boolean =
        isFloatWav(targets.float32File) && isPcm16Wav(targets.pcm16File) &&
            (targets.float32File.length() - HEADER_BYTES) / 4L ==
            (targets.pcm16File.length() - HEADER_BYTES) / PocketAudioFormat.PCM16_BYTES_PER_SAMPLE

    private fun isFloatWav(file: File): Boolean = PocketDiagnosticWav.isValidFloatWav(file)
    private fun isPcm16Wav(file: File): Boolean = PocketDiagnosticWav.isValidPcm16Wav(file)
}

internal class PocketIsolationLabCapture private constructor(
    override val targets: PocketDiagnosticTargets,
    override val stagedTargets: PocketDiagnosticTargets,
    private val marker: File,
    private val markerPartial: File,
    val runId: String
) : PocketWavCapture {
    override fun publish(): Boolean = publish(partial = false)

    override fun publish(partial: Boolean): Boolean = try {
        check(PocketIsolationLabWav.isCommittedWavPair(stagedTargets))
        check(stagedTargets.float32File.renameTo(targets.float32File))
        check(stagedTargets.pcm16File.renameTo(targets.pcm16File))
        check(PocketIsolationLabWav.isCommittedWavPair(targets))
        FileOutputStream(markerPartial).use { output ->
            output.write(("v3\n$runId\n${if (partial) "partial" else "complete"}\n" +
                "${targets.float32File.name}\n${targets.pcm16File.name}\n" +
                "${targets.float32File.length()}\n${targets.pcm16File.length()}\n")
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
        fun begin(targets: PocketDiagnosticTargets, variant: PocketIsolationVariant, runId: String): PocketIsolationLabCapture {
            require(targets.float32File.name.startsWith(variant.floatFileName.removeSuffix("_f32.wav") + "_${runId}_"))
            require(targets.pcm16File.name.startsWith(variant.pcmFileName.removeSuffix("_pcm16.wav") + "_${runId}_"))
            val directory = targets.float32File.parentFile?.canonicalFile ?: error("Directorio de laboratorio ausente")
            require(targets.pcm16File.parentFile?.canonicalFile == directory)
            check(directory.mkdirs() || directory.isDirectory)
            val marker = PocketIsolationLabWav.marker(targets)
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
            return PocketIsolationLabCapture(targets, staged, marker, markerPartial, runId)
        }
    }
}
