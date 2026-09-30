package com.kura.aria.brain.cloud

import com.kura.aria.brain.AriaBrainEngine
import com.kura.aria.brain.BrainRequest
import com.kura.aria.brain.BrainResponse
import com.kura.aria.brain.BrainState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** No provider key belongs here. The endpoint is an ARIA backend, never a provider endpoint. */
data class CloudBrainConfig(
    val endpoint: String,
    val connectTimeoutMs: Long = 15_000,
    val readTimeoutMs: Long = 90_000,
    val writeTimeoutMs: Long = 30_000,
    val requestTimeoutMs: Long = 120_000
) {
    init {
        require(endpoint.isNotBlank())
        require(connectTimeoutMs > 0 && readTimeoutMs > 0 && writeTimeoutMs > 0 && requestTimeoutMs > 0)
    }
}

interface CloudBrainClient {
    suspend fun health(): Boolean
    suspend fun respond(request: BrainRequest): BrainResponse
    suspend fun close() = Unit
}

sealed class CloudBrainException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoConnection(cause: Throwable? = null) : CloudBrainException("Sin conexión", cause)
    class Timeout(cause: Throwable? = null) : CloudBrainException("ARIA está tardando demasiado en responder", cause)
    class BadRequest : CloudBrainException("Solicitud rechazada por el servidor")
    class Unauthorized : CloudBrainException("Servidor de ARIA rechazó la conexión")
    class NotFound : CloudBrainException("Servidor de ARIA no disponible")
    class RateLimited : CloudBrainException("Servidor de ARIA está ocupado; prueba nuevamente")
    class ServerUnavailable : CloudBrainException("Servidor de ARIA no disponible")
    class InvalidResponse(reason: String) : CloudBrainException(reason)
}

class CloudInferenceEngine(
    val config: CloudBrainConfig,
    private val client: CloudBrainClient
) : AriaBrainEngine {
    @Volatile private var currentState: BrainState = BrainState.Disconnected
    override val state: BrainState get() = currentState

    suspend fun connect(): Boolean {
        currentState = BrainState.Connecting
        return try {
            if (client.health()) { currentState = BrainState.Ready; true }
            else { currentState = BrainState.Error("Servidor de ARIA no disponible"); false }
        } catch (cancelled: CancellationException) {
            currentState = BrainState.Disconnected; throw cancelled
        } catch (t: Throwable) {
            currentState = BrainState.Error(userMessage(t)); false
        }
    }

    override fun generate(request: BrainRequest): Flow<String> = flow {
        check(currentState == BrainState.Ready) { "Cloud brain is not ready" }
        currentState = BrainState.Generating
        try {
            val response = client.respond(request)
            if (response.requestId != request.requestId)
                throw CloudBrainException.InvalidResponse("La respuesta no corresponde a esta solicitud")
            if (response.text.isBlank()) throw CloudBrainException.InvalidResponse("ARIA recibió una respuesta vacía")
            emit(response.text)
            currentState = BrainState.Ready
        } catch (cancelled: CancellationException) {
            currentState = BrainState.Ready; throw cancelled
        } catch (t: Throwable) {
            currentState = BrainState.Error(userMessage(t)); throw t
        }
    }

    suspend fun recover(): Boolean = connect()

    override suspend fun close() {
        client.close(); currentState = BrainState.Disconnected
    }

    companion object {
        fun userMessage(t: Throwable): String = when (t) {
            is CloudBrainException -> t.message ?: "Servidor de ARIA no disponible"
            else -> "Servidor de ARIA no disponible"
        }
    }
}
