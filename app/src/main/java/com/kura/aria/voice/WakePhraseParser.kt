package com.kura.aria.voice

import java.text.Normalizer

/** Parses only the local recognition result; it never sends audio or text to a provider. */
object WakePhraseParser {
    fun parse(heard: String): WakeDetection? {
        val normalized = Normalizer.normalize(heard.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").lowercase()
        val match = Regex("^(?:oye\\s+)?aria(?:\\b|[,.:;!?])").find(normalized) ?: return null
        val remainder = heard.trim().substring(match.range.last + 1)
            .trimStart(' ', ',', '.', ':', ';', '!', '?')
        return WakeDetection(remainder.ifBlank { null })
    }
}
