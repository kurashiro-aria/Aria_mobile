package com.kura.aria.personality

import com.kura.aria.chat.ChatMessage
import com.kura.aria.chat.ReplyQuality
import com.kura.aria.memory.AriaMemory
import com.kura.aria.memory.MemorySelector
import java.text.Normalizer

/** Phase 1: cheap turn interpretation. The Cloud Brain decides what ARIA says. */
internal enum class ConversationIntent {
    NEW_TOPIC, FOLLOW_UP, ANSWER, QUESTION, ROLEPLAY_ACTION, ROLEPLAY_MIXED, RECALL, COMMAND, SOCIAL
}

internal enum class TurnContinuity { NEW, CONTINUES, RETURNS_TO_TOPIC }

internal data class ConversationTurn(
    val intent: ConversationIntent,
    val continuity: TurnContinuity,
    val spokenText: String,
    val recent: List<ChatMessage>,
    val relevantTopic: String?,
    val pendingQuestion: String?,
    val hasContextReference: Boolean,
    val roleplay: List<RoleplayAction>,
    val conversationState: ConversationState
)

internal object ConversationBrain {
    private val explicitReturn = Regex("^(?:(?:bueno|vale|ok|ya|jaja\\w*)[,;.!]?\\s+)*(?:volvamos|retomemos|regresemos)\\b")
    private val deictic = Regex("\\b(?:eso|esto|esa|ese|esos|esas|el otro|la otra|ese mismo|esa misma|lo que dijiste|lo que hablamos antes|lo anterior|y despues|y entonces)\\b")
    private val choice = Regex("^(?:(?:si|no|claro|vale|exacto|ok|dale|por supuesto)[,;:]?\\s+)?(?:uno|una|el otro|la otra|ese mismo|esa misma|de [a-z]+)\\b")
    private val socialReaction = Regex("^(?:me gusta|me encanta|suena|que rico|perfecto|genial|buena idea|jaja)\\b")

    fun interpret(history: List<ChatMessage>, current: String,
                  state: ConversationState = ConversationState()): ConversationTurn {
        require(current.isNotBlank())
        val spoken = RoleplayInterpreter.spokenText(current)
        val actions = RoleplayInterpreter.actions(current)
        val normalized = normalize(spoken)
        val greeting = ConversationContext.isStandaloneGreeting(spoken)
        val lastAria = history.lastOrNull()?.takeIf { it.role == "ARIA" &&
            !ReplyQuality.hasTranscript(it.text) }
        val question = lastAria?.let { ConversationContext.priorQuestion(it.text) }
            ?: state.pendingQuestion.takeIf { history.isEmpty() && it.isNotBlank() }
        val returns = explicitReturn.containsMatchIn(normalized)
        val bareAssent = normalized.matches(Regex("^(?:si|no|vale|claro|exacto|ok|dale|puede ser)[.!\\s]*$"))
        val reference = deictic.containsMatchIn(normalized) ||
            (MemorySelector.isFollowUp(spoken) && !bareAssent) ||
            (ConversationContext.isQualifiedAssent(spoken) && question != null) ||
            ConversationPerspective.shortClarification(spoken, lastAria?.text) ||
            ConversationContext.respondsToPriorTurn(spoken, lastAria?.text)
        val answer = question != null && (bareAssent || choice.containsMatchIn(normalized) ||
            (ConversationContext.isQualifiedAssent(spoken) &&
                !Regex("\\b(?:hace|tengo|voy|estoy|quiero)\\b").containsMatchIn(normalized)) ||
            (normalized.split(Regex("\\s+")).size == 1 &&
                Regex("\\b(?:sabor|color|cual|tipo|nombre|como estas)\\b").containsMatchIn(normalize(question))))
        val reaction = lastAria != null && (socialReaction.containsMatchIn(normalized) ||
            normalized.startsWith("prefiero ")) &&
            spoken.length <= 110
        val lexical = lastAria != null && spoken.length <= 120 &&
            MemorySelector.keywords(spoken).let { terms -> terms.isNotEmpty() &&
                history.takeLast(2).any { prior ->
                    terms.intersect(MemorySelector.keywords(RoleplayInterpreter.spokenText(prior.text))).isNotEmpty()
                } }
        val sceneContinues = actions.isNotEmpty() && history.takeLast(2)
            .any { it.role == "Kura" && RoleplayInterpreter.hasRoleplay(it.text) }
        val continuity = when {
            greeting -> TurnContinuity.NEW
            returns -> TurnContinuity.RETURNS_TO_TOPIC
            actions.isNotEmpty() && spoken.isBlank() ->
                if (sceneContinues) TurnContinuity.CONTINUES else TurnContinuity.NEW
            answer || ((reference || reaction || lexical) && lastAria != null) || sceneContinues ->
                TurnContinuity.CONTINUES
            else -> TurnContinuity.NEW
        }
        val intent = when {
            actions.isNotEmpty() && spoken.isBlank() -> ConversationIntent.ROLEPLAY_ACTION
            actions.isNotEmpty() -> ConversationIntent.ROLEPLAY_MIXED
            greeting -> ConversationIntent.SOCIAL
            AriaMemory.command(spoken) != null -> ConversationIntent.COMMAND
            returns -> ConversationIntent.RECALL
            answer && continuity == TurnContinuity.CONTINUES -> ConversationIntent.ANSWER
            continuity == TurnContinuity.CONTINUES -> ConversationIntent.FOLLOW_UP
            spoken.trimEnd().endsWith('?') -> ConversationIntent.QUESTION
            else -> ConversationIntent.NEW_TOPIC
        }
        // Never feed a whole history. Two adjacent exchanges cover an answer chain;
        // a fresh topic or greeting starts with a clean slate.
        val recent = if (continuity == TurnContinuity.CONTINUES) history.takeLast(4)
            .filter { it.role == "ARIA" || !RoleplayInterpreter.isPureAction(it.text) }
        else emptyList()
        val topic = if (continuity == TurnContinuity.RETURNS_TO_TOPIC)
            state.relevantTopic(spoken) ?: state.earlierTopics.lastOrNull()
                ?.takeUnless(RoleplayInterpreter::hasRoleplay) else null
        return ConversationTurn(intent, continuity, spoken, recent, topic,
            if (answer && lastAria == null) question else null,
            reference || answer, actions, state)
    }

    private fun normalize(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").trim().trimStart('¿', '¡')
}
