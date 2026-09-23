package com.kura.aria.emotion

/** Emotion Engine v1. Keeps UI state separate from ARIA's personality and memory. */
enum class AriaEmotion(val tile: Int, val label: String) {
    NEUTRAL(0, "neutral"), HAPPY(1, "feliz"), AMUSED(2, "divertida"), THINKING(3, "pensando"),
    SURPRISED(4, "sorprendida"), CONFUSED(5, "confundida"), ANNOYED(6, "molesta"), ANGRY(7, "enojada"),
    EMBARRASSED(8, "avergonzada"), SAD(9, "triste"), AFFECTIONATE(10, "cariñosa"), PLAYFUL(11, "juguetona"),
    SERIOUS(12, "seria"), TIRED(13, "cansada"), EXCITED(14, "entusiasta");

    companion object {
        /** The portrait reacts to Kura's situation as well as ARIA's words. */
        fun fromExchange(user: String, reply: String): AriaEmotion {
            val message = user.lowercase()
            if (listOf("me siento triste", "estoy triste", "me siento mal", "tengo miedo", "falleció", "fallecio", "murió", "murio", "me duele").any { it in message })
                return SAD
            if (listOf("infarto", "emergencia", "urgencia", "accidente", "peligro").any { it in message })
                return SERIOUS
            return fromReply(reply)
        }

        fun fromReply(text: String, previous: AriaEmotion = NEUTRAL): AriaEmotion {
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
            // Mild persistence: don't snap back to neutral after every ordinary sentence.
            return if (previous in setOf(ANNOYED, EMBARRASSED, SAD, AFFECTIONATE, PLAYFUL) && s.length < 180) previous else NEUTRAL
        }

        private fun score(text: String, vararg cues: String): Int = cues.count { text.contains(it) } * 2
    }
}
