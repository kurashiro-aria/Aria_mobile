package com.kura.aria.brain.mock

import com.kura.aria.brain.AriaBrainEngine
import com.kura.aria.brain.BrainRequest
import com.kura.aria.brain.BrainState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Development-only brain used to prove ARIA's Cloud pipeline without GGUF, network or secrets.
 * It intentionally does not try to be intelligent: ARIA identity remains outside this class.
 */
class MockCloudBrainEngine(
    private val latencyMs: Long = 120L,
    private val responder: (BrainRequest) -> String = { request ->
        "Entendido. Estoy respondiendo desde el cerebro Cloud de prueba (${request.requestId ?: "sin-id"})."
    }
) : AriaBrainEngine {
    @Volatile private var currentState: BrainState = BrainState.Disconnected
    override val state: BrainState get() = currentState

    @Volatile var lastRequest: BrainRequest? = null
        private set

    fun connect() {
        currentState = BrainState.Connecting
        currentState = BrainState.Ready
    }

    override fun generate(request: BrainRequest): Flow<String> = flow {
        check(currentState == BrainState.Ready) { "Mock Cloud brain is not ready" }
        lastRequest = request
        currentState = BrainState.Generating
        try {
            delay(latencyMs)
            val text = when {
                request.prompt.contains("[MOCK_ERROR]") -> error("Simulated Mock Cloud failure")
                request.prompt.contains("[MOCK_EMPTY]") -> ""
                else -> responder(request)
            }
            if (text.isNotEmpty()) emit(text)
            currentState = BrainState.Ready
        } catch (cancelled: CancellationException) {
            currentState = BrainState.Ready
            throw cancelled
        } catch (t: Throwable) {
            currentState = BrainState.Error(t.message ?: t.javaClass.simpleName)
            throw t
        }
    }

    override suspend fun close() {
        currentState = BrainState.Disconnected
    }
}
