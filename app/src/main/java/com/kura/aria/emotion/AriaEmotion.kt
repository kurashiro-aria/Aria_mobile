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
                ConversationMood.CURIOUS -> if (expressed == NEUTRAL) THINKING else expressed
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
            // A strong emotion in ARIA's actual answer is authoritative.  The
            // requested style describes the turn, but must not flatten what
            // ARIA really said into SERIOUS/NEUTRAL.
            val expressed = fromReply(reply)
            if (expressed != NEUTRAL && expressed != THINKING) return expressed
            if (mood in setOf(ConversationMood.URGENT, ConversationMood.VULNERABLE,
                    ConversationMood.FRUSTRATED, ConversationMood.FOCUSED)) return base
            if (expression.style == ExpressionStyle.NATURAL && expression.requestedByKura)
                return fromReply(reply)
            if (expression.style == ExpressionStyle.SERIOUS) return SERIOUS
            if (expression.style == ExpressionStyle.FOCUSED) return if (reply.isBlank()) THINKING else SERIOUS
            if (expression.style == ExpressionStyle.FLIRTY && reply.isNotBlank() && base == NEUTRAL &&
                Regex("(?i)\\b(?:linda|hermosa|guapa|preciosa|me gustas)\\b").containsMatchIn(user))
                return EMBARRASSED
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

        /** Derives the vocal style from the same resolved emotion used by the portrait. */
        internal fun styleFor(emotion: AriaEmotion, requested: ExpressionStyle): ExpressionStyle {
            if (requested != ExpressionStyle.NATURAL) return requested
            return when (emotion) {
                HAPPY, AMUSED, PLAYFUL -> ExpressionStyle.PLAYFUL
                EXCITED, SURPRISED -> ExpressionStyle.EXCITED
                AFFECTIONATE -> ExpressionStyle.AFFECTIONATE
                EMBARRASSED -> ExpressionStyle.SHY
                SERIOUS, ANNOYED, ANGRY -> ExpressionStyle.SERIOUS
                SAD, TIRED -> ExpressionStyle.COMFORTING
                else -> ExpressionStyle.NATURAL
            }
        }

        fun fromReply(text: String): AriaEmotion {
            val s = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("\\bno (?:estoy|me siento|ando) (?:triste|enojada|enfadada|feliz|contenta|cansada|agotada|confundida)\\b"), "")
            val scored = listOf(
                EXCITED to score(s, "lo logramos", "¡vamos", "vamos", "funciono!", "no me lo creo", "que emocion", "me emociona", "emocionada", "entusiasmada", "euforica", "increible", "que genial", "🎉"),
                SAD to score(s, "triste", "entristece", "apenada", "desanimada", "melancolica", "me da pena", "me duele", "😢"),
                ANGRY to score(s, "me enfada", "furiosa", "enojada", "indignada", "me da rabia", "estoy que ardo", "basta", "me enfurece", "no lo tolero"),
                ANNOYED to score(s, "hmpf", "tch", "que pesado", "que pesado eres", "que cruel eres", "no empieces", "me fastidia", "me irrita", "molesta", "🙄"),
                EMBARRASSED to score(s, "verguenza", "sonrojo", "sonrojar", "me vas a hacer sonrojar", "me pones nerviosa", "me pones roja", "sonrojada", "avergonzada", "ruborizada", "no digas eso", "😳"),
                SURPRISED to score(s, "¿¡", "¡¿", "wow", "no me lo esperaba", "no puede ser", "que sorpresa", "sorprendida", "asombrada", "atónita", "¿en serio?", "😮"),
                CONFUSED to score(s, "no entiendo", "confundida", "desconcertada", "no me queda claro", "¿como?", "que raro"),
                AFFECTIONATE to score(s, "carino", "te quiero", "me importas", "cuidate", "me alegra verte", "ven aqui", "con ternura", "te acompano", "te acompanaria", "te acompane", "acompanarte", "cuenta conmigo", "💜", "❤️"),
                PLAYFUL to score(s, "jeje", "😏", "😉", "te pille", "tramposo", "travieso", "bromista", "que cruel jajaja", "anda ya", "te tomo el pelo", "te sigo el juego"),
                AMUSED to score(s, "jaj", "jajaja", "😂", "🤣", "me hizo gracia", "me divierte", "que risa"),
                HAPPY to score(s, "me alegra", "me alegro", "feliz", "contenta", "alegre", "genial", "perfecto", "eso si me gusto", "que bonito", "que rico", "suena delicioso", "suena bien", "me apunto", "me encanta", "bien!", "sonrio", "😊", "✨"),
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
