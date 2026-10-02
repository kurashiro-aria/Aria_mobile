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

    /**
     * Adds a corrective instruction without dropping ARIA's identity or the live turn context.
     * A retry is still an ARIA turn: it must carry personality, memories and expression just as
     * the first request does.
     */
    fun retry(basePrompt: String, retryInstruction: String,
              maxCharacters: Int = CloudContextLimits().maxCharacters): String {
        require(maxCharacters >= 1_000)
        val marker = "\nMENSAJE ACTUAL DE KURA:\n"
        val currentBlock = if (basePrompt.contains(marker))
            marker + basePrompt.substringAfterLast(marker) else ""
        val context = if (currentBlock.isNotEmpty()) basePrompt.substringBeforeLast(marker) else basePrompt
        val correction = "\n\nINSTRUCCIÓN DE REINTENTO:\n${retryInstruction.trim()}"
        val contextBudget = (maxCharacters - currentBlock.length - correction.length).coerceAtLeast(0)
        val boundedContext = when {
            context.length <= contextBudget -> context
            contextBudget == 0 -> ""
            else -> {
                // With a normal budget keep the complete stable identity. With a deliberately
                // tiny budget split the space so the newest mood/memory guidance also survives.
                val identityBudget = if (contextBudget <= 4_200)
                    (contextBudget * 2 / 3).coerceAtLeast(1) else 2_800
                val tailBudget = (contextBudget - identityBudget - 5).coerceAtLeast(0)
                context.take(identityBudget).trimEnd() + "\n[…]\n" + context.takeLast(tailBudget).trimStart()
            }
        }
        return (boundedContext + currentBlock + correction).take(maxCharacters)
    }
}
