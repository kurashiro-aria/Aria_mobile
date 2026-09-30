package com.kura.aria.memory

import org.junit.Assert.*
import org.junit.Test

class LocalMemoryProcessorTest {
    @Test fun extractsOnlyHighConfidenceFacts() {
        assertEquals("hecho", LocalMemoryProcessor.candidate("Mi teléfono es un Xiaomi 11T Pro")?.type)
        assertEquals("preferencia", LocalMemoryProcessor.candidate("Prefiero teléfonos Xiaomi")?.type)
        assertEquals("proyecto", LocalMemoryProcessor.candidate("Estoy trabajando en el proyecto ARIA Mobile")?.type)
        assertNull(LocalMemoryProcessor.candidate("sí"))
        assertNull(LocalMemoryProcessor.candidate("hace calor"))
        assertNull(LocalMemoryProcessor.candidate("*acaricio la cabeza de aria*"))
    }
}
