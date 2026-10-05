package com.kura.aria.voice

/** Data boundary that lets ARIA Voice V1 be replaced without changing the engine. */
data class ReplaceableVoiceProfile(
    val id: String,
    val version: Int,
    val displayName: String,
    val referenceAsset: String,
    val referenceSha256: String,
    val referenceTranscript: String,
    val promptFileName: String,
)

object AriaB5CVoiceProfile {
    val V1 = ReplaceableVoiceProfile(
        id = "aria-b5c",
        version = 1,
        displayName = "ARIA-B5C Voice V1",
        referenceAsset = "aria_voice_B5C_MASTER.wav",
        referenceSha256 = "b2bc79dc5a7504fe531d5fbd729a4b946423585efb1afff2ef9ab5a058f0a6b5",
        referenceTranscript = "Hola Kura. ¿Cómo estás? Me alegra mucho verte otra vez.\n" +
            "Estaba esperando que volvieras para seguir trabajando juntos.",
        promptFileName = "aria-b5c-v1-icl-prompt.json",
    )
}

object QwenVoiceLabIsolationPolicy {
    const val PROCESS_NAME = ":qwen_voice"
    const val AUTOMATIC_VOICE_ENABLED = false
    const val FALLBACK_ENGINE = "Piper"
}

/** Pure state guard used by tests and UI policy; native work stays in the service process. */
class QwenVoiceLabStateMachine(initial: QwenLabState) {
    var state: QwenLabState = initial
        private set
    var runtimeLoaded: Boolean = false
        private set

    fun beginLoad() {
        check(state == QwenLabState.INSTALLED || state == QwenLabState.ERROR)
        state = QwenLabState.LOADING
    }

    fun finishLoad(success: Boolean) {
        runtimeLoaded = success
        state = if (success) QwenLabState.READY else QwenLabState.ERROR
    }

    fun synthesisFailed() {
        state = QwenLabState.ERROR
    }

    fun release(modelInstalled: Boolean) {
        runtimeLoaded = false
        state = if (modelInstalled) QwenLabState.INSTALLED else QwenLabState.NOT_INSTALLED
    }
}
