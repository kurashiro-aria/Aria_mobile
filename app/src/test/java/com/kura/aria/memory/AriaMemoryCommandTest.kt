package com.kura.aria.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AriaMemoryCommandTest {
    @Test fun explicitRememberAndForgetCommands() {
        assertEquals(MemoryCommand.Save("mi gato se llama Nube"),
            AriaMemory.command("ARIA, recuerda que mi gato se llama Nube"))
        assertEquals(MemoryCommand.Save("hoy probamos tu cerebro"),
            AriaMemory.command("recuerda esto: hoy probamos tu cerebro"))
        assertEquals(MemoryCommand.Delete(7), AriaMemory.command("olvida el recuerdo #7"))
        assertEquals(MemoryCommand.Correct(7, "mi gato se llama Sol"),
            AriaMemory.command("ARIA, corrige el recuerdo #7: mi gato se llama Sol"))
        assertEquals(MemoryCommand.ListAll, AriaMemory.command("¿Qué recuerdas?"))
    }

    @Test fun ordinaryConversationDoesNotSilentlyChangeMemory() {
        assertNull(AriaMemory.command("recuerdo que hablamos ayer"))
        assertNull(AriaMemory.command("¿te acuerdas de Sol?"))
    }
}
