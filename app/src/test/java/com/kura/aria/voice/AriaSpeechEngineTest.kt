package com.kura.aria.voice

import com.kura.aria.emotion.AriaEmotion
import com.kura.aria.personality.ExpressionStyle
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AriaSpeechEngineTest {
    @Test fun missingModelFallsBackToAndroidTts() = runBlocking {
        val engine = PiperNeuralSpeechEngine(Files.createTempDirectory("aria-no-model").toFile())
        assertEquals(SpeechEngineState.UNAVAILABLE, engine.initialize())
        assertEquals(SpeechEngineSelection.ANDROID_TTS_FALLBACK, selectSpeechEngine(engine.state))
    }

    @Test fun missingRuntimeDoesNotPretendNeuralIsReady() = runBlocking {
        val dir = Files.createTempDirectory("aria-model").toFile()
        File(dir, PiperSpanishPrototype.MODEL_FILE).writeText("placeholder")
        File(dir, PiperSpanishPrototype.TOKENS_FILE).writeText("placeholder")
        File(dir, PiperSpanishPrototype.DATA_DIR).mkdirs()
        val engine = PiperNeuralSpeechEngine(dir)
        assertEquals(SpeechEngineState.UNAVAILABLE, engine.initialize())
        assertTrue(engine.lastError!!.contains("sherpa-onnx"))
    }

    @Test fun fakeBackendLoadsOnceAndReceivesSanitizedText() = runBlocking {
        val dir = Files.createTempDirectory("aria-fake-model").toFile()
        File(dir, PiperSpanishPrototype.MODEL_FILE).writeText("placeholder")
        File(dir, PiperSpanishPrototype.TOKENS_FILE).writeText("placeholder")
        File(dir, PiperSpanishPrototype.DATA_DIR).mkdirs()
        var loads = 0
        var received = ""
        val cancelled = AtomicBoolean(false)
        val fake = object : PiperNeuralBackend {
            override suspend fun load(modelDirectory: File) { loads++ }
            override suspend fun synthesize(request: SpeechSynthesisRequest): SpeechAudio {
                received = request.text
                return SpeechAudio(shortArrayOf(0, 1), PiperSpanishPrototype.SAMPLE_RATE_HZ, 2)
            }
            override fun cancel() { cancelled.set(true) }
            override fun close() = Unit
        }
        val engine = PiperNeuralSpeechEngine(dir, fake)
        assertEquals(SpeechEngineState.READY, engine.initialize())
        assertEquals(SpeechEngineState.READY, engine.initialize())
        assertEquals(1, loads)
        val result = engine.synthesize(SpeechSynthesisRequest(
            text = "*sonríe* Hola Kura",
            emotion = AriaEmotion.HAPPY,
            expressionStyle = ExpressionStyle.PLAYFUL
        ))
        assertTrue(result.isSuccess)
        assertEquals("Hola Kura", received)
        engine.cancel()
        assertTrue(cancelled.get())
    }

    @Test fun onlyActionProducesNoNeuralRequest() = runBlocking {
        val dir = Files.createTempDirectory("aria-action-model").toFile()
        File(dir, PiperSpanishPrototype.MODEL_FILE).writeText("placeholder")
        File(dir, PiperSpanishPrototype.TOKENS_FILE).writeText("placeholder")
        File(dir, PiperSpanishPrototype.DATA_DIR).mkdirs()
        var calls = 0
        val fake = object : PiperNeuralBackend {
            override suspend fun load(modelDirectory: File) = Unit
            override suspend fun synthesize(request: SpeechSynthesisRequest): SpeechAudio {
                calls++
                return SpeechAudio(shortArrayOf(0), 22_050, 1)
            }
            override fun cancel() = Unit
            override fun close() = Unit
        }
        val engine = PiperNeuralSpeechEngine(dir, fake)
        engine.initialize()
        assertTrue(engine.synthesize(SpeechSynthesisRequest("*sonríe*")).isFailure)
        assertEquals(0, calls)
    }

    @Test fun nativeSynthesisFailureIsReturnedAndDisablesNeuralEngine() = runBlocking {
        val dir = installedModelDirectory("aria-native-failure")
        val backend = object : PiperNeuralBackend {
            override suspend fun load(modelDirectory: File) = Unit
            override suspend fun synthesize(request: SpeechSynthesisRequest): SpeechAudio =
                throw IllegalStateException("native generation failed")
            override fun cancel() = Unit
            override fun close() = Unit
        }
        val engine = PiperNeuralSpeechEngine(dir, backend)
        assertEquals(SpeechEngineState.READY, engine.initialize())

        val result = engine.synthesize(SpeechSynthesisRequest("Hola Kura"))

        assertTrue(result.isFailure)
        assertEquals(SpeechEngineState.ERROR, engine.state)
        assertTrue(engine.lastError!!.contains("IllegalStateException"))
        assertEquals(SpeechEngineSelection.ANDROID_TTS_FALLBACK, selectSpeechEngine(engine.state))
    }

    @Test fun malformedPcmIsRejectedBeforePlayback() {
        assertTrue(runCatching { validateSpeechAudio(SpeechAudio(shortArrayOf(), 22_050, 1)) }.isFailure)
        assertTrue(runCatching { validateSpeechAudio(SpeechAudio(shortArrayOf(1), -1, 1)) }.isFailure)
        assertEquals(22_050, validateSpeechAudio(SpeechAudio(shortArrayOf(1), 22_050, 1)).sampleRateHz)
    }

    @Test fun sherpaGenerationUsesNonCallbackRuntimePath() = runBlocking {
        var seenText = ""
        var seenSpeed = 0f
        var released = false
        val backend = SherpaPiperBackend(PiperOfflineTtsRuntimeFactory {
            object : PiperOfflineTtsRuntime {
                override fun generate(text: String, speed: Float, silenceScale: Float): PiperGeneratedSamples {
                    seenText = text
                    seenSpeed = speed
                    return PiperGeneratedSamples(floatArrayOf(-1f, 0f, 0.5f), 22_050)
                }
                override fun release() { released = true }
            }
        })
        backend.load(Files.createTempDirectory("aria-sherpa-runtime").toFile())

        val audio = backend.synthesize(SpeechSynthesisRequest("Prueba sin callback", speed = 9f))

        assertEquals("Prueba sin callback", seenText)
        assertEquals(1.15f, seenSpeed)
        assertEquals(listOf((-32767).toShort(), 0.toShort(), 16383.toShort()), audio.pcm16.toList())
        assertTrue(audio.firstAudioMs == null)
        backend.close()
        assertTrue(released)
    }

    @Test fun labProfileAddsRealPcmPauseBetweenSentenceGenerations() = runBlocking {
        val calls = mutableListOf<String>()
        val backend = SherpaPiperBackend(PiperOfflineTtsRuntimeFactory {
            object : PiperOfflineTtsRuntime {
                override fun generate(text: String, speed: Float, silenceScale: Float): PiperGeneratedSamples {
                    calls += text
                    return PiperGeneratedSamples(floatArrayOf(0.5f), 22_050)
                }
                override fun release() = Unit
            }
        })
        backend.load(Files.createTempDirectory("aria-piper-profile").toFile())

        val audio = backend.synthesize(SpeechSynthesisRequest(
            text = "Hola. ¿Cómo estás?",
            performanceProfile = VoicePerformanceProfile(1f, 100)
        ))

        assertEquals(listOf("Hola.", "¿Cómo estás?"), calls)
        assertEquals(2 + 2_205, audio.pcm16.size)
        assertEquals(16_383.toShort(), audio.pcm16.first())
        assertEquals(16_383.toShort(), audio.pcm16.last())
        assertTrue(audio.pcm16.sliceArray(1 until audio.pcm16.lastIndex).all { it == 0.toShort() })
        backend.close()
    }

    private fun installedModelDirectory(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().apply {
            File(this, PiperSpanishPrototype.MODEL_FILE).writeText("placeholder")
            File(this, PiperSpanishPrototype.TOKENS_FILE).writeText("placeholder")
            File(this, PiperSpanishPrototype.DATA_DIR).mkdirs()
        }
}
