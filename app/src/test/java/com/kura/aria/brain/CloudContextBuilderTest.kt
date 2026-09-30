package com.kura.aria.brain

import com.kura.aria.chat.ChatMessage
import com.kura.aria.memory.Memory
import com.kura.aria.personality.*
import org.junit.Assert.*
import org.junit.Test

class CloudContextBuilderTest {
    @Test fun sendsOnlyBoundedSelectedContext() {
        val history = (1..20).flatMap { index -> listOf(
            ChatMessage("Kura", "turno Kura $index", index * 2L),
            ChatMessage("ARIA", "turno ARIA $index", index * 2L + 1)) }
        val turn = ConversationBrain.interpret(history, "¿y eso?", ConversationState())
        val memories = (1..20).map { Memory(it.toLong(), "recuerdo único $it") }
        val prompt = CloudContextBuilder.build(history, memories, "¿y eso?", ConversationState(),
            StylePreferences(), turn, CloudContextLimits(maxRecentTurns = 4, maxMemories = 3, maxCharacters = 2_000))
        assertTrue(prompt.length <= 2_000)
        assertTrue(prompt.contains("MENSAJE ACTUAL DE KURA"))
        assertTrue(prompt.endsWith("¿y eso?"))
        assertFalse(prompt.contains("recuerdo único 4"))
        assertFalse(prompt.contains("turno Kura 1"))
    }

    @Test fun duplicateMemoryIdsAppearOnce() {
        val history = listOf(ChatMessage("ARIA", "Hablábamos de Xiaomi", 1))
        val memory = Memory(7, "El teléfono actual es Xiaomi")
        val turn = ConversationBrain.interpret(history, "¿y eso?")
        val prompt = CloudContextBuilder.build(history, listOf(memory, memory), "¿y eso?",
            ConversationState(), StylePreferences(), turn)
        assertEquals(1, Regex("El teléfono actual es Xiaomi").findAll(prompt).count())
    }
}
