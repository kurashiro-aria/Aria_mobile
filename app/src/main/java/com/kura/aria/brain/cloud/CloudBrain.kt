package com.kura.aria.brain.cloud

import com.kura.aria.brain.AriaBrainEngine
import com.kura.aria.brain.BrainRequest
import com.kura.aria.brain.BrainState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Configuration intentionally contains no API key and no provider-specific fields. */
data class CloudBrainConfig(
    val endpoint: String,
    val connectTimeoutMs: Long = 15_000,
    val requestTimeoutMs: Long = 90_000
) {
    init {
        require(endpoint.isNotBlank()) { "Cloud endpoint must not be blank" }
        require(connectTimeoutMs > 0 && requestTimeoutMs > 0)
    }
}

/** Transport seam. HTTP/auth/provider details belong behind this boundary in Stage 2+. */
interface CloudBrainClient {
    fun stream(request: BrainRequest): Flow<String>
    suspend fun close() = Unit
}

/**
 * Provider-neutral cloud engine. Stage 1 deliberately has no production HTTP client.
 * A client must be injected, which also makes the boundary testable without network access.
 */
class CloudInferenceEngine(
    val config: CloudBrainConfig,
    private val client: CloudBrainClient
) : AriaBrainEngine {
    @Volatile private var currentState: BrainState = BrainState.Disconnected
    override val state: BrainState get() = currentState

    fun connect() {
        // No network operation in Stage 1. Injection proves configuration is complete.
        currentState = BrainState.Ready
    }

    override fun generate(request: BrainRequest): Flow<String> = flow {
        check(currentState == BrainState.Ready) { "Cloud brain is not ready" }
        currentState = BrainState.Generating
        try {
            client.stream(request).collect { emit(it) }
            currentState = BrainState.Ready
        } catch (t: Throwable) {
            currentState = BrainState.Error(t.message ?: t.javaClass.simpleName)
            throw t
        }
    }

    override suspend fun close() {
        client.close()
        currentState = BrainState.Disconnected
    }
}
