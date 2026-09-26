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
    }
}
