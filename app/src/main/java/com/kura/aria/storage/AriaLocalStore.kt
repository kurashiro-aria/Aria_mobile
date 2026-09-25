package com.kura.aria.storage

import android.content.Context
import java.io.File

/**
 * Private on-device workspace for ARIA. Files live under internal app storage,
 * so no broad storage permission is required and Android removes them with app data.
 * Keep generated/runtime data out of the APK and separate by responsibility.
 */
internal class AriaLocalStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "aria")

    val conversation = directory("conversation")
    val memory = directory("memory")
    val relationship = directory("relationship")
    val initiative = directory("initiative")
    val roleplay = directory("roleplay")
    val models = directory("models")
    val diagnostics = directory("diagnostics")

    private fun directory(name: String): File = File(root, name).also { dir ->
        check(dir.exists() || dir.mkdirs()) { "No pude crear el almacenamiento local de ARIA: $name" }
    }

    fun file(area: Area, name: String): File {
        require(name.isNotBlank() && '/' !in name && '\\' !in name) { "Nombre de archivo local inválido." }
        return File(directory(area.folder), name)
    }

    enum class Area(val folder: String) {
        CONVERSATION("conversation"),
        MEMORY("memory"),
        RELATIONSHIP("relationship"),
        INITIATIVE("initiative"),
        ROLEPLAY("roleplay"),
        MODELS("models"),
        DIAGNOSTICS("diagnostics")
    }
}
