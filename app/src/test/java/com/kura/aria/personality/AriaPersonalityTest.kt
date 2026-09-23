package com.kura.aria.personality

import com.kura.aria.chat.ChatMessage
import com.kura.aria.memory.Memory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AriaPersonalityTest {
    @Test fun fixedIdentityIsSeparateFromLiveConversation() {
        val prompt = AriaPersonality.systemPrompt()
        assertTrue(prompt.contains("Adaptive Reasoning & Interactive Assistant"))
        assertTrue(prompt.contains("22 de septiembre de 2026"))
        assertFalse(prompt.contains("CONTEXTO RECIENTE:"))
        assertTrue(prompt.endsWith("/no_think"))
    }

    @Test fun currentTurnKeepsNonThinkingInstructionLast() {
        val prompt = AriaPersonality.directResponsePrompt("¿Cómo te fue? /think")
        assertTrue(prompt.endsWith("/no_think"))
    }

    @Test fun currentMessageComesAfterRecentDialogAndSourcedMemories() {
        val history = listOf(
            ChatMessage("Kura", "Tengo un gato", 1),
            ChatMessage("ARIA", "¿Cómo se llama?", 2)
        )
        val prompt = ConversationContext.turnPrompt(history, listOf(Memory(7, "El gato se llama Nube")), "Se llama Nube")
        assertTrue(prompt.indexOf("Kura: Tengo un gato") < prompt.indexOf("Guardado por Kura #7"))
        assertTrue(prompt.indexOf("Guardado por Kura #7") < prompt.indexOf("MENSAJE ACTUAL DE KURA:"))
        assertTrue(prompt.endsWith("MENSAJE ACTUAL DE KURA:\nSe llama Nube"))
        assertTrue(prompt.contains("¿Cómo se llama?"))
        assertFalse(prompt.contains("ARIA: ¿Cómo se llama?"))
    }

    @Test fun olderUserFactIsLabeledAsHistoryWithoutRepeatingAria() {
        val history = listOf(
            ChatMessage("Kura", "Hablemos del manga de Sora", 1),
            ChatMessage("ARIA", "Sora entra a la mazmorra", 2)
        ) + (3..10).map { ChatMessage("Kura", "Tema cotidiano $it", it.toLong()) }
        val prompt = ConversationContext.turnPrompt(history, emptyList(), "Volvamos al manga")
        assertTrue(prompt.contains("Fragmentos anteriores del historial (no guardados)"))
        assertTrue(prompt.contains("Hablemos del manga de Sora"))
        assertFalse(prompt.contains("Sora entra a la mazmorra"))
        assertFalse(prompt.contains("Guardado por Kura"))
    }

    @Test fun historyIsBoundedAndDoesNotInventYesterday() {
        val history = (1..14).map { ChatMessage("Kura", "Mensaje $it " + "x".repeat(250), it.toLong()) }
        val prompt = ConversationContext.turnPrompt(history, emptyList(), "Otra cosa")
        assertFalse(prompt.contains("Mensaje 8 "))
        assertFalse(prompt.contains("Mensaje 9 "))
        assertFalse(prompt.contains("Mensaje 14 "))
        assertFalse(prompt.contains("x".repeat(250)))
        assertFalse(prompt.contains("ayer hablamos"))
    }

    @Test fun greetingAfterUnrelatedTopicDoesNotPrimeOldAnswer() {
        val history = listOf(
            ChatMessage("Kura", "No me gusta el café", 1),
            ChatMessage("ARIA", "¿Te gustaría tomar café en un lugar tranquilo?", 2)
        )
        val prompt = ConversationContext.turnPrompt(history, emptyList(), "hola aria")
        assertFalse(prompt.contains("café"))
        assertTrue(prompt.endsWith("MENSAJE ACTUAL DE KURA:\nhola aria"))
    }

    @Test fun shortAnswerKeepsTheQuestionItAnswers() {
        val history = listOf(
            ChatMessage("Kura", "Estoy dibujando el manga", 1),
            ChatMessage("ARIA", "¿Quieres probar otra pose para Sora?", 2)
        )
        val prompt = ConversationContext.turnPrompt(history, emptyList(), "sí")
        assertTrue(prompt.contains("¿Quieres probar otra pose para Sora?"))
        assertFalse(prompt.contains("ARIA: ¿Quieres probar otra pose para Sora?"))
        assertTrue(prompt.endsWith("MENSAJE ACTUAL DE KURA:\nsí"))
    }

    @Test fun shortWhyQuestionKeepsPreviousAnswer() {
        val history = listOf(
            ChatMessage("Kura", "Dibujé a Sora", 1),
            ChatMessage("ARIA", "La pose se ve rígida", 2)
        )
        val prompt = ConversationContext.turnPrompt(history, emptyList(), "¿por qué?")
        assertTrue(prompt.contains("La pose se ve rígida"))
        assertFalse(prompt.contains("ARIA: La pose se ve rígida"))
    }

    @Test fun topicContinuationDoesNotPasteThePriorAriaAnswer() {
        val history = listOf(
            ChatMessage("Kura", "Tengo mucho calor", 1),
            ChatMessage("ARIA", "Si odias el calor, quizá necesitas un refugio fresco. ¿Cómo te sientes?", 2)
        )
        val prompt = ConversationContext.turnPrompt(history, emptyList(), "odio el calor jaja")
        assertTrue(prompt.contains("Kura: Tengo mucho calor"))
        assertFalse(prompt.contains("refugio fresco"))
    }

    @Test fun assentGetsOnlyTheLastQuestionNotTheOldAnswer() {
        val history = listOf(
            ChatMessage("Kura", "Tengo mucho calor", 1),
            ChatMessage("ARIA", "Quizá necesitas un refugio más fresco. ¿Te gustaría que fuéramos a un lugar tranquilo?", 2)
        )
        val prompt = ConversationContext.turnPrompt(history, emptyList(), "sí")
        assertTrue(prompt.contains("¿Te gustaría que fuéramos a un lugar tranquilo?"))
        assertFalse(prompt.contains("Quizá necesitas un refugio"))
    }
}
