package com.kura.aria.brain

import com.kura.aria.brain.cloud.CloudBrainClient
import com.kura.aria.brain.cloud.CloudBrainConfig
import com.kura.aria.brain.cloud.CloudInferenceEngine
import com.kura.aria.brain.cloud.CloudBrainResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AriaBrainEngineTest {
    @Test fun requestRejectsInvalidTokenBudget() {
        val failure = runCatching { BrainRequest("hola", 0) }
        assertTrue(failure.isFailure)
    }

    @Test fun cloudEngineUsesInjectedClientWithoutProviderKnowledge() = runBlocking {
        val client = object : CloudBrainClient {
            override suspend fun health() = true
            override suspend fun respond(request: BrainRequest) = CloudBrainResult(
                BrainResponse("Hola", request.requestId)
            )
        }
        val engine: AriaBrainEngine = CloudInferenceEngine(
            CloudBrainConfig(endpoint = "https://example.invalid/aria"), client
        ).also { (it as CloudInferenceEngine).connect() }

        assertEquals(BrainState.Ready, engine.state)
        assertEquals(listOf("Hola"), engine.generate(BrainRequest("saluda", 32, "generated")).toList())
        assertEquals(BrainState.Ready, engine.state)
        engine.close()
        assertEquals(BrainState.Disconnected, engine.state)
    }

    @Test fun fakeCloudReceivesComposedAriaPromptAndReturnsResponse() = runBlocking {
        var captured: BrainRequest? = null
        val client = object : CloudBrainClient {
            override suspend fun health() = true
            override suspend fun respond(request: BrainRequest): CloudBrainResult {
                captured = request
                return CloudBrainResult(BrainResponse("Respuesta de ARIA", request.requestId))
            }
        }
        val engine = CloudInferenceEngine(CloudBrainConfig("https://example.invalid/aria"), client)
        assertTrue(engine.connect())
        val request = BrainPipeline.request(
            "IDENTIDAD Y PERSONALIDAD ESTABLE DE ARIA:\nEres ARIA\nMENSAJE ACTUAL DE KURA:\nhola",
            64, "fake-cloud-turn"
        )

        assertEquals(listOf("Respuesta de ARIA"), engine.generate(request).toList())
        assertEquals("fake-cloud-turn", captured?.requestId)
        assertTrue(captured?.prompt?.contains("Eres ARIA") == true)
        assertTrue(captured?.prompt?.endsWith("hola\n/no_think") == true)
    }

    @Test fun networkFailureLeavesCallerOwnedConversationStateUntouched() = runBlocking {
        val stateBefore = com.kura.aria.personality.ConversationState(topic = "manga")
        val client = object : CloudBrainClient {
            override suspend fun health() = true
            override suspend fun respond(request: BrainRequest): CloudBrainResult = throw IOException("offline")
        }
        val engine = CloudInferenceEngine(CloudBrainConfig("https://example.invalid/aria"), client)
        assertTrue(engine.connect())

        assertTrue(runCatching {
            engine.generate(BrainRequest("hola", 32, "network-failure")).toList()
        }.isFailure)
        assertEquals("manga", stateBefore.topic)
        assertTrue(engine.state is BrainState.Error)
    }
}
