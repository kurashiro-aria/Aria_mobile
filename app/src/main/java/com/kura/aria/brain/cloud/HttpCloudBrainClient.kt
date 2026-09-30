package com.kura.aria.brain.cloud

import com.kura.aria.brain.BrainRequest
import com.kura.aria.brain.BrainResponse
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
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/** Small dependency-free HTTP transport. Cancellation disconnects the active HttpURLConnection. */
class HttpCloudBrainClient(private val config: CloudBrainConfig) : CloudBrainClient {
    init {
        val protocol = URL(config.endpoint).protocol
        require(protocol == "https" || (BuildConfigBridge.debug && protocol == "http")) {
            "Cloud backend must use HTTPS outside debug builds"
        }
    }

    override suspend fun health(): Boolean = request("GET", "/v1/health", null).first == 200

    override suspend fun respond(request: BrainRequest): BrainResponse {
        val id = requireNotNull(request.requestId) { "Cloud requests require requestId" }
        val body = JSONObject().apply {
            put("requestId", id)
            put("message", request.prompt)
            put("generation", JSONObject().put("maxOutputTokens", request.maxOutputTokens))
        }.toString()
        val (code, text) = request("POST", "/v1/brain/respond", body)
        when (code) {
            400 -> throw CloudBrainException.BadRequest()
            401, 403 -> throw CloudBrainException.Unauthorized()
            404 -> throw CloudBrainException.NotFound()
            429 -> throw CloudBrainException.RateLimited()
            in 500..599 -> throw CloudBrainException.ServerUnavailable()
            !in 200..299 -> throw CloudBrainException.ServerUnavailable()
        }
        val json = try { JSONObject(text) } catch (t: Throwable) {
            throw CloudBrainException.InvalidResponse("Respuesta inválida del servidor")
        }
        return BrainResponse(
            text = json.optString("reply", ""),
            requestId = json.optString("requestId", null),
            finishReason = json.optString("finishReason", null)
        )
    }

    private suspend fun request(method: String, path: String, body: String?): Pair<Int, String> =
        withTimeout(config.requestTimeoutMs) {
            withContext(Dispatchers.IO) {
                var connection: HttpURLConnection? = null
                try {
                    coroutineContext.ensureActive()
                    val base = config.endpoint.trimEnd('/')
                    connection = URL(base + path).openConnection() as HttpURLConnection
                    connection.requestMethod = method
                    connection.connectTimeout = config.connectTimeoutMs.toInt()
                    connection.readTimeout = config.readTimeoutMs.toInt()
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("X-ARIA-Protocol", "1")
                    if (body != null) {
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
                    }
                    coroutineContext.ensureActive()
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    code to text
                } catch (cancelled: CancellationException) {
                    connection?.disconnect(); throw cancelled
                } catch (t: SocketTimeoutException) {
                    throw CloudBrainException.Timeout(t)
                } catch (t: UnknownHostException) {
                    throw CloudBrainException.NoConnection(t)
                } catch (t: IOException) {
                    throw CloudBrainException.NoConnection(t)
                } finally {
                    connection?.disconnect()
                }
            }
        }

    override suspend fun close() = Unit
}

/** Set by the Android layer; kept out of ConversationBrain and transport protocol. */
object BuildConfigBridge { @Volatile var debug: Boolean = false }
