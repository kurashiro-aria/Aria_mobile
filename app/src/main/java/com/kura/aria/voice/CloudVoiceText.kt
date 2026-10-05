package com.kura.aria.voice

/**
 * Removes written stage directions only from the transcript sent to TTS.
 * The original chat message remains untouched in the conversation UI/state.
 */
internal fun spokenTextForCloud(text: String): String =
    text.replace(Regex("\\*[^*]*\\*"), " ")
        .replace(Regex("```[a-zA-Z0-9_-]*"), " ")
        .replace("```", " ")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)")) { match -> match.groupValues[1] }
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
        .replace(Regex("(?m)^\\s*(?:[-+•]|>|\\d+[.)])\\s+"), "")
        .replace(Regex("[*_~`]+"), "")
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
    val chunks = mutableListOf<String>()
    var current = ""
    fun appendSentence(sentence: String) {
        if (current.isBlank()) current = sentence
        else if (current.length + sentence.length + 1 <= maxChars) current += " $sentence"
        else { chunks += current; current = sentence }
    }
    sentences.forEach { sentence ->
        if (sentence.length <= maxChars) appendSentence(sentence)
        else {
            if (current.isNotBlank()) { chunks += current; current = "" }
            sentence.split(Regex("\\s+"))
                .filter(String::isNotBlank)
                .forEach { word ->
                    if (current.isBlank()) current = word
                    else if (current.length + word.length + 1 <= maxChars) current += " $word"
                    else { chunks += current; current = word }
                }
        }
    }
    if (current.isNotBlank()) chunks += current
    return chunks
}
