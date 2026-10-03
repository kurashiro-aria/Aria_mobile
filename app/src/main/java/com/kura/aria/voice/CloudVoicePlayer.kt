package com.kura.aria.voice

import android.content.Context
import android.media.MediaPlayer
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

internal class CloudVoicePlayer(context: Context) : AutoCloseable {
    private val cache = CloudVoiceCache(File(context.cacheDir, "aria-cloud-voice-preview"))
    private var player: MediaPlayer? = null

    fun cached(key: String): File? = cache.find(key)
    fun cache(key: String, audio: ByteArray): File = cache.store(key, audio)

    fun play(file: File, onComplete: () -> Unit = {}) {
        stop()
        player = MediaPlayer().also { media ->
            media.setDataSource(file.absolutePath)
            media.setOnCompletionListener { it.release(); if (player === it) player = null; onComplete() }
            media.setOnErrorListener { instance, _, _ ->
                instance.release(); if (player === instance) player = null; onComplete(); true
            }
            media.prepare()
            media.start()
        }
    }

    suspend fun playAndAwait(file: File) = suspendCancellableCoroutine<Unit> { continuation ->
        play(file) {
            if (continuation.isActive) continuation.resume(Unit)
        }
        continuation.invokeOnCancellation { stop() }
    }

    fun stop() { player?.runCatching { stop() }; player?.release(); player = null }
    override fun close() = stop()
}
