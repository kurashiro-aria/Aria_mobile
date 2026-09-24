package com.kura.aria.emotion

import com.kura.aria.memory.MemorySelector
import java.text.Normalizer

/** Lightweight signal from Kura's own words; it is not a diagnosis or permanent memory. */
internal enum class ConversationMood(val guidance: String) {
    NEUTRAL(""),
    VULNERABLE("Escucha el hecho concreto que contó Kura y responde con cuidado. No dramatices, diagnostiques ni repitas fórmulas de consuelo."),
    URGENT("Da prioridad al problema concreto y habla con claridad y seriedad; evita bromas."),
    FRUSTRATED("Reconoce la molestia brevemente y ve a algo útil; no repitas disculpas ni cambies de tema."),
    TIRED("Mantén un tono suave y breve, sin exigirle energía ni asumir qué necesita."),
    JOYFUL("Reacciona a lo que Kura consiguió o compartió, con alegría propia y una observación concreta; evita felicitaciones prefabricadas."),
    PLAYFUL("Sigue el juego desde tu personalidad si encaja: puedes sorprenderte, picarte o reírte. No fuerces la misma broma ni una pregunta final.")
}

internal object MoodReader {
    fun isGrief(message: String): Boolean = Regex("\\b(?:falleci[oó]|muri[oó]|perd[ií] a)\\b")
        .containsMatchIn(message.lowercase())

    fun forTurn(current: String, previousUser: String? = null): ConversationMood {
        val mood = detect(current)
        if (mood != ConversationMood.NEUTRAL) return mood
        // A short answer can keep the preceding tone; an unrelated message starts fresh.
        return if (previousUser != null && MemorySelector.isFollowUp(current) &&
            MemorySelector.keywords(current).size <= 2) detect(previousUser)
        else ConversationMood.NEUTRAL
    }

    private fun detect(message: String): ConversationMood {
        val s = Normalizer.normalize(message.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        if (Regex("\\b(?:infarto|emergencia|urgencia|accidente|peligro|no puedo respirar)\\b").containsMatchIn(s))
            return ConversationMood.URGENT
        val negatedSadness = Regex("\\bno (?:estoy|me siento|ando) (?:triste|mal|deprimido)\\b").containsMatchIn(s)
        if (!negatedSadness && Regex("\\b(?:estoy triste|me siento triste|me siento mal|tengo miedo|estoy asustado|estoy deprimido|fallecio|murio|me duele|estoy preocupado|tengo ansiedad)\\b").containsMatchIn(s))
            return ConversationMood.VULNERABLE
        if (Regex("\\b(?:estoy cansado|estoy agotado|tengo sueno|no he dormido)\\b").containsMatchIn(s))
            return ConversationMood.TIRED
        if (Regex("\\b(?:lo logre|funciono|salio bien|estoy feliz|buenas noticias|ya termine)\\b").containsMatchIn(s))
            return ConversationMood.JOYFUL
        if (Regex("\\b(?:jaja+|jeje+|xd|es broma|te estoy molestando)\\b").containsMatchIn(s))
            return ConversationMood.PLAYFUL
        if (Regex("\\b(?:me frustra|estoy frustrado|estoy enojado|me molesta|odio|no funciona|sigue fallando)\\b").containsMatchIn(s))
            return ConversationMood.FRUSTRATED
        return ConversationMood.NEUTRAL
    }
}
