package com.kura.aria.voice

/**
 * Removes written stage directions only from the transcript sent to TTS.
 * The original chat message remains untouched in the conversation UI/state.
 */
internal fun spokenTextForCloud(text: String): String =
    text.replace(Regex("\\*[^*]*\\*"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
