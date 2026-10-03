package com.kura.aria.voice

import com.kura.aria.brain.cloud.CloudBrainConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext

internal data class CloudVoiceRequest(
    val requestId: String,
    val text: String,
    val direction: CloudVoiceDirection,
    val voiceId: String
) {
    init {
        require(requestId.isNotBlank() && requestId.length <= 100)
        require(text.isNotBlank() && text.length <= 1200)
        require(voiceId in AriaVoiceDirector.cloudCandidates.map { it.id })
    }
}

internal data class CloudVoiceResult(
    val audio: ByteArray,
    val mimeType: String,
    val totalMs: Long,
    val gatewayMs: Long?,
    val providerMs: Long?,
    val model: String?,
    val voiceId: String
)

internal interface CloudVoiceClient {
    suspend fun synthesize(request: CloudVoiceRequest): CloudVoiceResult
}

internal sealed class CloudVoiceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoConnection(cause: Throwable? = null) : CloudVoiceException("Sin conexión para generar la voz", cause)
    class Timeout(cause: Throwable? = null) : CloudVoiceException("La voz Cloud tardó demasiado", cause)
    class RateLimited(val gateway: Boolean = false, val retryAfterMs: Long? = null) : CloudVoiceException(
        if (gateway) "El Gateway de voz está temporalmente limitado" else "El proveedor de voz está temporalmente limitado")
    class Rejected : CloudVoiceException("El servidor rechazó la prueba de voz")
    class Unavailable : CloudVoiceException("La voz Cloud no está disponible")
    class InvalidAudio : CloudVoiceException("El servidor no devolvió audio válido")
}

internal class HttpCloudVoiceClient(
    private val config: CloudBrainConfig,
    private val allowCleartextDebug: Boolean = false
) : CloudVoiceClient {
    init {
        val protocol = URL(config.endpoint).protocol
        require(protocol == "https" || (allowCleartextDebug && protocol == "http"))
    }

    override suspend fun synthesize(request: CloudVoiceRequest): CloudVoiceResult {
        val body = JSONObject().apply {
            put("requestId", request.requestId)
            put("text", request.text)
            put("emotion", request.direction.emotion)
            put("intensity", request.direction.intensity.toDouble())
            put("expressionStyle", request.direction.expressionStyle)
            put("voiceId", request.voiceId)
        }.toString()
        val started = System.nanoTime()
        return try {
            withTimeout(60_000) { withContext(Dispatchers.IO) {
                var connection: HttpURLConnection? = null
                try {
                    coroutineContext.ensureActive()
                    connection = URL(config.endpoint.trimEnd('/') + "/v1/voice/synthesize")
                        .openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.connectTimeout = config.connectTimeoutMs.toInt()
                    connection.readTimeout = 50_000
                    connection.doOutput = true
                    connection.setRequestProperty("Accept", "audio/wav")
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setRequestProperty("X-ARIA-Protocol", "1")
                    if (config.clientToken.isNotBlank())
                        connection.setRequestProperty("X-ARIA-Client", config.clientToken)
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
                    coroutineContext.ensureActive()
                    val code = connection.responseCode
                    val retryAfterMs = connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceAtLeast(0)?.times(1000)
                    if (code == 400 || code == 401 || code == 403) throw CloudVoiceException.Rejected()
                    if (code == 408 || code == 504) throw CloudVoiceException.Timeout()
                    if (code == 429) {
                        val errorCode = runCatching {
                            connection.errorStream?.bufferedReader()?.use { JSONObject(it.readText().take(2048)).optString("error") }
                        }.getOrNull()
                        throw CloudVoiceException.RateLimited(errorCode == "gateway_rate_limited", retryAfterMs)
                    }
                    if (code !in 200..299) throw CloudVoiceException.Unavailable()
                    val mime = connection.contentType?.substringBefore(';') ?: ""
                    val audio = connection.inputStream.use { it.readBytes() }
                    if (mime != "audio/wav" || audio.size < 44 ||
                        !audio.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()))
                        throw CloudVoiceException.InvalidAudio()
                    CloudVoiceResult(audio, mime, (System.nanoTime() - started) / 1_000_000,
                        connection.getHeaderField("X-ARIA-Gateway-Ms")?.toLongOrNull(),
                        connection.getHeaderField("X-ARIA-Provider-Ms")?.toLongOrNull(),
                        connection.getHeaderField("X-ARIA-Model"),
                        connection.getHeaderField("X-ARIA-Voice-Id") ?: request.voiceId)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (known: CloudVoiceException) { throw known }
                catch (timeout: SocketTimeoutException) { throw CloudVoiceException.Timeout(timeout) }
                catch (host: UnknownHostException) { throw CloudVoiceException.NoConnection(host) }
                catch (io: IOException) { throw CloudVoiceException.NoConnection(io) }
                finally { connection?.disconnect() }
            }}
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw CloudVoiceException.Timeout(timeout)
        }
    }
}
