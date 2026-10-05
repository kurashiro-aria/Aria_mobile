package com.kura.aria.voice.pocket

import android.content.Context

internal class PocketVoicePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("aria_pocket_voice", Context.MODE_PRIVATE)

    var engine: LocalVoiceEngine
        get() = runCatching {
            LocalVoiceEngine.valueOf(prefs.getString(KEY_ENGINE, LocalVoiceEngine.POCKET.name)!!)
        }.getOrDefault(LocalVoiceEngine.POCKET)
        set(value) { prefs.edit().putString(KEY_ENGINE, value.name).apply() }

    var selectedVoiceId: String?
        get() = prefs.getString(KEY_VOICE, null)
        set(value) { prefs.edit().putString(KEY_VOICE, value).apply() }

    companion object {
        private const val KEY_ENGINE = "engine"
        private const val KEY_VOICE = "voice_id"
    }
}

internal object PocketVoiceSelection {
    fun selected(voices: List<PocketVoice>, selectedId: String?): PocketVoice? =
        voices.firstOrNull { it.id == selectedId } ?: voices.firstOrNull()

    fun shouldUsePocket(engine: LocalVoiceEngine, installed: Boolean): Boolean =
        engine == LocalVoiceEngine.POCKET && installed
}

internal object PocketFallbackPolicy {
    /** Never start a second voice after Pocket audio has already begun. */
    fun useAndroidFallback(
        selected: LocalVoiceEngine,
        pocketInstalled: Boolean,
        pocketAudioStarted: Boolean,
        cancelled: Boolean
    ): Boolean = !cancelled && when (selected) {
        LocalVoiceEngine.ANDROID_FALLBACK -> true
        LocalVoiceEngine.POCKET -> !pocketInstalled || !pocketAudioStarted
    }
}
