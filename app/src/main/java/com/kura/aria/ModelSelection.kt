package com.kura.aria

import java.io.File

/** The saved choice is authoritative. Never silently select a different installed GGUF. */
internal object ModelSelection {
    fun installed(directory: File): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.canRead() && it.name.endsWith(".gguf", ignoreCase = true) &&
            !it.name.startsWith("backup-") }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    fun resolve(directory: File, savedName: String?): File? {
        if (savedName == null) return installed(directory).singleOrNull()
        if (savedName.isBlank() || savedName == "." || savedName == ".." ||
            savedName.contains('/') || savedName.contains('\\')) return null
        return installed(directory).firstOrNull { it.name == savedName }
    }
}
