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


internal data class PocketFloatStats(
    val sampleCount: Long,
    val durationMs: Long,
    val minimum: Float,
    val maximum: Float,
    val peakAbsolute: Float,
    val rms: Double,
    val mean: Double,
    val nanCount: Long,
    val positiveInfinityCount: Long,
    val negativeInfinityCount: Long,
    val aboveOneCount: Long,
    val belowMinusOneCount: Long,
    val outsideRangePercent: Double
)

internal class PocketFloatInspector {
    private var samples = 0L
    private var finite = 0L
    private var minimum = Float.POSITIVE_INFINITY
    private var maximum = Float.NEGATIVE_INFINITY
    private var sum = 0.0
    private var squares = 0.0
    private var nan = 0L
    private var positiveInfinity = 0L
    private var negativeInfinity = 0L
    private var aboveOne = 0L
    private var belowMinusOne = 0L

    fun accept(values: FloatArray) {
        samples += values.size
        values.forEach { value ->
            when {
                value.isNaN() -> nan++
                value == Float.POSITIVE_INFINITY -> positiveInfinity++
                value == Float.NEGATIVE_INFINITY -> negativeInfinity++
                else -> {
                    finite++
                    minimum = minOf(minimum, value)
                    maximum = maxOf(maximum, value)
                    sum += value.toDouble()
                    squares += value.toDouble() * value.toDouble()
                    if (value > 1f) aboveOne++
                    if (value < -1f) belowMinusOne++
                }
            }
        }
    }

    fun snapshot(): PocketFloatStats {
        val count = finite.coerceAtLeast(1L)
        val min = if (finite == 0L) 0f else minimum
        val max = if (finite == 0L) 0f else maximum
        val outside = aboveOne + belowMinusOne
        return PocketFloatStats(
            sampleCount = samples,
            durationMs = samples * 1_000L / PocketAudioFormat.SAMPLE_RATE,
            minimum = min,
            maximum = max,
            peakAbsolute = maxOf(abs(min), abs(max)),
            rms = sqrt(squares / count),
            mean = sum / count,
            nanCount = nan,
            positiveInfinityCount = positiveInfinity,
            negativeInfinityCount = negativeInfinity,
            aboveOneCount = aboveOne,
            belowMinusOneCount = belowMinusOne,
            outsideRangePercent = if (samples == 0L) 0.0 else outside * 100.0 / samples
        )
    }
}

internal data class PocketPcm16Stats(
    val sampleCount: Long,
    val durationMs: Long,
    val minimum: Short,
    val maximum: Short,
    val peakAbsolute: Int,
    val rms: Double,
    val clippedPositiveCount: Long,
    val clippedNegativeCount: Long
)

internal class PocketPcm16Inspector {
    private var samples = 0L
    private var minimum = Short.MAX_VALUE
    private var maximum = Short.MIN_VALUE
    private var squares = 0.0
    private var clippedPositive = 0L
    private var clippedNegative = 0L

    fun accept(pcm16le: ByteArray) {
        require(pcm16le.size % 2 == 0) { "PCM16 payload must contain complete samples" }
        val buffer = ByteBuffer.wrap(pcm16le).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.remaining() >= 2) {
            val value = buffer.short
            samples++
            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
            squares += value.toDouble() * value.toDouble()
            if (value == Short.MAX_VALUE) clippedPositive++
            if (value == Short.MIN_VALUE) clippedNegative++
        }
    }

    fun snapshot(): PocketPcm16Stats = PocketPcm16Stats(
        sampleCount = samples,
        durationMs = samples * 1_000L / PocketAudioFormat.SAMPLE_RATE,
        minimum = if (samples == 0L) 0 else minimum,
        maximum = if (samples == 0L) 0 else maximum,
        peakAbsolute = maxOf(abs(minimum.toInt()), abs(maximum.toInt())).takeIf { samples > 0 } ?: 0,
        rms = sqrt(squares / samples.coerceAtLeast(1L)),
        clippedPositiveCount = clippedPositive,
        clippedNegativeCount = clippedNegative
    )
}

internal data class PocketQuantizationStats(
    val sampleCount: Long,
    val maxAbsoluteError: Double,
    val meanAbsoluteError: Double,
    val rmsError: Double
)

internal class PocketQuantizationInspector {
    private var samples = 0L
    private var maximum = 0.0
    private var absoluteSum = 0.0
    private var squareSum = 0.0

    fun accept(floatSamples: FloatArray, pcm16le: ByteArray) {
        require(pcm16le.size == floatSamples.size * PocketAudioFormat.PCM16_BYTES_PER_SAMPLE)
        val pcm = ByteBuffer.wrap(pcm16le).order(ByteOrder.LITTLE_ENDIAN)
        floatSamples.forEach { input ->
            val decoded = pcm.short.toDouble() / 32767.0
            val clamped = if (input.isFinite()) input.coerceIn(-1f, 1f).toDouble() else 0.0
            val error = abs(decoded - clamped)
            samples++
            maximum = maxOf(maximum, error)
            absoluteSum += error
            squareSum += error * error
        }
    }

    fun snapshot(): PocketQuantizationStats = PocketQuantizationStats(
        sampleCount = samples,
        maxAbsoluteError = maximum,
        meanAbsoluteError = absoluteSum / samples.coerceAtLeast(1L),
        rmsError = sqrt(squareSum / samples.coerceAtLeast(1L))
    )
}

/** Diagnostic-only standard RIFF/WAVE IEEE float32 writer; source floats are preserved bit-for-bit. */
internal class PocketFloatWavWriter(private val target: File) {
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

    fun append(samples: FloatArray) {
        check(!closed)
        require(samples.size <= MAX_DATA_BYTES / 4) { "FLOAT32 chunk is too large" }
        require(samples.size.toLong() * 4L <= MAX_DATA_BYTES - dataBytes) { "Invalid FLOAT32 WAV payload" }
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putInt(java.lang.Float.floatToRawIntBits(it)) }
        file.write(bytes.array())
        dataBytes += bytes.capacity()
    }

    fun finish(): File {
        if (closed) return target
        require(dataBytes in 4..MAX_DATA_BYTES && dataBytes % 4L == 0L) { "Invalid FLOAT32 WAV payload" }
        file.seek(0L)
        file.write(header(dataBytes.toInt()))
        file.fd.sync()
        file.close()
        closed = true
        if (target.exists() && !target.delete()) error("Could not replace diagnostic FLOAT32 WAV")
        if (!partial.renameTo(target)) error("Could not publish diagnostic FLOAT32 WAV")
        return target
    }

    fun abort() {
        if (!closed) runCatching { file.close() }
        closed = true
        partial.delete()
    }

    companion object {
        const val IEEE_FLOAT_FORMAT_CODE = 3
        private const val MAX_DATA_BYTES = Int.MAX_VALUE.toLong() - 36L
        fun header(dataBytes: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataBytes)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(IEEE_FLOAT_FORMAT_CODE.toShort()); putShort(PocketAudioFormat.CHANNELS.toShort())
            putInt(PocketAudioFormat.SAMPLE_RATE)
            putInt(PocketAudioFormat.SAMPLE_RATE * 4)
            putShort(4); putShort(32)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes)
        }.array()
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
    private val wavWriter: PocketWavWriter? = null,
    private val floatWavWriter: PocketFloatWavWriter? = null
) {
    private val pending = ByteArrayOutputStream(prebufferBytes)
    val inspector = PocketPcmInspector()
    val floatInspector = if (floatWavWriter != null) PocketFloatInspector() else null
    val pcm16Inspector = if (floatWavWriter != null) PocketPcm16Inspector() else null
    val quantizationInspector = if (floatWavWriter != null) PocketQuantizationInspector() else null
    var playbackStarted = false
        private set
    var writtenSamples = 0L
        private set

    fun accept(samples: FloatArray) {
        floatWavWriter?.append(samples)
        floatInspector?.accept(samples)
        inspector.accept(samples)
        val bytes = PocketPcm.floatToPcm16(samples)
        pcm16Inspector?.accept(bytes)
        quantizationInspector?.accept(samples, bytes)
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
        floatWavWriter?.finish()
    }

    fun abort() {
        wavWriter?.abort()
        floatWavWriter?.abort()
    }

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
