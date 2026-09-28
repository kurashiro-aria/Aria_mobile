package com.kura.aria

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ModelSelectionTest {
    @Test fun savedChoiceWinsOverAnOlderInstalledBrain() {
        val dir = Files.createTempDirectory("aria-model-choice").toFile()
        try {
            val first = dir.resolve("first.gguf").apply { writeText("one") }
            val second = dir.resolve("second.gguf").apply { writeText("two") }
            assertEquals(second, ModelSelection.resolve(dir, second.name))
            assertEquals(first, ModelSelection.resolve(dir, first.name))
            assertEquals(listOf(first, second), ModelSelection.installed(dir))
            assertNull(ModelSelection.resolve(dir, null))
        } finally { dir.deleteRecursively() }
    }

    @Test fun missingChoiceCannotFallBackToAnotherModel() {
        val dir = Files.createTempDirectory("aria-model-missing").toFile()
        try {
            val old = dir.resolve("old.gguf").apply { writeText("one") }
            assertNull(ModelSelection.resolve(dir, "second.gguf"))
            assertNull(ModelSelection.resolve(dir, "../old.gguf"))
            assertEquals(old, ModelSelection.resolve(dir, null)) // legacy single-model installs
            dir.resolve("backup-copy.gguf").writeText("backup")
            assertEquals(listOf(old), ModelSelection.installed(dir))
        } finally { dir.deleteRecursively() }
    }
}
