package com.kura.aria.voice.pocket

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Format produced by the pinned PocketTTS.cpp decoder and consumed by ARIA. */
internal object PocketAudioFormat {
    const val SAMPLE_RATE = 24_000
    const val CHANNELS = 1
    const val NATIVE_SAMPLE_FORMAT = "FLOAT32_LE"
    const val PLAYBACK_SAMPLE_FORMAT = "PCM16_LE"
    const val PCM16_BYTES_PER_SAMPLE = 2
    const val PREBUFFER_MS = 1_000
    const val PREBUFFER_SAMPLES = SAMPLE_RATE * PREBUFFER_MS / 1_000
    const val PREBUFFER_BYTES = PREBUFFER_SAMPLES * PCM16_BYTES_PER_SAMPLE
}

internal data class PocketPcmStats(
    val sampleCount: Long,
    val durationMs: Long,
    val minimum: Float,
    val maximum: Float,
    val peakAbsolute: Float,
    val rms: Double,
    val dcOffset: Double,
    val nanCount: Long,
    val infiniteCount: Long,
    val clippedSamples: Long
)

internal class PocketPcmInspector {
    private var samples = 0L
    private var finiteSamples = 0L
    private var minimum = Float.POSITIVE_INFINITY
    private var maximum = Float.NEGATIVE_INFINITY
    private var sum = 0.0
    private var squareSum = 0.0
    private var nan = 0L
    private var infinite = 0L
    private var clipped = 0L

    fun accept(values: FloatArray) {
        samples += values.size
        values.forEach { value ->
            when {
                value.isNaN() -> nan++
                value.isInfinite() -> infinite++
                else -> {
                    finiteSamples++
                    minimum = minOf(minimum, value)
                    maximum = maxOf(maximum, value)
                    sum += value
                    squareSum += value.toDouble() * value.toDouble()
                    if (abs(value) > 1f) clipped++
                }
            }
        }
    }

    fun snapshot(): PocketPcmStats {
        val count = finiteSamples.coerceAtLeast(1L)
        val min = if (finiteSamples == 0L) 0f else minimum
        val max = if (finiteSamples == 0L) 0f else maximum
        return PocketPcmStats(
            sampleCount = samples,
            durationMs = samples * 1_000L / PocketAudioFormat.SAMPLE_RATE,
            minimum = min,
            maximum = max,
            peakAbsolute = maxOf(abs(min), abs(max)),
            rms = sqrt(squareSum / count),
            dcOffset = sum / count,
            nanCount = nan,
            infiniteCount = infinite,
            clippedSamples = clipped
        )
    }
}

internal object PocketPcm {
    fun floatToPcm16(samples: FloatArray): ByteArray {
        val output = ByteBuffer.allocate(samples.size * PocketAudioFormat.PCM16_BYTES_PER_SAMPLE)
            .order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { sample ->
            val finite = if (sample.isFinite()) sample else 0f
            output.putShort((finite.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        return output.array()
    }
}

/** Writes every byte, including when AudioTrack accepts only a partial buffer. */
internal object PocketPartialWrite {
    fun writeFully(bytes: ByteArray, writer: (ByteArray, Int, Int) -> Int) {
        var offset = 0
        while (offset < bytes.size) {
            val written = writer(bytes, offset, bytes.size - offset)
            require(written > 0 && written <= bytes.size - offset) { "PCM write failed" }
            offset += written
        }
    }
}

/**
 * Keeps Pocket chunks ordered and writes a bounded preroll before playback starts.
 * This prevents the guaranteed underrun caused by starting an empty AudioTrack.
 */
internal class PocketPcm16Pipeline(
    private val prebufferBytes: Int = PocketAudioFormat.PREBUFFER_BYTES,
    private val writer: (ByteArray, Int, Int) -> Int,
    private val onPlaybackStart: () -> Unit,
    private val wavWriter: PocketWavWriter? = null
) {
    private val pending = ByteArrayOutputStream(prebufferBytes)
    val inspector = PocketPcmInspector()
    var playbackStarted = false
        private set
    var writtenSamples = 0L
        private set

    fun accept(samples: FloatArray) {
        inspector.accept(samples)
        val bytes = PocketPcm.floatToPcm16(samples)
        wavWriter?.append(bytes)
        if (!playbackStarted) {
            pending.write(bytes)
            if (pending.size() >= prebufferBytes) startPlayback()
        } else {
            write(bytes)
        }
    }

    fun finish() {
        if (!playbackStarted && pending.size() > 0) startPlayback()
        wavWriter?.finish()
    }

    fun abort() = wavWriter?.abort()

    private fun startPlayback() {
        val buffered = pending.toByteArray()
        pending.reset()
        // AudioTrack is filled before play(), never started empty.
        write(buffered)
        onPlaybackStart()
        playbackStarted = true
    }

    private fun write(bytes: ByteArray) {
        PocketPartialWrite.writeFully(bytes, writer)
        writtenSamples += bytes.size / PocketAudioFormat.PCM16_BYTES_PER_SAMPLE
    }
}

/** Minimal canonical PCM16 mono WAV writer for the exact playback bytes. */
internal class PocketWavWriter(private val target: File) {
    private val partial = File(target.parentFile, "${target.name}.partial")
    private val file: RandomAccessFile
    private var dataBytes = 0L
    private var closed = false

    init {
        target.parentFile?.mkdirs()
        file = RandomAccessFile(partial, "rw")
        file.setLength(0L)
        file.write(ByteArray(44))
    }

    fun append(bytes: ByteArray) {
        check(!closed)
        file.write(bytes)
        dataBytes += bytes.size
    }

    fun finish(): File {
        if (closed) return target
        require(dataBytes in 1..0xfffffff0L) { "Invalid WAV payload" }
        file.seek(0L)
        file.write(header(dataBytes.toInt()))
        file.fd.sync()
        file.close()
        closed = true
        if (target.exists() && !target.delete()) error("Could not replace diagnostic WAV")
        if (!partial.renameTo(target)) error("Could not publish diagnostic WAV")
        return target
    }

    fun abort() {
        if (!closed) runCatching { file.close() }
        closed = true
        partial.delete()
    }

    companion object {
        fun header(dataBytes: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataBytes)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(PocketAudioFormat.CHANNELS.toShort())
            putInt(PocketAudioFormat.SAMPLE_RATE)
            putInt(PocketAudioFormat.SAMPLE_RATE * PocketAudioFormat.PCM16_BYTES_PER_SAMPLE)
            putShort(PocketAudioFormat.PCM16_BYTES_PER_SAMPLE.toShort()); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes)
        }.array()
    }
}
