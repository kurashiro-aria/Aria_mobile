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
}
