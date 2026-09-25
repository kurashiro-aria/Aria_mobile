package com.kura.aria.personality

/**
 * Decides when ARIA may reopen a real unfinished thread after an absence.
 * Initiative remains conservative: explicit opt-in, daytime use, a real pending
 * thread, and cooldowns are all required. The wording deliberately avoids the
 * old generic "si quieres podemos..." offer pattern.
 */
internal object InitiativePolicy {
    private const val TWO_HOURS = 2 * 60 * 60 * 1000L
    private const val SIX_HOURS = 6 * 60 * 60 * 1000L

    fun suggestion(state: ConversationState, enabled: Boolean, now: Long,
                   lastInitiative: Long, localHour: Int): String? {
        if (!enabled || localHour !in 8..22 || state.topic.isBlank() || state.pendingQuestion.isBlank()) return null
        if (state.updatedAt <= 0 || now - state.updatedAt < TWO_HOURS ||
            (lastInitiative > 0 && now - lastInitiative < SIX_HOURS) ||
            (lastInitiative > 0 && lastInitiative >= state.updatedAt)) return null

        val subject = state.topic.take(90).trim().trimEnd('.', '?', '!')
        if (subject.isBlank()) return null

        // Stable variation per thread: different topics do not all receive the same opener,
        // while reopening the same state cannot randomly oscillate during one session.
        return when (positiveBucket(subject.hashCode(), 4)) {
            0 -> "Me quedé pensando en lo de «$subject». ¿Qué terminó pasando con eso?"
            1 -> "Hay algo de nuestra conversación anterior que todavía me da curiosidad: «$subject»."
            2 -> "Oye, no me olvidé de «$subject». Me quedé con ganas de saber cómo siguió."
            else -> "Antes de que cambiemos de tema: me acordé de «$subject»."
        }
    }

    private fun positiveBucket(value: Int, size: Int): Int = (value and Int.MAX_VALUE) % size
}
