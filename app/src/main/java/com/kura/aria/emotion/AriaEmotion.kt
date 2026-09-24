package com.kura.aria.emotion

import com.kura.aria.personality.ExpressionState
import com.kura.aria.personality.ExpressionStyle
import java.text.Normalizer

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
                ConversationMood.SHY -> if (expressed == NEUTRAL) EMBARRASSED else expressed
                ConversationMood.VULNERABLE -> if (reply.isBlank() || MoodReader.isGrief(user)) SAD else AFFECTIONATE
                ConversationMood.URGENT, ConversationMood.FRUSTRATED -> SERIOUS
                ConversationMood.TIRED -> expressed
                ConversationMood.JOYFUL -> if (expressed == NEUTRAL) HAPPY else expressed
                ConversationMood.PLAYFUL -> when (expressed) {
                    NEUTRAL, THINKING -> PLAYFUL
                    else -> expressed
                }
                ConversationMood.NEUTRAL -> expressed
            }
        }

        internal fun fromInteraction(mood: ConversationMood, expression: ExpressionState,
                                     user: String, reply: String): AriaEmotion {
            val base = fromMood(mood, user, reply)
            if (mood in setOf(ConversationMood.URGENT, ConversationMood.VULNERABLE,
                    ConversationMood.FRUSTRATED, ConversationMood.FOCUSED)) return base
            if (expression.style == ExpressionStyle.NATURAL && expression.requestedByKura)
                return fromReply(reply)
            if (expression.style == ExpressionStyle.SERIOUS) return SERIOUS
            if (expression.style == ExpressionStyle.FOCUSED) return if (reply.isBlank()) THINKING else SERIOUS
            // Once ARIA speaks, her own words take priority over a suggested style.
            if (reply.isNotBlank() && base != NEUTRAL && base != THINKING) return base
            return when (expression.style) {
                ExpressionStyle.FLIRTY, ExpressionStyle.TEASING, ExpressionStyle.PLAYFUL ->
                    if (mood == ConversationMood.SHY) EMBARRASSED else PLAYFUL
                ExpressionStyle.AFFECTIONATE, ExpressionStyle.COMFORTING -> AFFECTIONATE
                ExpressionStyle.SHY -> EMBARRASSED
                ExpressionStyle.SARCASTIC_LIGHT -> AMUSED
                ExpressionStyle.SERIOUS, ExpressionStyle.FOCUSED -> SERIOUS
                ExpressionStyle.EXCITED -> EXCITED
                ExpressionStyle.NATURAL -> base
            }
        }

        fun fromReply(text: String): AriaEmotion {
            val s = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("\\bno (?:estoy|me siento|ando) (?:triste|enojada|enfadada|feliz|contenta|cansada|agotada|confundida)\\b"), "")
            val scored = listOf(
                EXCITED to score(s, "lo logramos", "¡vamos", "funciono!", "no me lo creo", "que emocion", "emocionada", "entusiasmada", "euforica", "🎉"),
                SAD to score(s, "triste", "entristece", "apenada", "desanimada", "melancolica", "me da pena", "me duele", "😢"),
                ANGRY to score(s, "me enfada", "furiosa", "enojada", "indignada", "me da rabia", "estoy que ardo"),
                ANNOYED to score(s, "hmpf", "tch", "que pesado", "me fastidia", "me irrita", "molesta", "🙄"),
                EMBARRASSED to score(s, "verguenza", "sonrojo", "sonrojada", "avergonzada", "ruborizada", "no digas eso", "😳"),
                SURPRISED to score(s, "¿¡", "¡¿", "wow", "no me lo esperaba", "sorprendida", "asombrada", "atónita", "😮"),
                CONFUSED to score(s, "no entiendo", "confundida", "desconcertada", "no me queda claro", "¿como?", "que raro"),
                AFFECTIONATE to score(s, "carino", "te quiero", "me importas", "cuídate", "cuidate", "con ternura", "💜", "❤️"),
                PLAYFUL to score(s, "jeje", "😏", "te pille", "tramposo", "travieso", "bromista", "te tomo el pelo"),
                AMUSED to score(s, "jaj", "jajaja", "😂", "🤣", "me hizo gracia", "me divierte", "que risa"),
                HAPPY to score(s, "me alegra", "feliz", "contenta", "alegre", "genial", "perfecto", "bien!", "sonrio", "😊", "✨"),
                THINKING to score(s, "hmm", "interesante", "me pregunto", "que habra", "curioso", "dejame pensar", "reflexionar", "🤔"),
                TIRED to score(s, "cansada", "tengo sueno", "agotada", "exhausta", "sonolienta", "me vence el sueno", "😴"),
                SERIOUS to score(s, "importante", "en serio", "cuidado", "riesgo", "delicado", "preocupante", "debemos atender"),
            )
            val best = scored.maxByOrNull { it.second }
            if (best != null && best.second >= 2) return best.first
            return NEUTRAL
        }

        private fun score(text: String, vararg cues: String): Int = cues.count { cue ->
            val normalized = Normalizer.normalize(cue.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
            val pattern = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(normalized)}(?![\\p{L}\\p{N}])")
            pattern.containsMatchIn(text)
        } * 2
    }
}
