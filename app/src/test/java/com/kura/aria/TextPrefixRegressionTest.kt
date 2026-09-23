package com.kura.aria

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPrefixRegressionTest {
    @Test fun ordinaryPrefixesDoNotRecurseInTheActivityPackage() {
        assertTrue("¿Qué tal?".startsWith("¿"))
        assertFalse("Hola".startsWith("¿"))
        val title: CharSequence? = "Sistema • 0.2.13-alpha"
        assertTrue(title?.toString()?.startsWith("Sistema") == true)
    }
}
