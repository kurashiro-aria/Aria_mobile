package com.kura.aria.personality

/** Only resumes a real unanswered thread after a long absence and with explicit opt-in. */
internal object InitiativePolicy {
    private const val TWO_HOURS = 2 * 60 * 60 * 1000L
    private const val SIX_HOURS = 6 * 60 * 60 * 1000L

    fun suggestion(state: ConversationState, enabled: Boolean, now: Long,
                   lastInitiative: Long, localHour: Int): String? {
        if (!enabled || localHour !in 8..22 || state.topic.isBlank() || state.pendingQuestion.isBlank()) return null
        if (state.updatedAt <= 0 || now - state.updatedAt < TWO_HOURS ||
            (lastInitiative > 0 && now - lastInitiative < SIX_HOURS) ||
            (lastInitiative > 0 && lastInitiative >= state.updatedAt)) return null
        val subject = state.topic.take(75).trimEnd().trimEnd('.', '?', '!')
        return "Si quieres, podemos retomar lo que me contabas: «$subject»."
    }
}
