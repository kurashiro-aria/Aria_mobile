package com.kura.aria.voice.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PocketIsolationLabTest {
    @Rule @JvmField val temporaryFolder = TemporaryFolder()

    private fun pack(): PocketPack {
        val root = temporaryFolder.newFolder("pack-${System.nanoTime()}")
        return PocketPack(
            "spanish", "Spanish", "es-ES", "fp32", 0.7f, 1, 4, root,
            listOf(PocketVoice(PocketBuiltinVoiceProfile.ID, PocketBuiltinVoiceProfile.NAME,
                PocketBuiltinVoiceProfile.profile.voiceFileName, true, PocketBuiltinVoiceProfile.profile))
        )
    }

    @Test fun variantsAreExactlyControlSeparateAndSeparateLsd3() {
        val a = PocketIsolationVariant.A_CONTROL.runtimeConfig()
        val b = PocketIsolationVariant.B_KV_SEPARATE.runtimeConfig()
        val c = PocketIsolationVariant.C_KV_SEPARATE_LSD3.runtimeConfig()
        assertEquals(PocketKvMode.CURRENT_SINGLE_BUFFER, a.kvMode)
        assertEquals(1, a.lsdSteps)
        assertEquals(PocketKvMode.SEPARATE_INPUT_OUTPUT, b.kvMode)
        assertEquals(1, b.lsdSteps)
        assertEquals(PocketKvMode.SEPARATE_INPUT_OUTPUT, c.kvMode)
        assertEquals(3, c.lsdSteps)
        assertEquals(a.randomSeed, b.randomSeed)
        assertEquals(b.randomSeed, c.randomSeed)
        assertNotEquals(0L, a.randomSeed)
    }

    @Test fun normalChatRuntimeRemainsCurrentKvAndPackLsdWithoutLabSeed() {
        val normal = PocketRuntimeConfig.normal(pack())
        assertEquals(PocketKvMode.CURRENT_SINGLE_BUFFER, normal.kvMode)
        assertEquals(1, normal.lsdSteps)
        assertEquals(0L, normal.randomSeed)
    }

    @Test fun transportModesAreLabOnlyAndUseTheApprovedLimit() {
        assertEquals(null, PocketTransportMode.CURRENT_WRITES.maxWriteBytes)
        assertEquals(8_192, PocketTransportMode.FRAGMENTED_8192.maxWriteBytes)
        assertEquals(4_096, PocketTransportMode.FRAGMENTED_8192.maxWriteBytes!! / 2)
        assertEquals(PocketAudioFormat.SAMPLE_RATE, PocketIsolationLabSpec.SAMPLE_RATE)
    }

    @Test fun everyPlanPinsSameTextVoiceModelTemperatureAndSampleRate() {
        val pack = pack()
        val plans = PocketIsolationVariant.entries.map { PocketIsolationPlan.create(pack, pack.voices, it) }
        assertEquals(1, plans.map { it.text }.distinct().size)
        assertEquals(PocketIsolationLabSpec.TEXT, plans.first().text)
        assertTrue(plans.all { it.voice.id == PocketBuiltinVoiceProfile.ID })
        assertTrue(plans.all { it.voice.profile == PocketBuiltinVoiceProfile.profile })
        assertTrue(plans.all { it.temperature == 0.7f })
        assertTrue(plans.all { it.sampleRate == 24_000 })
    }

    @Test fun labRejectsConcurrentInference() {
        val gate = PocketIsolationGate()
        assertTrue(gate.tryEnter())
        assertFalse(gate.tryEnter())
        assertTrue(gate.isRunning)
        gate.leave()
        assertTrue(gate.tryEnter())
    }

    @Test fun wavNamesAreExactAndVariantSpecific() {
        val cache = temporaryFolder.newFolder("names")
        val names = PocketIsolationVariant.entries.flatMap {
            PocketIsolationLabWav.targets(cache, it).files().map(File::getName)
        }
        assertEquals(listOf(
            "pocket_lab_A_control_f32.wav", "pocket_lab_A_control_pcm16.wav",
            "pocket_lab_B_kv_separate_f32.wav", "pocket_lab_B_kv_separate_pcm16.wav",
            "pocket_lab_C_kv_separate_lsd3_f32.wav", "pocket_lab_C_kv_separate_lsd3_pcm16.wav"
        ), names)
        assertEquals(names.size, names.distinct().size)
    }

    @Test fun successfulPairsPublishAtomicallyAndShareOnlyExistingCommittedFiles() {
        val cache = temporaryFolder.newFolder("publish")
        val a = PocketIsolationLabWav.beginCapture(cache, PocketIsolationVariant.A_CONTROL)
        writePair(a, floatArrayOf(-0.4f, 0.2f))
        assertTrue(a.publish())
        val files = PocketIsolationLabWav.committedFiles(cache)
        val before = files.map(File::readBytes)
        val uris = PocketIsolationLabWav.shareUris(files) { "content://lab/${it.name}" }
        assertEquals(2, files.size)
        assertEquals(2, uris.size)
        assertEquals(2, uris.distinct().size)
        assertTrue(files.zip(before).all { (file, bytes) -> file.readBytes().contentEquals(bytes) })
        assertEquals("android.intent.action.SEND_MULTIPLE", PocketIsolationLabWav.SHARE_ACTION)
    }

    @Test fun newRunInvalidatesOnlyItsOldVariantAndFailurePublishesNothing() {
        val cache = temporaryFolder.newFolder("failure")
        val first = PocketIsolationLabWav.beginCapture(cache, PocketIsolationVariant.B_KV_SEPARATE)
        writePair(first, floatArrayOf(0.1f, 0.3f))
        assertTrue(first.publish())
        assertEquals(2, PocketIsolationLabWav.committedFiles(cache).size)

        val failed = PocketIsolationLabWav.beginCapture(cache, PocketIsolationVariant.B_KV_SEPARATE)
        PocketFloatWavWriter(failed.stagedTargets.float32File).apply {
            append(floatArrayOf(0.5f)); finish()
        }
        assertFalse(failed.publish())
        assertTrue(PocketIsolationLabWav.committedFiles(cache).isEmpty())
        assertFalse(failed.targets.files().any(File::exists))
    }

    @Test fun cancellationRemovesPendingAndFinalPair() {
        val cache = temporaryFolder.newFolder("cancel")
        val capture = PocketIsolationLabWav.beginCapture(cache, PocketIsolationVariant.C_KV_SEPARATE_LSD3)
        writePair(capture, floatArrayOf(-0.2f, 0.6f))
        capture.abort()
        assertTrue(PocketIsolationLabWav.committedFiles(cache).isEmpty())
        assertFalse(capture.targets.files().any(File::exists))
        assertFalse(capture.stagedTargets.files().any(File::exists))
    }

    @Test fun nativeSeparateModeUsesDistinctStorageAndExplicitOutputToInputSwap() {
        val source = sequenceOf(
            File("vendor/PocketTTS.cpp/pocket_tts.cpp"),
            File("../vendor/PocketTTS.cpp/pocket_tts.cpp")
        ).firstOrNull(File::isFile)?.readText() ?: error("PocketTTS.cpp source unavailable")
        assertTrue(source.contains("assert_fp16_storage_is_separate"))
        assertTrue(source.contains("f16[0][i].data() == f16[1][i].data()"))
        assertTrue(source.contains("const int target_buffer = state_.fp16_buf(state_.out_buf())"))
        assertTrue(source.contains("std::memcpy(our_ptr, ort_ptr, output_count * sizeof(uint16_t))"))
        assertTrue(source.contains("state_.swap();"))
        assertTrue(source.contains("if (separate_fp16_)"))
    }

    private fun writePair(capture: PocketWavCapture, samples: FloatArray) {
        PocketFloatWavWriter(capture.stagedTargets.float32File).apply { append(samples); finish() }
        PocketWavWriter(capture.stagedTargets.pcm16File).apply {
            append(PocketPcm.floatToPcm16(samples)); finish()
        }
    }
}
