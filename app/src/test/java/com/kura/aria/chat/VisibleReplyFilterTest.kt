package com.kura.aria.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VisibleReplyFilterTest {
    @Test fun reasoningNeverFlashesAtAnyChunkBoundary() {
        val raw = "<think>PRIVATE reasoning</think>\n¡Hola, Kura!"
        for (split in 0..raw.length) {
            val filter = VisibleReplyFilter()
            assertFalse(filter.append(raw.take(split)).contains("PRIVATE"))
            assertEquals("¡Hola, Kura!", filter.append(raw.drop(split)))
            assertEquals("¡Hola, Kura!", filter.finish())
        }
    }

    @Test fun handlesSingleCharactersAndNestedBlocks() {
        val filter = VisibleReplyFilter()
        val raw = "<THINK>PRIVATE<think>PRIVATE</think>PRIVATE</THINK>Hola 🧠"
        for (char in raw) {
            val shown = filter.append(char.toString())
            assertFalse(shown.contains("PRIVATE"))
            assertFalse(shown.contains("<"))
        }
        assertEquals("Hola 🧠", filter.finish())
    }

    @Test fun unfinishedReasoningIsNotPresentedAsAnAnswer() {
        val filter = VisibleReplyFilter()
        assertEquals("", filter.append("<think>PRIVATE unfinished"))
        assertEquals("", filter.finish())
    }

    @Test fun keepsPlainTextAndHidesLaterBlocks() {
        val filter = VisibleReplyFilter()
        assertEquals("2 < 3. Hola", filter.append("2 < 3. <think>PRIVATE</think>Hola"))
        assertEquals("2 < 3. Hola", filter.finish())
    }

    @Test fun discardsPartialTagsAtTheEnd() {
        val filter = VisibleReplyFilter()
        assertEquals("Hola", filter.append("Hola<th"))
        assertEquals("Hola", filter.finish())
    }

    @Test fun newTurnStartsWithAnEmptyFilter() {
        VisibleReplyFilter().append("<think>unfinished")
        assertEquals("Nueva respuesta", VisibleReplyFilter().append("Nueva respuesta"))
    }
}
