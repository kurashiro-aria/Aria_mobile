package com.kura.aria.chat

import android.content.Context
import org.json.JSONObject
import java.io.File

data class ChatMessage(
    val role: String,
    val text: String,
    val timestamp: Long
)

/**
 * Append-only local conversation history.
 * It is deliberately separate from the LLM context: storing messages does not feed
 * the whole history back into Qwen or consume its context window.
 */
class ChatHistory(context: Context) {
    private val file = File(context.filesDir, "history/chat.jsonl")

    @Synchronized
    fun append(role: String, text: String, timestamp: Long = System.currentTimeMillis()) {
        require(role == "Kura" || role == "ARIA")
        require(text.isNotBlank())
        file.parentFile?.mkdirs()
        val json = JSONObject()
            .put("role", role)
            .put("text", text)
            .put("timestamp", timestamp)
        file.appendText(json.toString() + "\n", Charsets.UTF_8)
    }

    @Synchronized
    fun readAll(): List<ChatMessage> {
        if (!file.isFile) return emptyList()
        return file.useLines(Charsets.UTF_8) { lines ->
            lines.mapNotNull { line ->
                try {
                    val json = JSONObject(line)
                    val role = json.optString("role")
                    val text = json.optString("text")
                    val timestamp = json.optLong("timestamp", 0L)
                    if ((role == "Kura" || role == "ARIA") && text.isNotBlank()) {
                        ChatMessage(role, text, timestamp)
                    } else null
                } catch (_: Exception) {
                    // A damaged final line must not erase the rest of the conversation.
                    null
                }
            }.toList()
        }
    }
}
