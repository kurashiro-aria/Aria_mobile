package com.kura.aria.voice.pocket

import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Temporary, one-inference evidence. The engine still writes the exact same PCM to AudioTrack. */
internal class PocketReplyWavStore(private val cacheDir: File) {
    private val root = File(cacheDir, "pocket-diagnostics/replies")

    fun begin(exactReply: String, spokenText: String): Reply {
        cleanup()
        val id = UUID.randomUUID().toString()
        val directory = File(root, id)
        return Reply(
            id, exactReply, spokenText,
            PocketDiagnosticTargets(
                File(directory, PocketDiagnosticWav.FLOAT_FILE_NAME),
                File(directory, PocketDiagnosticWav.PCM_FILE_NAME)
            )
        )
    }

    /** Remove expired evidence and bound retained conversations, including abandoned captures. */
    fun cleanup(now: Long = System.currentTimeMillis(), protectedId: String? = null) {
        val directories = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }
            ?: return
        directories.forEachIndexed { index, directory ->
            if (directory.name != protectedId &&
                (index >= MAX_REPLIES || now - directory.lastModified() > MAX_AGE_MS)) {
                directory.deleteRecursively()
            }
        }
    }

    internal class Reply(
        val id: String,
        val exactReply: String,
        val spokenText: String,
        val targets: PocketDiagnosticTargets
    ) {
        private val callbacks = AtomicLong()
        private val callbackSamples = AtomicLong()
        val diagnosticFile: File = File(targets.pcm16File.parentFile, "pocket_reply_diagnostic.txt")

        fun onCallback(samples: Int) {
            callbacks.incrementAndGet()
            callbackSamples.addAndGet(samples.toLong())
        }

        /** A failure can still yield valid partial WAVs published by the engine. */
        fun finish(state: State): Boolean {
            val pair = targets.files()
            if (!PocketDiagnosticWav.areShareable(pair)) return false
            val samples = (targets.pcm16File.length() - 44L) / 2L
            val marker = File(targets.pcm16File.parentFile, ".pocket-diagnostics.ready")
            val partial = marker.readLines().getOrNull(2) == "partial"
            val actualState = if (state == State.COMPLETE && partial) State.INCOMPLETE else state
            val report = buildString {
                append("Respuesta: ").append(id).append('\n')
                append("Estado: ").append(actualState.name).append('\n')
                append("Captura: ").append(if (partial) "PARCIAL" else "COMPLETA").append('\n')
                append("Formato: PCM16 mono, ").append(PocketAudioFormat.SAMPLE_RATE).append(" Hz\n")
                append("Callbacks: ").append(callbacks.get()).append('\n')
                append("Muestras callbacks: ").append(callbackSamples.get()).append('\n')
                append("Muestras WAV: ").append(samples).append('\n')
                append("Duración ms: ").append(samples * 1000L / PocketAudioFormat.SAMPLE_RATE).append('\n')
                append("Texto exacto de la respuesta:\n").append(exactReply).append('\n')
                append("Texto enviado a Pocket:\n").append(spokenText).append('\n')
            }
            val staged = File(diagnosticFile.parentFile, "${diagnosticFile.name}.pending")
            return runCatching {
                staged.writeText(report, Charsets.UTF_8)
                check(staged.renameTo(diagnosticFile))
                true
            }.getOrDefault(false).also { if (!it) staged.delete() }
        }

        fun shareable(): Boolean = diagnosticFile.isFile && PocketDiagnosticWav.areShareable(targets.files()) &&
            diagnosticFile.readText().lineSequence().firstOrNull() == "Respuesta: $id"

        fun shareFiles(): List<File> {
            check(shareable())
            return listOf(targets.pcm16File, diagnosticFile)
        }
    }

    internal enum class State { COMPLETE, INCOMPLETE, CANCELLED }

    companion object {
        private const val MAX_REPLIES = 8
        private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
    }
}
