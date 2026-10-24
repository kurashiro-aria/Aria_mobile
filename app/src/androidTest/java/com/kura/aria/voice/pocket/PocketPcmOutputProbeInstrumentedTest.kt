package com.kura.aria.voice.pocket

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires an Android audio output; it tests AudioTrack without loading Pocket. */
@RunWith(AndroidJUnit4::class)
class PocketPcmOutputProbeInstrumentedTest {
    @Test fun generatedPcmStartsAndPlaybackHeadAdvances() {
        val result = PocketPcmOutputProbe.run()
        assertEquals(result.summary(), 48_000, result.writtenBytes)
        assertNull(result.summary(), result.error)
        assertTrue(result.summary(), result.playbackHead > 0L)
    }
}
