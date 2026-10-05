package com.kura.aria.voice

object QwenVoiceLabContract {
    const val ACTION_STATUS = "com.kura.aria.qwen.STATUS"
    const val ACTION_QUERY = "com.kura.aria.qwen.QUERY"
    const val ACTION_DOWNLOAD = "com.kura.aria.qwen.DOWNLOAD"
    const val ACTION_CANCEL_DOWNLOAD = "com.kura.aria.qwen.CANCEL_DOWNLOAD"
    const val ACTION_DELETE_MODEL = "com.kura.aria.qwen.DELETE_MODEL"
    const val ACTION_LOAD_MODEL = "com.kura.aria.qwen.LOAD_MODEL"
    const val ACTION_TEST_SHORT = "com.kura.aria.qwen.TEST_SHORT"
    const val ACTION_TEST_LONG = "com.kura.aria.qwen.TEST_LONG"
    const val ACTION_STOP = "com.kura.aria.qwen.STOP"
    const val ACTION_RELEASE = "com.kura.aria.qwen.RELEASE"

    const val EXTRA_STATE = "state"
    const val EXTRA_DETAIL = "detail"
    const val EXTRA_PROGRESS = "progress"
    const val EXTRA_METRICS = "metrics"

    const val SHORT_TEXT = "Hola Kura, soy Aria. ¿Qué hacemos ahora?"
    const val LONG_TEXT = "Kura, ya estoy aquí. Ahora podemos continuar trabajando juntos."
}
