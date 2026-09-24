package com.kura.aria.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySelectorTest {
    private val memories = listOf(
        Memory(1, "Mi gato se llama Nube", timestamp = 1),
        Memory(2, "Me gusta pintar con resina", timestamp = 2),
        Memory(3, "Mi perro se llama Roco", timestamp = 3)
    )

    @Test fun followUpCanUseThePriorSubject() {
        assertEquals(listOf(1L), MemorySelector.select(memories, "¿Cómo se llama?", listOf("Hablemos de mi gato")).map { it.id })
    }

    @Test fun currentSubjectBeatsOlderConversation() {
        assertEquals(listOf(2L), MemorySelector.select(memories, "¿Te acuerdas de la resina?", listOf("Mi gato")).map { it.id })
    }

    @Test fun noMatchingSubjectDoesNotInjectPersonalFacts() {
        assertTrue(MemorySelector.select(memories, "¿Qué hora es?", emptyList()).isEmpty())
    }

    @Test fun greetingsDoNotRetrieveThePreviousTopic() {
        val coffee = listOf(Memory(4, "No me gusta el café"))
        assertTrue(MemorySelector.select(coffee, "hola aria", listOf("Hablemos del café")).isEmpty())
        assertTrue(MemorySelector.select(coffee, "¿cómo estás?", listOf("Hablemos del café")).isEmpty())
    }

    @Test fun genericPhrasesDoNotRetrieveUnrelatedMemories() {
        assertTrue(MemorySelector.select(memories, "Me gusta hablar contigo", listOf("Mi gato")).isEmpty())
    }

    @Test fun obsoleteMemoriesAreNotRecoveredAndRelevantOnesAreBounded() {
        val items = (1..9).map { Memory(it.toLong(), "Mi gato Nube tiene juguete $it", active = it != 1) }
        val selected = MemorySelector.select(items, "¿Cómo está mi gato Nube?", emptyList())
        assertEquals(5, selected.size)
        assertTrue(selected.none { it.id == 1L })
    }

    @Test fun correctedPreferenceNeverCompetesWithOlderOppositeFact() {
        val old = Memory(8, "Me gusta el café", updatedAt = 1)
        val corrected = Memory(9, "No me gusta el café", updatedAt = 2)
        val selected = MemorySelector.select(listOf(old, corrected), "¿Qué opinas del café?", emptyList())
        assertEquals(listOf(9L), selected.map { it.id })
        assertEquals("preferencia", MemoryFacts.category(corrected.content))
    }

    @Test fun differentPetsRemainSeparateAndLegacyUsageDefaultsAreSafe() {
        val cat = Memory(1, "Mi gato se llama Nube", updatedAt = 1)
        val dog = Memory(2, "Mi perro se llama Roco", updatedAt = 2)
        assertEquals(2, MemoryFacts.current(listOf(cat, dog)).size)
        assertEquals(0, cat.accessCount)
        assertEquals(0L, cat.lastUsed)
    }

    @Test fun aSavedTodayFactDoesNotReappearAsCurrentDaysLater() {
        val day = 24L * 60 * 60 * 1000
        val old = Memory(4, "Hoy trabajé en el manga de Sora", timestamp = day, updatedAt = day)
        assertTrue(MemorySelector.select(listOf(old), "¿Cómo va el manga de Sora?", emptyList(), 4 * day).isEmpty())
        assertEquals(1, MemoryFacts.current(listOf(old)).size)
    }
}
