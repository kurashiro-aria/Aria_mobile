package com.kura.aria.personality

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class InitiativePolicyTest {
    private val hour = 60 * 60 * 1000L
    private val state = ConversationState().afterExchange("Mi manga de Sora", "¿Lo retomamos?", hour)

    @Test fun needsOptInAndARealPendingQuestion() {
        assertNull(InitiativePolicy.suggestion(state, false, 4 * hour, 0, 12))
        assertNull(InitiativePolicy.suggestion(state.copy(pendingQuestion = ""), true, 4 * hour, 0, 12))
        assertNotNull(InitiativePolicy.suggestion(state, true, 4 * hour, 0, 12))
    }

    @Test fun respectsHoursCooldownAndNewActivity() {
        assertNull(InitiativePolicy.suggestion(state, true, 2 * hour, 0, 12))
        assertNull(InitiativePolicy.suggestion(state, true, 4 * hour, 0, 23))
        assertNull(InitiativePolicy.suggestion(state, true, 4 * hour, 3 * hour, 12))
    }

    @Test fun legacyRoleplayActionCannotBecomeInitiativeReminder() {
        val legacy = state.copy(topic = "*acaricio la cabeza de aria*", pendingQuestion = "¿Seguimos?")
        assertNull(InitiativePolicy.suggestion(legacy, true, 4 * hour, 0, 12))
    }

    @Test fun oldSavedAssentCannotBecomeInitiativeReminder() {
        val legacy = state.copy(topic = "si,uno de uva", pendingQuestion = "¿Qué te pasa?")
        assertNull(InitiativePolicy.suggestion(legacy, true, 4 * hour, 0, 12))
    }
    @Test fun answeredHungerAndCasualConversationAreNotPendingEvenAfterIdle() {
        val hunger = ConversationState().afterExchange("que tengo hambre",
            "mmm, ¿quieres que te prepare un bocadillo? o tal vez un refresco?", hour)
        assertNull(InitiativePolicy.suggestion(hunger, true, 4 * hour, 0, 12))
        val continued = hunger.afterExchange("sí, uno de uva", "Buena elección.", 2 * hour)
        assertNull(InitiativePolicy.suggestion(continued, true, 5 * hour, 0, 12))
        val greeting = continued.afterExchange("hola", "Hola, Kura.", 3 * hour)
        assertNull(InitiativePolicy.suggestion(greeting, true, 6 * hour, 0, 12))
    }

    @Test fun aDeferredThreadExpiresOnTheNextNormalExchange() {
        assertNotNull(InitiativePolicy.suggestion(state, true, 4 * hour, 0, 12))
        val answered = state.afterExchange("Seguimos con el manga", "Perfecto, avancemos.", 2 * hour)
        assertNull(InitiativePolicy.suggestion(answered, true, 5 * hour, 0, 12))
    }
}
