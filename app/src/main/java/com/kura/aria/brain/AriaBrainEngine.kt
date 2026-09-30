package com.kura.aria.brain

import kotlinx.coroutines.flow.Flow

/** Provider-neutral contract between ARIA's conversation layer and text inference. */
interface AriaBrainEngine {
    val state: BrainState

    /** Generate raw model chunks. ARIA identity/filtering stays outside the engine. */
    fun generate(request: BrainRequest): Flow<String>

    /** Release provider-specific resources. Safe to call more than once. */
    suspend fun close()
}

sealed interface BrainState {
    data object Disconnected : BrainState
    data object Connecting : BrainState
    data object Ready : BrainState
    data object Generating : BrainState
    data class Error(val message: String, val recoverable: Boolean = true) : BrainState
}

data class BrainRequest(
    val prompt: String,
    val maxOutputTokens: Int,
    val requestId: String? = null
) {
    init {
        require(prompt.isNotBlank()) { "BrainRequest.prompt must not be blank" }
        require(maxOutputTokens > 0) { "BrainRequest.maxOutputTokens must be positive" }
    }
}

data class BrainResponse(
    val text: String,
    val requestId: String? = null,
    val finishReason: String? = null
)
