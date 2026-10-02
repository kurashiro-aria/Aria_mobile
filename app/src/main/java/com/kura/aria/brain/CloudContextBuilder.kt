package com.kura.aria.brain

import com.kura.aria.chat.ChatMessage
import com.kura.aria.memory.Memory
import com.kura.aria.personality.*

internal data class CloudContextLimits(
    val maxRecentTurns: Int = 4,
    val maxMemories: Int = 3,
    val maxCharacters: Int = 7_500
) {
    init { require(maxRecentTurns in 1..12); require(maxMemories in 0..10); require(maxCharacters >= 1_000) }
}

/** Builds the only conversation payload allowed to leave the device. */
internal object CloudContextBuilder {
    fun build(history: List<ChatMessage>, memories: List<Memory>, current: String,
              state: ConversationState, preferences: StylePreferences,
              turn: ConversationTurn, limits: CloudContextLimits = CloudContextLimits()): String {
        val boundedTurn = turn.copy(recent = turn.recent.takeLast(limits.maxRecentTurns))
        val uniqueMemories = memories.distinctBy { it.id }.take(limits.maxMemories)
        val prompt = buildString {
            append("IDENTIDAD Y PERSONALIDAD ESTABLE DE ARIA:\n")
            append(AriaPersonality.systemPrompt())
            append("\n\nCONTEXTO DEL TURNO:\n")
            append(ConversationContext.turnPrompt(history, uniqueMemories, current, state,
                preferences, boundedTurn))
        }
        if (prompt.length <= limits.maxCharacters) return prompt
        val marker = "\nMENSAJE ACTUAL DE KURA:\n"
        val tail = prompt.substringAfterLast(marker, current).takeLast(limits.maxCharacters / 2)
        val headBudget = (limits.maxCharacters - marker.length - tail.length).coerceAtLeast(0)
        return prompt.take(headBudget).trimEnd() + marker + tail
    }
}
