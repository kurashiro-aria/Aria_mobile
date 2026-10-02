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
    val expression: ExpressionState = ExpressionState(),
    val initiativePending: Boolean = false
) {
    fun afterExchange(user: String, reply: String, now: Long = System.currentTimeMillis(),
                      stylePreferences: StylePreferences = StylePreferences()): ConversationState {
        val cleanUser = RoleplayInterpreter.spokenText(user).take(160)
        val pureAction = RoleplayInterpreter.isPureAction(user)
        val substantive = !pureAction && !ConversationContext.isStandaloneGreeting(cleanUser) &&
            !ConversationContext.isQualifiedAssent(cleanUser) &&
            MemorySelector.keywords(cleanUser).isNotEmpty() && !MemorySelector.isFollowUp(cleanUser)
        val nextTopic = if (substantive) cleanUser else topic.takeUnless(RoleplayInterpreter::hasRoleplay).orEmpty()
        val earlier = if (substantive && topic.isNotBlank() &&
            MemorySelector.keywords(topic).intersect(MemorySelector.keywords(cleanUser)).isEmpty()) {
            (earlierTopics + topic).distinct().takeLast(24)
        } else earlierTopics
        val question = if (substantive && !RoleplayInterpreter.hasRoleplay(user))
            ConversationContext.priorQuestion(reply).orEmpty() else ""
        // Ordinary offers/questions are answered in the active chat, not reminders.
        // Only an explicit proposal to resume later leaves an initiative-eligible thread.
        val deferred = substantive && question.isNotBlank() &&
            Regex("(?i)\\b(?:retomamos|seguimos\\s+(?:luego|despues|mas tarde))\\b")
                .containsMatchIn(question)
        val idleExpired = updatedAt > 0L && now - updatedAt > 30L * 60L * 1000L
        val observedMood = MoodReader.forTurn(user, topic, socialMood, updatedAt, now, socialTurns)
        val nextExpression = ExpressionResolver.forTurn(user, observedMood, expression, updatedAt, now,
            stylePreferences)
        // Expiration must win over emotion inferred from ARIA's reply. Otherwise a neutral
        // acknowledgement such as "Entendido." can be reclassified (for example as THINKING)
        // and resurrect the social tone that just expired.
        val carriedMoodExpired = socialMood != ConversationMood.NEUTRAL &&
            (idleExpired || (observedMood == ConversationMood.NEUTRAL && socialTurns >= 3))
        val mood = if (carriedMoodExpired) {
            ConversationMood.NEUTRAL
        } else if (observedMood == ConversationMood.NEUTRAL) when (AriaEmotion.fromReply(reply)) {
            AriaEmotion.PLAYFUL, AriaEmotion.AMUSED -> ConversationMood.PLAYFUL
            AriaEmotion.THINKING, AriaEmotion.SURPRISED -> ConversationMood.CURIOUS
            AriaEmotion.EMBARRASSED -> ConversationMood.SHY
            AriaEmotion.EXCITED -> ConversationMood.JOYFUL
            else -> observedMood
        } else observedMood
        val nextSocialTurns = when {
            mood == ConversationMood.NEUTRAL -> 0
            socialMood == ConversationMood.NEUTRAL || mood != socialMood -> 0
            else -> (socialTurns + 1).coerceAtMost(3)
        }
        return copy(topic = nextTopic, pendingQuestion = question, earlierTopics = earlier,
            updatedAt = now, socialMood = mood, expression = nextExpression,
            socialTurns = nextSocialTurns, initiativePending = deferred)
    }

    fun relevantTopic(message: String): String? {
        val terms = MemorySelector.keywords(message)
        if (terms.isEmpty()) return null
        return (listOf(topic) + earlierTopics.asReversed()).firstOrNull {
            !RoleplayInterpreter.hasRoleplay(it) &&
                !ConversationContext.isQualifiedAssent(it) &&
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
            val savedTopic = obj.optString("topic")
            val staleTopic = RoleplayInterpreter.hasRoleplay(savedTopic) ||
                ConversationContext.isStandaloneGreeting(savedTopic) ||
                ConversationContext.isQualifiedAssent(savedTopic)
            ConversationState(if (staleTopic) "" else savedTopic,
                if (staleTopic) "" else obj.optString("pendingQuestion"),
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                    .filterNot(RoleplayInterpreter::hasRoleplay)
                    .filterNot(ConversationContext::isQualifiedAssent).takeLast(24),
                obj.optLong("updatedAt"), mood, obj.optInt("socialTurns", 0).coerceIn(0, 3),
                ExpressionState(style, obj.optDouble("expressionIntensity", 0.0).toFloat().coerceIn(0f, 1f),
                    obj.optInt("expressionTurns", 0).coerceIn(0, 5), obj.optBoolean("expressionRequested", false)),
                !staleTopic && obj.optBoolean("initiativePending", false))
        } catch (_: Exception) { ConversationState() }
    }

    @Synchronized fun record(user: String, reply: String,
                             stylePreferences: StylePreferences = StylePreferences()) {
        val state = snapshot().afterExchange(user, reply, stylePreferences = stylePreferences)
        val obj = JSONObject().put("topic", state.topic).put("pendingQuestion", state.pendingQuestion)
            .put("initiativePending", state.initiativePending)
            .put("earlierTopics", JSONArray(state.earlierTopics)).put("updatedAt", state.updatedAt)
            .put("socialMood", state.socialMood.name).put("socialTurns", state.socialTurns)
            .put("expressionStyle", state.expression.style.name)
            .put("expressionIntensity", state.expression.intensity.toDouble())
            .put("expressionTurns", state.expression.turns)
            .put("expressionRequested", state.expression.requestedByKura)
        check(prefs.edit().putString("state", obj.toString()).commit()) { "No pude guardar el estado de conversación." }
    }
}
