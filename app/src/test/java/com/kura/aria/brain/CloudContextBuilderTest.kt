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
        assertFalse(prompt.contains("• «turno Kura 1»"))
    }

    @Test fun duplicateMemoryIdsAppearOnce() {
        val history = listOf(ChatMessage("ARIA", "Hablábamos de Xiaomi", 1))
        val memory = Memory(7, "El teléfono actual es Xiaomi")
        val turn = ConversationBrain.interpret(history, "¿y eso?")
        val prompt = CloudContextBuilder.build(history, listOf(memory, memory), "¿y eso?",
            ConversationState(), StylePreferences(), turn)
        assertEquals(1, Regex("El teléfono actual es Xiaomi").findAll(prompt).count())
    }

    @Test fun cloudPromptKeepsAriaIdentityMemoryEmotionRolesAndCurrentMessage() {
        val history = listOf(
            ChatMessage("Kura", "Estoy trabajando en ARIA Mobile", 1),
            ChatMessage("ARIA", "¿Retomamos la interfaz?", 2)
        )
        val state = ConversationState(
            topic = "ARIA Mobile",
            socialMood = com.kura.aria.emotion.ConversationMood.PLAYFUL,
            updatedAt = System.currentTimeMillis(),
            expression = ExpressionState(ExpressionStyle.PLAYFUL, 0.6f, requestedByKura = true)
        )
        val current = "Sí, hagámoslo con calma"
        val turn = ConversationBrain.interpret(history, current, state)
        val prompt = CloudContextBuilder.build(history,
            listOf(Memory(11, "Kura prefiere respuestas directas", category = "preferencia_conversacion")),
            current, state, StylePreferences(), turn)

        assertTrue(prompt.contains("IDENTIDAD Y PERSONALIDAD ESTABLE DE ARIA"))
        assertTrue(prompt.contains("Eres ARIA"))
        assertTrue(prompt.contains("TONO DE ESTE TURNO"))
        assertTrue(prompt.contains("Kura prefiere respuestas directas"))
        assertTrue(prompt.contains("Kura dijo:"))
        assertTrue(prompt.contains("ARIA dijo:"))
        assertTrue(prompt.endsWith("MENSAJE ACTUAL DE KURA:\n$current"))
        assertFalse(prompt.contains("MENSAJE ACTUAL DE ARIA"))
    }

    @Test fun roleplayKeepsKurasPerspectiveAndExcludesMemories() {
        val current = "*me siento a su lado*"
        val turn = ConversationBrain.interpret(emptyList(), current)
        val prompt = CloudContextBuilder.build(emptyList(), listOf(Memory(9, "dato ajeno")),
            current, ConversationState(), StylePreferences(), turn)

        assertTrue(prompt.contains("acciones ficticias de Kura"))
        assertTrue(prompt.endsWith(current))
        assertFalse(prompt.contains("dato ajeno"))
    }

    @Test fun retryPreservesIdentityPersonalityMemoryEmotionAndCurrentMessage() {
        val current = "sí, sigamos con eso"
        val history = listOf(
            ChatMessage("Kura", "Estoy arreglando ARIA", 1),
            ChatMessage("ARIA", "¿Quieres que sigamos con calma?", 2)
        )
        val state = ConversationState(
            socialMood = com.kura.aria.emotion.ConversationMood.PLAYFUL,
            expression = ExpressionState(ExpressionStyle.AFFECTIONATE, 0.8f, requestedByKura = true)
        )
        val turn = ConversationBrain.interpret(history, current, state)
        val initial = CloudContextBuilder.build(history,
            listOf(Memory(3, "Kura prefiere que ARIA sea cercana")), current,
            state, StylePreferences(), turn)
        val retry = CloudContextBuilder.retry(initial,
            "Da una respuesta nueva sin repetir la anterior.")

        assertTrue(retry.contains("IDENTIDAD Y PERSONALIDAD ESTABLE DE ARIA"))
        assertTrue(retry.contains("Eres ARIA"))
        assertTrue(retry.contains("TONO DE ESTE TURNO"))
        assertTrue(retry.contains("Kura prefiere que ARIA sea cercana"))
        assertTrue(retry.contains("MENSAJE ACTUAL DE KURA:\n$current"))
        assertTrue(retry.contains("INSTRUCCIÓN DE REINTENTO"))
        assertTrue(retry.length <= CloudContextLimits().maxCharacters)
    }

    @Test fun boundedRetryKeepsIdentityAndNewestTurnContext() {
        val marker = "\nMENSAJE ACTUAL DE KURA:\n"
        val base = "IDENTIDAD Y PERSONALIDAD ESTABLE DE ARIA:\nEres ARIA\n" +
            "x".repeat(5_000) + "\nTONO DE ESTE TURNO: juguetona" + marker + "hola"
        val retry = CloudContextBuilder.retry(base, "No repitas.", maxCharacters = 1_200)

        assertTrue(retry.startsWith("IDENTIDAD Y PERSONALIDAD ESTABLE DE ARIA"))
        assertTrue(retry.contains("TONO DE ESTE TURNO"))
        assertTrue(retry.contains("MENSAJE ACTUAL DE KURA:\nhola"))
        assertTrue(retry.contains("INSTRUCCIÓN DE REINTENTO"))
        assertTrue(retry.length <= 1_200)
    }
}
