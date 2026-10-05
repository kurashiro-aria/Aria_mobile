package com.kura.aria.voice

data class QwenVoiceLabMetrics(
    val downloadMs: Long? = null,
    val verifyMs: Long? = null,
    val modelLoadMs: Long? = null,
    val voiceProfileLoadMs: Long? = null,
    val profileBuildColdMs: Long? = null,
    val profileCacheWriteMs: Long? = null,
    val profileCacheLoadMs: Long? = null,
    val profileCacheHit: Boolean? = null,
    val profileCacheBytes: Long? = null,
    val firstAudioMs: Long? = null,
    val generationMs: Long? = null,
    val totalMs: Long? = null,
    val peakRamMib: Int? = null,
    val downloadedBytes: Long? = null,
    val storageBytes: Long? = null,
    val audioDurationMs: Long? = null,
    val inferenceNumber: Int? = null,
) {
    val realTimeFactor: Double?
        get() = if (generationMs != null && audioDurationMs != null && audioDurationMs > 0L)
            generationMs.toDouble() / audioDurationMs else null

    val firstAudioClassification: String
        get() = when (val value = firstAudioMs) {
            null -> "SIN MEDIR"
            in 0L..3_000L -> "EXCELENTE"
            in 3_001L..5_000L -> "USABLE"
            in 5_001L..10_000L -> "REQUIERE OPTIMIZACIÓN"
            else -> "NO APTO COMO VOZ PRINCIPAL"
        }
}
