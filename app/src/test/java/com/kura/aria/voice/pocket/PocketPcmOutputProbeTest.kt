package com.kura.aria.voice.pocket

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketPcmOutputProbeTest {
    @Test fun independentToneIsOneSecondOfNonSilentMonoPcm16() {
        val pcm = PocketPcmOutputProbe.tone()
        assertEquals(48_000, pcm.size)
        val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, samples.short.toInt())
        assertTrue(ShortArray(100) { samples.short }.any { it != 0.toShort() })
    }
}
