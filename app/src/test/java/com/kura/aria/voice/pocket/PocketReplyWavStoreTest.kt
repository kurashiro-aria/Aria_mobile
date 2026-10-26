package com.kura.aria.voice.pocket

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class PocketReplyWavStoreTest {
    @Rule @JvmField val temporary = TemporaryFolder()

    @Test fun consecutiveShortAndLongRepliesKeepTheirOwnExactPcmAndText() {
        val store = PocketReplyWavStore(temporary.root)
        val first = store.begin("¡Hola, Kura!", "¡Hola, Kura!")
        val firstPcm = capture(first, listOf(floatArrayOf(-0.5f, 0.125f), floatArrayOf(0.75f)))
        assertTrue(first.finish(PocketReplyWavStore.State.COMPLETE))
        val long = store.begin("Primera frase. Segunda frase. ¿Cómo va tu día?", "Primera frase. Segunda frase. ¿Cómo va tu día?")
        val chunks = (0 until 40).map { i -> FloatArray(240) { j -> ((i + j) % 41 - 20) / 40f } }
        val longPcm = capture(long, chunks)
        assertTrue(long.finish(PocketReplyWavStore.State.COMPLETE))
        assertArrayEquals(firstPcm, first.targets.pcm16File.readBytes().copyOfRange(44, first.targets.pcm16File.length().toInt()))
        assertArrayEquals(longPcm, long.targets.pcm16File.readBytes().copyOfRange(44, long.targets.pcm16File.length().toInt()))
        assertTrue(first.shareable())
        assertTrue(long.shareable())
        assertTrue(first.diagnosticFile.readText().contains("Muestras WAV: 3"))
        assertTrue(long.diagnosticFile.readText().contains("Callbacks: 40"))
        assertTrue(long.diagnosticFile.readText().contains("Muestras WAV: 9600"))
        assertTrue(long.diagnosticFile.readText().contains("¿Cómo va tu día?"))
        assertEquals(2, long.shareFiles().size)
        assertFalse(first.id == long.id)
    }

    @Test fun incompleteOrCancelledCaptureKeepsValidPartialWavOnly() {
        val store = PocketReplyWavStore(temporary.root)
        val incomplete = store.begin("Texto completo", "Texto completo")
        val capture = PocketDiagnosticWav.beginCapture(incomplete.targets)
        val pipeline = PocketPcm16Pipeline(
            prebufferBytes = 2,
            writer = { _, _, _ -> error("Playback failed") }, onPlaybackStart = {},
            wavWriter = PocketWavWriter(capture.stagedTargets.pcm16File),
            floatWavWriter = PocketFloatWavWriter(capture.stagedTargets.float32File)
        )
        incomplete.onCallback(3)
        runCatching { pipeline.accept(floatArrayOf(0.1f, 0.2f, 0.3f)) }
        assertTrue(pipeline.finishDiagnostics())
        assertTrue(capture.publish(partial = true))
        assertTrue(incomplete.finish(PocketReplyWavStore.State.INCOMPLETE))
        assertTrue(incomplete.diagnosticFile.readText().contains("Estado: INCOMPLETE"))
        assertTrue(incomplete.diagnosticFile.readText().contains("Muestras WAV: 3"))

        val cancelled = store.begin("Cancelada", "Cancelada")
        capture(cancelled, listOf(floatArrayOf(0.5f)), partial = true)
        assertTrue(cancelled.finish(PocketReplyWavStore.State.CANCELLED))
        assertTrue(cancelled.diagnosticFile.readText().contains("Estado: CANCELLED"))

        val empty = store.begin("Sin audio", "Sin audio")
        assertFalse(empty.finish(PocketReplyWavStore.State.INCOMPLETE))
        assertFalse(empty.shareable())
    }

    @Test fun cleanupExpiresPrivateAudioWithoutTouchingCurrentResponse() {
        val store = PocketReplyWavStore(temporary.root)
        val old = store.begin("Vieja", "Vieja")
        capture(old, listOf(floatArrayOf(0.1f)))
        assertTrue(old.finish(PocketReplyWavStore.State.COMPLETE))
        val new = store.begin("Nueva", "Nueva")
        capture(new, listOf(floatArrayOf(0.2f)))
        assertTrue(new.finish(PocketReplyWavStore.State.COMPLETE))
        store.cleanup(System.currentTimeMillis() + 25L * 60L * 60L * 1000L, protectedId = new.id)
        assertFalse(old.targets.pcm16File.exists())
        assertTrue(new.shareable())
    }

    private fun capture(reply: PocketReplyWavStore.Reply, chunks: List<FloatArray>, partial: Boolean = false): ByteArray {
        val capture = PocketDiagnosticWav.beginCapture(reply.targets)
        val sink = ByteArrayOutputStream()
        val pipeline = PocketPcm16Pipeline(
            prebufferBytes = 48_000,
            writer = { bytes, offset, length -> sink.write(bytes, offset, length); length },
            onPlaybackStart = {},
            wavWriter = PocketWavWriter(capture.stagedTargets.pcm16File),
            floatWavWriter = PocketFloatWavWriter(capture.stagedTargets.float32File)
        )
        chunks.forEach { reply.onCallback(it.size); pipeline.accept(it) }
        pipeline.finish()
        assertTrue(capture.publish(partial))
        return sink.toByteArray()
    }
}
