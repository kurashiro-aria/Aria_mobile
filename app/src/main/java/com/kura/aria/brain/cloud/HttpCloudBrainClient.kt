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
import kotlin.coroutines.coroutineContext

class HttpCloudBrainClient(private val config: CloudBrainConfig, private val allowCleartextDebug: Boolean = false) : CloudBrainClient {
    init {
        val protocol = URL(config.endpoint).protocol
        require(protocol == "https" || (allowCleartextDebug && protocol == "http")) { "Cloud backend must use HTTPS outside debug builds" }
    }

    override suspend fun health(): Boolean = request("GET", "/v1/health", null).first == 200

    override suspend fun respond(request: BrainRequest): CloudBrainResult {
        val id = requireNotNull(request.requestId) { "Cloud requests require requestId" }
        val body = JSONObject().apply {
            put("requestId", id); put("message", request.prompt)
            put("generation", JSONObject().put("maxOutputTokens", request.maxOutputTokens))
        }.toString()
        val (code, text) = request("POST", "/v1/brain/respond", body)
        when (code) {
            400 -> throw CloudBrainException.BadRequest(); 401, 403 -> throw CloudBrainException.Unauthorized()
            404 -> throw CloudBrainException.NotFound(); 429 -> throw CloudBrainException.RateLimited()
            in 500..599 -> throw CloudBrainException.ServerUnavailable(); !in 200..299 -> throw CloudBrainException.ServerUnavailable()
        }
        val json = try { JSONObject(text) } catch (t: Throwable) { throw CloudBrainException.InvalidResponse("Respuesta inválida del servidor") }
        val response = BrainResponse(json.optString("reply", ""), json.optString("requestId", null), json.optString("finishReason", null))
        return CloudBrainResult(response, if (json.has("serverProcessingMs")) json.optLong("serverProcessingMs") else null, json.optString("model", null))
    }

    private suspend fun request(method: String, path: String, body: String?): Pair<Int, String> = try {
        withTimeout(config.requestTimeoutMs) { withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                coroutineContext.ensureActive(); connection = URL(config.endpoint.trimEnd('/') + path).openConnection() as HttpURLConnection
                connection.requestMethod = method; connection.connectTimeout = config.connectTimeoutMs.toInt(); connection.readTimeout = config.readTimeoutMs.toInt()
                connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("X-ARIA-Protocol", "1")
                if (body != null) { connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json; charset=utf-8"); connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) } }
                coroutineContext.ensureActive(); val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                code to stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            } catch (cancelled: CancellationException) { connection?.disconnect(); throw cancelled }
            catch (t: SocketTimeoutException) { throw CloudBrainException.Timeout(t) }
            catch (t: UnknownHostException) { throw CloudBrainException.NoConnection(t) }
            catch (t: IOException) { throw CloudBrainException.NoConnection(t) }
            finally { connection?.disconnect() }
        }}
    } catch (t: kotlinx.coroutines.TimeoutCancellationException) { throw CloudBrainException.Timeout(t) }

    override suspend fun close() = Unit
}
