package com.kura.aria.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

class CloudVoiceContractTest {
    @Test fun requestKeepsAriaTextVerbatim() {
        val text = "Hola Kura. ¿Qué te parece mi voz?"
        val request = CloudVoiceRequest("voice-1", text,
            AriaVoiceDirector.forPreview("playful"), "Leda")
        assertEquals(text, request.text)
        assertEquals("playful", request.direction.expressionStyle)
    }

    @Test fun providerFailureDoesNotRemoveTheTextReply() = runBlocking {
        val displayedReply = "La respuesta escrita de ARIA permanece."
        val fake = object : CloudVoiceClient {
            override suspend fun synthesize(request: CloudVoiceRequest): CloudVoiceResult {
                throw CloudVoiceException.Timeout()
            }
        }
        val error = runCatching { fake.synthesize(CloudVoiceRequest("voice-2", displayedReply,
            AriaVoiceDirector.forPreview("neutral"), "Achernar")) }.exceptionOrNull()
        assertTrue(error is CloudVoiceException.Timeout)
        assertEquals("La respuesta escrita de ARIA permanece.", displayedReply)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownVoiceCannotLeaveAndroid() {
        CloudVoiceRequest("voice-3", "Hola", AriaVoiceDirector.forPreview("neutral"), "Unknown")
    }

    @Test fun transportErrorsAreNotAllReportedAsOffline() {
        assertTrue(classifyVoiceIo(UnknownHostException()) is CloudVoiceException.DnsFailure)
        assertTrue(classifyVoiceIo(ConnectException()) is CloudVoiceException.ConnectFailure)
        assertTrue(classifyVoiceIo(SSLException("tls")) is CloudVoiceException.TlsFailure)
        assertTrue(classifyVoiceIo(SocketTimeoutException()) is CloudVoiceException.Timeout)
        assertTrue(classifyVoiceIo(IOException("audio reset")) is CloudVoiceException.TransportFailure)
    }

    @Test fun httpErrorKeepsStatusAndServerCategory() {
        val error = CloudVoiceException.HttpError(503, "provider_unavailable")
        assertEquals(503, error.httpStatus)
        assertEquals("provider_unavailable", error.serverError)
    }
}
