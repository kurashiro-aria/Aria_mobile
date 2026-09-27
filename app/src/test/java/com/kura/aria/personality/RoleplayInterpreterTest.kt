package com.kura.aria.personality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleplayInterpreterTest {
    @Test fun touchDirectedAtAriaIsStructuredAsKuraActionTowardAria() {
        val actions = RoleplayInterpreter.actions("*acaricio la cabeza de aria*")
        assertEquals(1, actions.size)
        assertEquals("Kura", actions.first().actor)
        assertEquals("ARIA", actions.first().target)
        assertEquals("acaricio la cabeza de aria", actions.first().action)
    }

    @Test fun directActionPromptRequestsReactionInsteadOfNarratingKura() {
        val prompt = RoleplayInterpreter.promptContext("*acaricio la cabeza de aria*")!!
        assertTrue(prompt.contains("reacciona directamente como ARIA"))
        assertTrue(prompt.contains("no escribas 'Kura:'"))
        assertTrue(prompt.contains("no termines con una pregunta"))
        assertFalse(prompt.contains("Kura realiza:"))
        assertFalse(prompt.contains("acaricio la cabeza de aria"))
    }

    @Test fun pureActionsStayOutOfTopicPendingQuestionAndEarlierContext() {
        val action = "*acaricio la cabeza de aria*"
        assertTrue(RoleplayInterpreter.isPureAction(action))
        assertFalse(RoleplayInterpreter.isPureAction("¿Cómo estás? *sonrío*"))
        val initial = ConversationState().afterExchange("Estoy dibujando el manga de Sora",
            "¿Qué escena dibujas?", 1_000L)
        val after = initial.afterExchange(action, "*sonrío* Qué detalle.", 2_000L)
        assertEquals(initial.topic, after.topic)
        assertTrue(after.pendingQuestion.isEmpty())
        assertFalse(after.earlierTopics.contains(action))
        val history = listOf(com.kura.aria.chat.ChatMessage("Kura", action, 1_000L))
        val prompt = ConversationContext.turnPrompt(history, emptyList(), action, after)
        assertEquals(1, Regex(Regex.escape(action)).findAll(prompt).count())
    }

    @Test fun greetingWithPossessiveActionKeepsKuraAsActorAndAriaAsTarget() {
        val message = "hola aria *acaricio su cabeza *"
        assertEquals("hola aria", RoleplayInterpreter.spokenText(message))
        val prompt = RoleplayInterpreter.promptContext(message)!!
        assertTrue(prompt.contains("Kura acaba de realizar una acción dirigida a ARIA"))
        assertFalse(prompt.contains("*acaricio su cabeza*"))
        val state = ConversationState().afterExchange(message,
            "*sonrío* Qué lindo gesto. ¿Qué tal el día?", 2_000L)
        assertTrue(state.topic.isEmpty())
        assertTrue(state.pendingQuestion.isEmpty())
    }
}
