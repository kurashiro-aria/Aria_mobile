package com.kura.aria.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

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
}
