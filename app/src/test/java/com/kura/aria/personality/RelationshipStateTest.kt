package com.kura.aria.personality

import com.kura.aria.chat.ChatMessage
import com.kura.aria.emotion.ConversationMood
import com.kura.aria.memory.AriaMemory
import com.kura.aria.memory.Memory
import com.kura.aria.memory.MemoryFacts
import com.kura.aria.memory.MemorySelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationshipStateTest {
    @Test fun onlyExplicitlyStoredConversationPreferencesBiasStyle() {
        val saved = listOf(
            Memory(1, "Prefiero coqueteo ligero", category = "preferencia_conversacion"),
            Memory(2, "No me gusta tu sarcasmo", category = "preferencia_conversacion"),
            Memory(3, "Me gusta cuando ARIA bromea conmigo", category = "preferencia_conversacion")
        )
        val preferences = RelationshipState.from(saved)
        assertTrue(ExpressionStyle.FLIRTY in preferences.preferred)
        assertTrue(ExpressionStyle.FLIRTY in preferences.gentle)
        assertTrue(ExpressionStyle.SARCASTIC_LIGHT in preferences.avoided)
        assertTrue(ExpressionStyle.PLAYFUL in preferences.preferred)
        assertEquals(ExpressionStyle.NATURAL, ExpressionResolver.forTurn("hola",
            ConversationMood.RELAXED, preferences = preferences).style)
        assertEquals(0.35f, ExpressionResolver.forTurn("Qué guapa eres",
            ConversationMood.NEUTRAL, preferences = preferences).intensity, 0.001f)
        assertEquals(ExpressionStyle.SARCASTIC_LIGHT, ExpressionResolver.forTurn("Usa ironía conmigo",
            ConversationMood.NEUTRAL, preferences = preferences).style)
        assertTrue(RelationshipState.from(emptyList()).preferred.isEmpty())
    }

    @Test fun correctionAndForgetRemoveTheOldPreferenceWithoutAForcedMode() {
        val old = Memory(7, "Me gusta tu sarcasmo", category = "preferencia_conversacion", updatedAt = 1)
        val corrected = old.copy(content = "No me gusta tu sarcasmo", updatedAt = 2)
        assertEquals(MemoryFacts.key(old.content), MemoryFacts.key(corrected.content))
        val changed = RelationshipState.from(listOf(old, corrected))
        assertTrue(ExpressionStyle.SARCASTIC_LIGHT in changed.avoided)
        assertFalse(ExpressionStyle.SARCASTIC_LIGHT in changed.preferred)
        val forgotten = RelationshipState.from(emptyList())
        assertTrue(forgotten.avoided.isEmpty())
        val noTeasing = RelationshipState.from(listOf(Memory(8, "No me gusta que me molestes",
            category = "preferencia_conversacion")))
        val previous = ExpressionState(ExpressionStyle.PLAYFUL, 0.3f, 1)
        assertEquals(ExpressionStyle.PLAYFUL, ExpressionResolver.forTurn("Jajaja",
            ConversationMood.PLAYFUL, previous, 1_000L, 2_000L, noTeasing).style)
    }

    @Test fun sharedExperiencesHaveProvenanceAndDoNotExpireLikeTodayFacts() {
        val text = "Ayer terminamos juntos la escena del manga de Sora"
        assertEquals("experiencia_compartida", MemoryFacts.category(text))
        assertEquals("proyecto", MemoryFacts.category("Estoy escribiendo el manga de Sora"))
        val day = 24L * 60 * 60 * 1000
        val experience = Memory(4, text, category = MemoryFacts.category(text),
            timestamp = day, updatedAt = day)
        val relevant = MemorySelector.select(listOf(experience), "¿Recuerdas la escena de Sora?",
            emptyList(), 9 * day)
        assertEquals(listOf(4L), relevant.map { it.id })
        val prompt = ConversationContext.turnPrompt(listOf(ChatMessage("Kura", "Otro tema", 1)),
            relevant, "¿Recuerdas la escena de Sora?")
        assertTrue(prompt.contains("Kura pidió guardar esta experiencia"))
        assertTrue(prompt.contains(text))
        assertTrue(prompt.endsWith("MENSAJE ACTUAL DE KURA:\n¿Recuerdas la escena de Sora?"))
        assertNull(AriaMemory.command("Ayer terminamos juntos la escena del manga de Sora"))
    }
}
