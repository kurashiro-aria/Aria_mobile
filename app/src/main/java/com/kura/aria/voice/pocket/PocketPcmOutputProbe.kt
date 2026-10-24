package com.kura.aria.voice.pocket

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/** One-second output check using generated PCM, with no model, JNI, or Pocket callback. */
internal object PocketPcmOutputProbe {
    data class Result(val writtenBytes: Int, val playbackHead: Long, val route: String?, val error: String?) {
        fun summary(): String = "PCM independiente: bytes=$writtenBytes head=$playbackHead " +
            "route=${route ?: "—"} error=${error ?: "—"}. ¿Se escuchó el tono?"
    }

    fun tone(): ByteArray {
        val output = ByteBuffer.allocate(PocketAudioFormat.SAMPLE_RATE * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(PocketAudioFormat.SAMPLE_RATE) { sample ->
            output.putShort((sin(2.0 * Math.PI * 440.0 * sample / PocketAudioFormat.SAMPLE_RATE) * 6000).toInt().toShort())
        }
        return output.array()
    }

    fun run(): Result {
        var track: AudioTrack? = null
        var written = 0
        var head = 0L
        var route: String? = null
        return try {
            val min = AudioTrack.getMinBufferSize(PocketAudioFormat.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0) { "getMinBufferSize=$min" }
            val output = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(PocketAudioFormat.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min * 2, PocketAudioFormat.SAMPLE_RATE * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                .build()
            track = output
            check(output.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack no inicializado" }
            val pcm = tone()
            // One second fits within the two-second track capacity, even while stopped.
            while (written < pcm.size) {
                val next = output.write(pcm, written, pcm.size - written, AudioTrack.WRITE_BLOCKING)
                check(next > 0) { "write=$next after $written bytes play=${output.playState}" }
                written += next
            }
            output.play()
            val deadline = SystemClock.elapsedRealtime() + 3_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                head = Integer.toUnsignedLong(output.playbackHeadPosition)
                route = output.routedDevice?.let { "type=${it.type} address=${it.address}" } ?: route
                if (head >= PocketAudioFormat.SAMPLE_RATE) break
                SystemClock.sleep(20L)
            }
            Result(written, head, route, if (head == 0L) "SIN_AVANCE" else null)
        } catch (error: Throwable) {
            Result(written, head, route, "${error.javaClass.simpleName}: ${error.message ?: "sin detalle"}")
        } finally {
            track?.let { output ->
                runCatching { output.stop() }
                runCatching { output.flush() }
                runCatching { output.release() }
            }
        }
    }
}
