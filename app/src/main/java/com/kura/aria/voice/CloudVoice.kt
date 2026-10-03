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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
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
    val voiceId: String,
    val downloadMs: Long? = null,
    val audioPrepareMs: Long? = null
)

internal interface CloudVoiceClient {
    suspend fun synthesize(request: CloudVoiceRequest): CloudVoiceResult
}

internal sealed class CloudVoiceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoConnection(cause: Throwable? = null) : CloudVoiceException("Sin conexión para generar la voz", cause)
    class DnsFailure(cause: Throwable? = null) : CloudVoiceException("No se pudo resolver el Gateway de voz", cause)
    class ConnectFailure(cause: Throwable? = null) : CloudVoiceException("No se pudo conectar al Gateway de voz", cause)
    class TlsFailure(cause: Throwable? = null) : CloudVoiceException("Falló la conexión segura con el Gateway de voz", cause)
    class TransportFailure(cause: Throwable? = null) : CloudVoiceException("Falló la transferencia de audio Cloud", cause)
    class Timeout(cause: Throwable? = null) : CloudVoiceException("La voz Cloud tardó demasiado", cause)
    class RateLimited(val gateway: Boolean = false, val retryAfterMs: Long? = null) : CloudVoiceException(
        if (gateway) "El Gateway de voz está temporalmente limitado" else "El proveedor de voz está temporalmente limitado")
    class HttpError(val httpStatus: Int, val serverError: String? = null) : CloudVoiceException("La voz Cloud devolvió HTTP $httpStatus")
    class Rejected : CloudVoiceException("El servidor rechazó la prueba de voz")
    class Unavailable(val httpStatus: Int? = null, val serverError: String? = null) : CloudVoiceException("La voz Cloud no está disponible")
    class InvalidAudio : CloudVoiceException("El servidor no devolvió audio válido")
}

internal fun classifyVoiceIo(error: IOException): CloudVoiceException = when (error) {
    is SocketTimeoutException -> CloudVoiceException.Timeout(error)
    is UnknownHostException -> CloudVoiceException.DnsFailure(error)
    is SSLException -> CloudVoiceException.TlsFailure(error)
    is ConnectException -> CloudVoiceException.ConnectFailure(error)
    else -> CloudVoiceException.TransportFailure(error)
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
            withTimeout(30_000) { withContext(Dispatchers.IO) {
                var connection: HttpURLConnection? = null
                try {
                    coroutineContext.ensureActive()
                    connection = URL(config.endpoint.trimEnd('/') + "/v1/voice/synthesize")
                        .openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.connectTimeout = config.connectTimeoutMs.toInt()
                    connection.readTimeout = 25_000
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
                    if (code !in 200..299) {
                        val errorCode = runCatching {
                            connection.errorStream?.bufferedReader()?.use { JSONObject(it.readText().take(2048)).optString("error") }
                        }.getOrNull()
                        throw CloudVoiceException.HttpError(code, errorCode)
                    }
                    val mime = connection.contentType?.substringBefore(';') ?: ""
                    val downloadStarted = System.nanoTime()
                    val audio = connection.inputStream.use { it.readBytes() }
                    val downloadMs = (System.nanoTime() - downloadStarted) / 1_000_000
                    val audioPrepareStarted = System.nanoTime()
                    if (mime != "audio/wav" || audio.size < 44 ||
                        !audio.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()))
                        throw CloudVoiceException.InvalidAudio()
                    val audioPrepareMs = (System.nanoTime() - audioPrepareStarted) / 1_000_000
                    CloudVoiceResult(audio, mime, (System.nanoTime() - started) / 1_000_000,
                        connection.getHeaderField("X-ARIA-Gateway-Ms")?.toLongOrNull(),
                        connection.getHeaderField("X-ARIA-Provider-Ms")?.toLongOrNull(),
                        connection.getHeaderField("X-ARIA-Model"),
                        connection.getHeaderField("X-ARIA-Voice-Id") ?: request.voiceId,
                        downloadMs, audioPrepareMs)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (known: CloudVoiceException) { throw known }
                catch (io: IOException) { throw classifyVoiceIo(io) }
                finally { connection?.disconnect() }
            }}
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw CloudVoiceException.Timeout(timeout)
        }
    }
}
