package com.kura.aria.brain

import com.kura.aria.brain.cloud.CloudBrainClient
import com.kura.aria.brain.cloud.CloudBrainConfig
import com.kura.aria.brain.cloud.CloudInferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AriaBrainEngineTest {
    @Test fun requestRejectsInvalidTokenBudget() {
        val failure = runCatching { BrainRequest("hola", 0) }
        assertTrue(failure.isFailure)
    }

    @Test fun cloudEngineUsesInjectedClientWithoutProviderKnowledge() = runBlocking {
        val client = object : CloudBrainClient {
            override fun stream(request: BrainRequest): Flow<String> = flowOf("Ho", "la")
        }
        val engine: AriaBrainEngine = CloudInferenceEngine(
            CloudBrainConfig(endpoint = "https://example.invalid/aria"), client
        ).also { (it as CloudInferenceEngine).connect() }

        assertEquals(BrainState.Ready, engine.state)
        assertEquals(listOf("Ho", "la"), engine.generate(BrainRequest("saluda", 32)).toList())
        assertEquals(BrainState.Ready, engine.state)
        engine.close()
        assertEquals(BrainState.Disconnected, engine.state)
    }
}
