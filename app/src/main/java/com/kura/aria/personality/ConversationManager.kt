package com.kura.aria.personality

import android.content.Context
import com.kura.aria.memory.MemorySelector
import org.json.JSONArray
import org.json.JSONObject

/** Small, local conversation state. It contains Kura's words, never generated summaries. */
internal data class ConversationState(
    val topic: String = "",
    val pendingQuestion: String = "",
    val earlierTopics: List<String> = emptyList(),
    val updatedAt: Long = 0L
) {
    fun afterExchange(user: String, reply: String, now: Long = System.currentTimeMillis()): ConversationState {
        val cleanUser = user.replace(Regex("\\s+"), " ").trim().take(160)
        val substantive = MemorySelector.keywords(cleanUser).isNotEmpty() && !MemorySelector.isFollowUp(cleanUser)
        val nextTopic = if (substantive) cleanUser else topic
        val earlier = if (substantive && topic.isNotBlank() &&
            MemorySelector.keywords(topic).intersect(MemorySelector.keywords(cleanUser)).isEmpty()) {
            (earlierTopics + topic).distinct().takeLast(6)
        } else earlierTopics
        val question = ConversationContext.priorQuestion(reply).orEmpty()
        return copy(topic = nextTopic, pendingQuestion = question, earlierTopics = earlier, updatedAt = now)
    }

    fun relevantTopic(message: String): String? {
        val terms = MemorySelector.keywords(message)
        if (terms.isEmpty()) return null
        return (listOf(topic) + earlierTopics.asReversed()).firstOrNull {
            terms.intersect(MemorySelector.keywords(it)).isNotEmpty()
        }
    }
}

internal class ConversationManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("aria_conversation_state_v1", Context.MODE_PRIVATE)

    @Synchronized fun snapshot(): ConversationState {
        val raw = prefs.getString("state", null) ?: return ConversationState()
        return try {
            val obj = JSONObject(raw)
            val array = obj.optJSONArray("earlierTopics") ?: JSONArray()
            ConversationState(obj.optString("topic"), obj.optString("pendingQuestion"),
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.takeLast(6),
                obj.optLong("updatedAt"))
        } catch (_: Exception) { ConversationState() }
    }

    @Synchronized fun record(user: String, reply: String) {
        val state = snapshot().afterExchange(user, reply)
        val obj = JSONObject().put("topic", state.topic).put("pendingQuestion", state.pendingQuestion)
            .put("earlierTopics", JSONArray(state.earlierTopics)).put("updatedAt", state.updatedAt)
        check(prefs.edit().putString("state", obj.toString()).commit()) { "No pude guardar el estado de conversación." }
    }
}
