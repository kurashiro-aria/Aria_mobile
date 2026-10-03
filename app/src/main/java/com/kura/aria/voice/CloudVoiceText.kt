package com.kura.aria.voice

/**
 * Removes written stage directions only from the transcript sent to TTS.
 * The original chat message remains untouched in the conversation UI/state.
 */
internal fun spokenTextForCloud(text: String): String =
    text.replace(Regex("\\*[^*]*\\*"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

/**
 * Splits already-sanitized speech into natural phrases. The Worker currently
 * returns complete WAV files, so this lowers time-to-first-audio without
 * pretending that the provider supports byte streaming.
 */
internal fun speechChunks(text: String, maxChars: Int = 220): List<String> {
    require(maxChars >= 40)
    val normalized = spokenTextForCloud(text)
    if (normalized.isBlank()) return emptyList()
    val sentences = normalized.split(Regex("(?<=[.!?…])\\s+"))
        .map(String::trim)
        .filter(String::isNotBlank)
    return sentences.flatMap { sentence ->
        if (sentence.length <= maxChars) listOf(sentence)
        else sentence.split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .fold(mutableListOf()) { chunks, word ->
                val current = chunks.lastOrNull()
                if (current == null || current.length + word.length + 1 <= maxChars) {
                    if (current == null) chunks += word else chunks[chunks.lastIndex] = "$current $word"
                } else chunks += word
                chunks
            }
    }
}
