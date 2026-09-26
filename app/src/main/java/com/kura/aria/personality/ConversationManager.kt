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
    val socialTurns: Int = 0,
    val expression: ExpressionState = ExpressionState()
) {
    fun afterExchange(user: String, reply: String, now: Long = System.currentTimeMillis(),
                      stylePreferences: StylePreferences = StylePreferences()): ConversationState {
        val cleanUser = user.replace(Regex("\\s+"), " ").trim().take(160)
        val substantive = MemorySelector.keywords(cleanUser).isNotEmpty() && !MemorySelector.isFollowUp(cleanUser)
        val nextTopic = if (substantive) cleanUser else topic
        val earlier = if (substantive && topic.isNotBlank() &&
            MemorySelector.keywords(topic).intersect(MemorySelector.keywords(cleanUser)).isEmpty()) {
            (earlierTopics + topic).distinct().takeLast(24)
        } else earlierTopics
        val question = ConversationContext.priorQuestion(reply).orEmpty()
        val observedMood = MoodReader.forTurn(user, topic, socialMood, updatedAt, now, socialTurns)
        val nextExpression = ExpressionResolver.forTurn(user, observedMood, expression, updatedAt, now,
            stylePreferences)
        // Once a carried social mood has reached its turn limit, keep the neutral result neutral.
        // Otherwise AriaEmotion.fromReply(reply) can immediately resurrect the expired mood from
        // generic replies such as "Perfecto" or "Vamos", making the tone effectively permanent.
        val carriedMoodExpired = observedMood == ConversationMood.NEUTRAL &&
            socialMood != ConversationMood.NEUTRAL && socialTurns >= 3
        val mood = if (observedMood == ConversationMood.NEUTRAL && !carriedMoodExpired) when (AriaEmotion.fromReply(reply)) {
            AriaEmotion.PLAYFUL, AriaEmotion.AMUSED -> ConversationMood.PLAYFUL
            AriaEmotion.THINKING, AriaEmotion.SURPRISED -> ConversationMood.CURIOUS
            AriaEmotion.EMBARRASSED -> ConversationMood.SHY
            AriaEmotion.EXCITED -> ConversationMood.JOYFUL
            else -> observedMood
        } else observedMood
        // socialTurns counts how many subsequent turns a non-neutral social mood has been carried.
        // A newly detected mood starts at zero; each continuation increments it. When the reader
        // finally yields to neutral, reset the counter instead of comparing against the old mood.
        val nextSocialTurns = when {
            mood == ConversationMood.NEUTRAL -> 0
            mood == socialMood -> (socialTurns + 1).coerceAtMost(3)
            else -> 0
        }
        return copy(topic = nextTopic, pendingQuestion = question, earlierTopics = earlier,
            updatedAt = now, socialMood = mood, expression = nextExpression,
            socialTurns = nextSocialTurns)
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
            val style = runCatching { ExpressionStyle.valueOf(obj.optString("expressionStyle")) }
                .getOrDefault(ExpressionStyle.NATURAL)
            ConversationState(obj.optString("topic"), obj.optString("pendingQuestion"),
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.takeLast(24),
                obj.optLong("updatedAt"), mood, obj.optInt("socialTurns", 0).coerceIn(0, 3),
                ExpressionState(style, obj.optDouble("expressionIntensity", 0.0).toFloat().coerceIn(0f, 1f),
                    obj.optInt("expressionTurns", 0).coerceIn(0, 5), obj.optBoolean("expressionRequested", false)))
        } catch (_: Exception) { ConversationState() }
    }

    @Synchronized fun record(user: String, reply: String,
                             stylePreferences: StylePreferences = StylePreferences()) {
        val state = snapshot().afterExchange(user, reply, stylePreferences = stylePreferences)
        val obj = JSONObject().put("topic", state.topic).put("pendingQuestion", state.pendingQuestion)
            .put("earlierTopics", JSONArray(state.earlierTopics)).put("updatedAt", state.updatedAt)
            .put("socialMood", state.socialMood.name).put("socialTurns", state.socialTurns)
            .put("expressionStyle", state.expression.style.name)
            .put("expressionIntensity", state.expression.intensity.toDouble())
            .put("expressionTurns", state.expression.turns)
            .put("expressionRequested", state.expression.requestedByKura)
        check(prefs.edit().putString("state", obj.toString()).commit()) { "No pude guardar el estado de conversación." }
    }
}
