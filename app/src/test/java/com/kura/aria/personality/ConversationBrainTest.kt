package com.kura.aria.personality

import com.kura.aria.chat.ChatMessage
import com.kura.aria.memory.Memory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationBrainTest {
    private fun exchange(vararg lines: Pair<String, String>) = lines.mapIndexed { index, line ->
        ChatMessage(line.first, line.second, index.toLong() + 1)
    }

    @Test fun drinkConversationKeepsTheOfferAndBothAnswers() {
        val offer = exchange("Kura" to "Hace calor.", "ARIA" to "¿Quieres un refresco?")
        val yes = ConversationBrain.interpret(offer, "Sí.")
        assertEquals(ConversationIntent.ANSWER, yes.intent)
        assertEquals(TurnContinuity.CONTINUES, yes.continuity)
        assertTrue(ConversationContext.turnPrompt(offer, emptyList(), "Sí.").contains("¿Quieres un refresco?"))

        val flavor = offer + exchange("Kura" to "Sí.", "ARIA" to "¿De qué sabor?")
        val selection = ConversationBrain.interpret(flavor, "Sí, uno de uva.")
        assertEquals(ConversationIntent.ANSWER, selection.intent)
        assertEquals(4, selection.recent.size)
        val prompt = ConversationContext.turnPrompt(flavor, emptyList(), "Sí, uno de uva.")
        assertTrue(prompt.contains("¿Quieres un refresco?"))
        assertTrue(prompt.contains("¿De qué sabor?"))
        assertTrue(prompt.contains("Hace calor."))
        assertTrue(prompt.endsWith("MENSAJE ACTUAL DE KURA:\nSí, uno de uva."))
    }

    @Test fun aShortMessageNeedsEvidenceAndNewWeatherDoesNotAnswerAnOldQuestion() {
        val history = exchange("Kura" to "Estoy dibujando a Sora",
            "ARIA" to "¿Quieres cambiar el fondo?")
        assertEquals(ConversationIntent.ANSWER, ConversationBrain.interpret(history, "sí").intent)
        val statement = exchange("Kura" to "Estoy dibujando a Sora", "ARIA" to "La pose quedó bien.")
        assertEquals(TurnContinuity.NEW, ConversationBrain.interpret(statement, "sí").continuity)
        val weather = ConversationBrain.interpret(history, "Hace calor hoy")
        assertEquals(TurnContinuity.NEW, weather.continuity)
        assertTrue(weather.recent.isEmpty())
        assertFalse(ConversationContext.turnPrompt(history, emptyList(), "Hace calor hoy")
            .contains("cambiar el fondo"))
    }

    @Test fun referencesKeepAnAdjacentReferentButNotAnUnrelatedConversation() {
        val history = exchange("Kura" to "Tengo dos dibujos",
            "ARIA" to "El primero tiene un cielo azul.")
        listOf("¿Y ese?", "¿Y esa?", "el otro", "eso mismo", "lo que dijiste antes", "¿y después?")
            .forEach { reference ->
                val turn = ConversationBrain.interpret(history, reference)
                assertEquals(reference, TurnContinuity.CONTINUES, turn.continuity)
                assertTrue(reference, turn.hasContextReference)
                assertTrue(ConversationContext.turnPrompt(history, emptyList(), reference)
                    .contains("cielo azul"))
            }
        val newTopic = ConversationBrain.interpret(history, "Tengo hambre")
        assertEquals(TurnContinuity.NEW, newTopic.continuity)
        assertTrue(newTopic.recent.isEmpty())
    }

    @Test fun roleplayRemainsKurasActionAndNeverTurnsIntoStoredContext() {
        val action = "*acaricio la cabeza de aria*"
        val initial = ConversationState().afterExchange("Dibujo un manga", "¿Qué escena?", 1)
        val after = initial.afterExchange(action, "*sonrío* Qué detalle.", 2)
        assertEquals(initial.topic, after.topic)
        assertTrue(after.pendingQuestion.isEmpty())
        val history = exchange("Kura" to "Dibujo un manga", "ARIA" to "¿Qué escena?")
        val turn = ConversationBrain.interpret(history, action, after)
        assertEquals(ConversationIntent.ROLEPLAY_ACTION, turn.intent)
        assertTrue(turn.recent.isEmpty())
        val prompt = ConversationContext.turnPrompt(history, listOf(Memory(1, "Cabeza de Aria")), action, after)
        assertEquals(1, Regex(Regex.escape(action)).findAll(prompt).count())
        assertFalse(prompt.contains("Cabeza de Aria"))
        assertFalse(prompt.contains("Dibujo un manga"))

        val mixed = "*me siento a su lado* ¿cómo estás?"
        val mixedTurn = ConversationBrain.interpret(history, mixed)
        assertEquals(ConversationIntent.ROLEPLAY_MIXED, mixedTurn.intent)
        assertEquals("¿cómo estás?", mixedTurn.spokenText)
        assertTrue(ConversationContext.turnPrompt(history, emptyList(), mixed)
            .contains("acciones ficticias de Kura"))
    }

    @Test fun greetingAndFreshTopicDoNotReplayHistoryOrStoredTopics() {
        val history = exchange("Kura" to "Hablemos de Sora",
            "ARIA" to "¿Qué escena dibujas?")
        val state = ConversationState().afterExchange("Hablemos de Sora", "¿Qué escena dibujas?", 1)
        val greeting = ConversationBrain.interpret(history, "hola aria", state)
        assertEquals(ConversationIntent.SOCIAL, greeting.intent)
        assertTrue(greeting.recent.isEmpty())
        assertFalse(ConversationContext.turnPrompt(history, emptyList(), "hola aria", state).contains("Sora"))
        val fresh = ConversationBrain.interpret(history, "La cocina está lista", state)
        assertEquals(TurnContinuity.NEW, fresh.continuity)
        assertFalse(ConversationContext.turnPrompt(history, emptyList(), "La cocina está lista", state)
            .contains("escena dibujas"))
    }
}
