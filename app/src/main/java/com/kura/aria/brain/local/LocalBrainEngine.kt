package com.kura.aria.brain.local

import com.arm.aichat.InferenceEngine
import com.kura.aria.brain.AriaBrainEngine
import com.kura.aria.brain.BrainRequest
import com.kura.aria.brain.BrainState
import kotlinx.coroutines.flow.Flow

/**
 * Conservative adapter for the existing llama.cpp engine.
 * Model selection/loading remains in the existing local runtime during Stage 1.
 */
class LocalBrainEngine(
    private val delegate: InferenceEngine
) : AriaBrainEngine {
    override val state: BrainState
        get() = when (val local = delegate.state.value) {
            is InferenceEngine.State.ModelReady -> BrainState.Ready
            is InferenceEngine.State.Error -> BrainState.Error("Local inference engine error")
            is InferenceEngine.State.LoadingModel,
            is InferenceEngine.State.ProcessingSystemPrompt,
            is InferenceEngine.State.Initializing -> BrainState.Connecting
            else -> BrainState.Disconnected
        }

    override fun generate(request: BrainRequest): Flow<String> =
        delegate.sendUserPrompt(request.prompt, predictLength = request.maxOutputTokens)

    override suspend fun close() {
        delegate.cleanUp()
    }
}
