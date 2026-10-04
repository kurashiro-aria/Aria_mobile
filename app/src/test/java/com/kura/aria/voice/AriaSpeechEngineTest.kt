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
}
