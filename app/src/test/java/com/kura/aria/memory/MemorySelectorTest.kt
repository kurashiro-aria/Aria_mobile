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
}
