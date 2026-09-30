package com.kura.aria.brain

import com.kura.aria.brain.mock.MockCloudBrainEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MockCloudBrainEngineTest {
    @Test fun returnsResponseAndCapturesSimpleRequest() = runBlocking {
        val engine = MockCloudBrainEngine(latencyMs = 0) { "mock-ok" }
        engine.connect()
        val request = BrainRequest("personalidad + contexto + mensaje", 64, "req-1")
        assertEquals(listOf("mock-ok"), engine.generate(request).toList())
        assertEquals(request, engine.lastRequest)
        assertEquals(BrainState.Ready, engine.state)
    }

    @Test fun readyGeneratingReadyTransitionIsObservable() = runBlocking {
        val engine = MockCloudBrainEngine(latencyMs = 80)
        engine.connect(); assertEquals(BrainState.Ready, engine.state)
        val job = launch { engine.generate(BrainRequest("hola", 32)).toList() }
        kotlinx.coroutines.delay(10)
        assertEquals(BrainState.Generating, engine.state)
        job.join()
        assertEquals(BrainState.Ready, engine.state)
    }

    @Test fun simulatedErrorMovesToError() = runBlocking {
        val engine = MockCloudBrainEngine(latencyMs = 0); engine.connect()
        try { engine.generate(BrainRequest("[MOCK_ERROR]", 32)).toList(); fail("Expected failure") }
        catch (_: IllegalStateException) { }
        assertTrue(engine.state is BrainState.Error)
    }

    @Test fun cancellationReturnsEngineToReady() = runBlocking {
        val engine = MockCloudBrainEngine(latencyMs = 5_000); engine.connect()
        val job = launch { engine.generate(BrainRequest("cancel me", 32)).toList() }
        kotlinx.coroutines.delay(10); job.cancelAndJoin()
        assertEquals(BrainState.Ready, engine.state)
    }

    @Test fun emptyResponseIsSupportedForPipelineValidation() = runBlocking {
        val engine = MockCloudBrainEngine(latencyMs = 0); engine.connect()
        assertTrue(engine.generate(BrainRequest("[MOCK_EMPTY]", 32)).toList().isEmpty())
        assertEquals(BrainState.Ready, engine.state)
    }

    @Test fun availabilityDoesNotRequireLlamaModelReady() {
        val engine = MockCloudBrainEngine(latencyMs = 0)
        engine.connect()
        assertEquals(BrainState.Ready, engine.state)
    }

    @Test fun brainPipelineBuildsProviderNeutralRequestWithAriaInstructions() {
        val request = BrainPipeline.request("contexto preparado por ConversationBrain", 384, "turn-42")
        assertEquals(384, request.maxOutputTokens)
        assertEquals("turn-42", request.requestId)
        assertTrue(request.prompt.contains("contexto preparado por ConversationBrain"))
        assertFalse(request.prompt.contains("http://"))
        assertFalse(request.prompt.contains("https://"))
    }

    @Test fun mockPipelineNeedsNoGgufPath() = runBlocking {
        val engine = MockCloudBrainEngine(latencyMs = 0) { "sin gguf" }; engine.connect()
        val request = BrainPipeline.request("turno ARIA", 32, "no-file")
        assertEquals("sin gguf", engine.generate(request).toList().single())
    }
}
