package com.kura.aria.voice

import java.io.File
import java.security.MessageDigest

/** Small preview cache only; it never stores the conversation history. */
internal class CloudVoiceCache(private val directory: File, private val maxFiles: Int = 8,
                               private val maxBytes: Long = 12L * 1024 * 1024) {
    init { require(maxFiles > 0 && maxBytes > 0) }

    fun find(key: String): File? = fileFor(key).takeIf { it.isFile && it.length() in 44..maxBytes }
        ?.also { it.setLastModified(System.currentTimeMillis()) }

    fun store(key: String, audio: ByteArray): File {
        require(audio.size >= 44 && audio.size <= maxBytes)
        directory.mkdirs()
        val target = fileFor(key)
        val temporary = File(directory, target.name + ".tmp")
        temporary.writeBytes(audio)
        check(temporary.renameTo(target) || run { temporary.copyTo(target, overwrite = true); temporary.delete(); true })
        trim(target)
        return target
    }

    private fun trim(keep: File) {
        val files = directory.listFiles { file -> file.extension == "wav" }.orEmpty()
            .sortedByDescending { it.lastModified() }
        var bytes = 0L
        files.forEachIndexed { index, file ->
            bytes += file.length()
            if (file != keep && (index >= maxFiles || bytes > maxBytes)) file.delete()
        }
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.wav")
    }
}
