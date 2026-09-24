package com.kura.aria.personality

import android.content.Context
import com.kura.aria.emotion.ConversationMood
import com.kura.aria.emotion.MoodReader
import com.kura.aria.emotion.AriaEmotion
import com.kura.aria.memory.MemorySelector
import org.json.JSONArray
import org.json.JSONObject

/** Small, local conversation state. It contains Kura's words, never generated summaries. */
internal data class ConversationState(
    val topic: String = "",
    val pendingQuestion: String = "",
    val earlierTopics: List<String> = emptyList(),
    val updatedAt: Long = 0L,
    val socialMood: ConversationMood = ConversationMood.NEUTRAL,
    val socialTurns: Int = 0
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
        val observedMood = MoodReader.forTurn(user, topic, socialMood, updatedAt, now, socialTurns)
        val mood = if (observedMood == ConversationMood.NEUTRAL) when (AriaEmotion.fromReply(reply)) {
            AriaEmotion.PLAYFUL, AriaEmotion.AMUSED -> ConversationMood.PLAYFUL
            AriaEmotion.THINKING, AriaEmotion.SURPRISED -> ConversationMood.CURIOUS
            AriaEmotion.EMBARRASSED -> ConversationMood.SHY
            AriaEmotion.EXCITED -> ConversationMood.JOYFUL
            else -> observedMood
        } else observedMood
        return copy(topic = nextTopic, pendingQuestion = question, earlierTopics = earlier,
            updatedAt = now, socialMood = mood,
            socialTurns = if (mood == socialMood) (socialTurns + 1).coerceAtMost(3) else 0)
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
            val mood = runCatching { ConversationMood.valueOf(obj.optString("socialMood")) }
                .getOrDefault(ConversationMood.NEUTRAL)
            ConversationState(obj.optString("topic"), obj.optString("pendingQuestion"),
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.takeLast(6),
                obj.optLong("updatedAt"), mood, obj.optInt("socialTurns", 0).coerceIn(0, 3))
        } catch (_: Exception) { ConversationState() }
    }

    @Synchronized fun record(user: String, reply: String) {
        val state = snapshot().afterExchange(user, reply)
        val obj = JSONObject().put("topic", state.topic).put("pendingQuestion", state.pendingQuestion)
            .put("earlierTopics", JSONArray(state.earlierTopics)).put("updatedAt", state.updatedAt)
            .put("socialMood", state.socialMood.name).put("socialTurns", state.socialTurns)
        check(prefs.edit().putString("state", obj.toString()).commit()) { "No pude guardar el estado de conversación." }
    }
}
