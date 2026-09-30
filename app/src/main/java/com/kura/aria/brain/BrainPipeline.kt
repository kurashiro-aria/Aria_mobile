package com.kura.aria.brain

import com.kura.aria.personality.AriaPersonality
import java.util.UUID

/** Simple serializable boundary builder shared by local, mock and future Cloud engines. */
object BrainPipeline {
    fun request(preparedConversationPrompt: String, maxOutputTokens: Int, requestId: String = UUID.randomUUID().toString()): BrainRequest =
        BrainRequest(
            prompt = AriaPersonality.directResponsePrompt(preparedConversationPrompt),
            maxOutputTokens = maxOutputTokens,
            requestId = requestId
        )

    fun retry(preparedRetryPrompt: String, maxOutputTokens: Int = 160, requestId: String = UUID.randomUUID().toString()): BrainRequest =
        BrainRequest(
            prompt = AriaPersonality.directResponsePrompt(preparedRetryPrompt),
            maxOutputTokens = maxOutputTokens,
            requestId = requestId
        )
}
