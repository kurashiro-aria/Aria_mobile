package com.kura.aria.emotion

/** Emotion Engine v1. Keeps UI state separate from ARIA's personality and memory. */
enum class AriaEmotion(val tile: Int, val label: String) {
    NEUTRAL(0, "neutral"), HAPPY(1, "feliz"), AMUSED(2, "divertida"), THINKING(3, "pensando"),
    SURPRISED(4, "sorprendida"), CONFUSED(5, "confundida"), ANNOYED(6, "molesta"), ANGRY(7, "enojada"),
    EMBARRASSED(8, "avergonzada"), SAD(9, "triste"), AFFECTIONATE(10, "cariñosa"), PLAYFUL(11, "juguetona"),
    SERIOUS(12, "seria"), TIRED(13, "cansada"), EXCITED(14, "entusiasta");

    companion object {
        /** The portrait reacts to Kura's situation as well as ARIA's words. */
        fun fromExchange(user: String, reply: String, previousUser: String? = null): AriaEmotion {
            return fromMood(MoodReader.forTurn(user, previousUser), user, reply)
        }

        internal fun fromMood(mood: ConversationMood, user: String, reply: String): AriaEmotion {
            val expressed = fromReply(reply)
            return when (mood) {
                ConversationMood.RELAXED -> if (expressed == NEUTRAL) NEUTRAL else expressed
                ConversationMood.FOCUSED -> if (reply.isBlank()) THINKING else if (expressed == NEUTRAL) SERIOUS else expressed
                ConversationMood.CURIOUS -> if (expressed == SURPRISED) SURPRISED else THINKING
                ConversationMood.SHY -> EMBARRASSED
                ConversationMood.VULNERABLE -> if (reply.isBlank() || MoodReader.isGrief(user)) SAD else AFFECTIONATE
                ConversationMood.URGENT, ConversationMood.FRUSTRATED -> SERIOUS
                ConversationMood.TIRED -> AFFECTIONATE
                ConversationMood.JOYFUL -> if (expressed == EXCITED) EXCITED else HAPPY
                ConversationMood.PLAYFUL -> when (expressed) {
                    AMUSED, SURPRISED, EMBARRASSED, ANNOYED, PLAYFUL -> expressed
                    else -> PLAYFUL
                }
                ConversationMood.NEUTRAL -> expressed
            }
        }

        fun fromReply(text: String): AriaEmotion {
            val s = text.lowercase()
            val scored = listOf(
                EXCITED to score(s, "lo logramos", "¡vamos", "funcionó", "no me lo creo", "qué emoción"),
                SAD to score(s, "triste", "lo siento", "duele", "pena", "preocupa"),
                ANGRY to score(s, "me enfada", "furiosa", "basta"),
                ANNOYED to score(s, "hmpf", "tch", "qué pesado", "molesta", "🙄"),
                EMBARRASSED to score(s, "vergüenza", "sonro", "no digas eso", "😳"),
                SURPRISED to score(s, "¿¡", "¡¿", "wow", "no me esperaba", "😮"),
                CONFUSED to score(s, "no entiendo", "confund", "¿cómo?", "qué raro"),
                AFFECTIONATE to score(s, "cariño", "me alegra que", "cuídate", "💜", "❤️"),
                PLAYFUL to score(s, "jeje", "😏", "te pillé", "tramposo"),
                AMUSED to score(s, "jaj", "😂", "🤣", "me hizo gracia"),
                HAPPY to score(s, "me alegra", "genial", "perfecto", "bien!", "😊", "✨"),
                THINKING to score(s, "hmm", "interesante", "me pregunto", "qué habrá", "curioso"),
                TIRED to score(s, "cansada", "sueño", "agotada", "😴"),
                SERIOUS to score(s, "importante", "en serio", "cuidado", "riesgo"),
            )
            val best = scored.maxByOrNull { it.second }
            if (best != null && best.second >= 2) return best.first
            return NEUTRAL
        }

        private fun score(text: String, vararg cues: String): Int = cues.count { text.contains(it) } * 2
    }
}
