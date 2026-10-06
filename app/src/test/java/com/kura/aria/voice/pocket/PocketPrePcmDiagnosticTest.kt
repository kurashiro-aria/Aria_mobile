package com.kura.aria.voice.pocket

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.sqrt

class PocketPrePcmDiagnosticTest {
    @Rule @JvmField val temporaryFolder = TemporaryFolder()

    @Test
    fun writesValidMono24000HzIeeeFloat32WaveHeader() {
        val header = PocketFloatWavWriter.header(12)
        val bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals(48, bytes.getInt(4))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals(16, bytes.getInt(16))
        assertEquals(3, bytes.getShort(20).toInt())
        assertEquals(1, bytes.getShort(22).toInt())
        assertEquals(24_000, bytes.getInt(24))
        assertEquals(96_000, bytes.getInt(28))
        assertEquals(4, bytes.getShort(32).toInt())
        assertEquals(32, bytes.getShort(34).toInt())
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        assertEquals(12, bytes.getInt(40))
    }

    @Test
    fun floatWavePayloadPreservesSourceFloatBitsLittleEndian() {
        val target = File(temporaryFolder.newFolder(), "source.wav")
        val samples = floatArrayOf(0.25f, -0.75f, 0f, Float.POSITIVE_INFINITY)
        val writer = PocketFloatWavWriter(target)
        writer.append(samples)
        writer.finish()

        val bytes = target.readBytes()
        assertEquals(44 + samples.size * 4, bytes.size)
        val payload = ByteBuffer.wrap(bytes, 44, samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { assertEquals(java.lang.Float.floatToRawIntBits(it), payload.int) }
    }

    @Test
    fun floatMetricsCountNonFiniteAndOutOfRangeSamples() {
        val inspector = PocketFloatInspector()
        inspector.accept(floatArrayOf(0.5f, -1.5f, 2f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))
        val stats = inspector.snapshot()

        assertEquals(6L, stats.sampleCount)
        assertEquals(0.5f, stats.minimum)
        assertEquals(2f, stats.maximum)
        assertEquals(sqrt(6.5 / 3.0), stats.rms, 1e-9)
        assertEquals(1.0 / 3.0, stats.mean, 1e-9)
        assertEquals(1L, stats.nanCount)
        assertEquals(1L, stats.positiveInfinityCount)
        assertEquals(1L, stats.negativeInfinityCount)
        assertEquals(1L, stats.aboveOneCount)
        assertEquals(1L, stats.belowMinusOneCount)
        assertEquals(100.0 / 3.0, stats.outsideRangePercent, 1e-9)
    }

    @Test
    fun onePipelineSequenceFeedsFloatWavePcmWaveAndExistingConversion() {
        val directory = temporaryFolder.newFolder()
        val floatFile = File(directory, "pre.wav")
        val pcmFile = File(directory, "pcm.wav")
        val sink = ByteArrayOutputStream()
        val source = floatArrayOf(-1f, -0.375f, 0f, 0.12345f, 0.75f, 1f)
        val pipeline = PocketPcm16Pipeline(
            prebufferBytes = 1_200,
            writer = { bytes, offset, length -> sink.write(bytes, offset, length); length },
            onPlaybackStart = {},
            wavWriter = PocketWavWriter(pcmFile),
            floatWavWriter = PocketFloatWavWriter(floatFile)
        )

        pipeline.accept(source)
        pipeline.finish()

        val floatBytes = floatFile.readBytes()
        val decodedSource = ByteBuffer.wrap(floatBytes, 44, source.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        source.forEach { assertEquals(java.lang.Float.floatToRawIntBits(it), decodedSource.int) }
        val pcmFromSink = sink.toByteArray()
        assertArrayEquals(PocketPcm.floatToPcm16(source), pcmFromSink)
        assertArrayEquals(pcmFromSink, pcmFile.readBytes().copyOfRange(44, pcmFile.length().toInt()))
        assertEquals(source.size.toLong(), pipeline.floatInspector!!.snapshot().sampleCount)
        assertEquals(source.size.toLong(), pipeline.pcm16Inspector!!.snapshot().sampleCount)
        assertTrue(pipeline.quantizationInspector!!.snapshot().maxAbsoluteError <= 1.0 / 32767.0)
        assertEquals(source.size.toLong(), pipeline.quantizationInspector!!.snapshot().sampleCount)
    }

    @Test
    fun pcm16ConversionAndQuantizationRemainWithinExpectedBound() {
        val samples = floatArrayOf(0f, 1f, -1f, 1.2f, -1.2f, 0.12345f, Float.NaN)
        val actual = PocketPcm.floatToPcm16(samples)
        val decoded = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN)
        assertArrayEquals(shortArrayOf(0, 32767, -32767, 32767, -32767, 0), ShortArray(samples.size) { decoded.short })

        val inspector = PocketQuantizationInspector()
        inspector.accept(samples, actual)
        val errors = inspector.snapshot()
        val pcmStats = PocketPcm16Inspector().also { it.accept(actual) }.snapshot()
        assertEquals(2L, pcmStats.clippedPositiveCount)
        assertEquals(0L, pcmStats.clippedNegativeCount)
        assertEquals(32767, pcmStats.peakAbsolute)
        assertTrue(errors.maxAbsoluteError <= 1.0 / 32767.0)
        assertTrue(errors.meanAbsoluteError <= errors.maxAbsoluteError)
        assertTrue(errors.rmsError > 0.0)
        assertTrue(errors.rmsError <= errors.maxAbsoluteError)
    }
}
