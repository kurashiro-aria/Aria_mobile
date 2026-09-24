package com.kura.aria.emotion

import com.kura.aria.memory.MemorySelector
import java.text.Normalizer

/** Lightweight signal from Kura's own words; it is not a diagnosis or permanent memory. */
internal enum class ConversationMood(val guidance: String) {
    NEUTRAL(""),
    RELAXED("Conversa con naturalidad y sin forzar un tema nuevo."),
    FOCUSED("Sigue el trabajo concreto con claridad y criterio; conserva tu voz sin convertir la respuesta en un informe."),
    CURIOUS("Responde a lo que Kura pregunta y muestra curiosidad solo si aporta al tema."),
    SHY("Puedes mostrar una breve vergüenza juguetona si encaja; evita volverla una muletilla."),
    VULNERABLE("Escucha el hecho concreto que contó Kura y responde con cuidado. No dramatices, diagnostiques ni repitas fórmulas de consuelo."),
    URGENT("Da prioridad al problema concreto y habla con claridad y seriedad; evita bromas."),
    FRUSTRATED("Reconoce la molestia brevemente y ve a algo útil; no repitas disculpas ni cambies de tema."),
    TIRED("Mantén un tono suave y breve, sin exigirle energía ni asumir qué necesita."),
    JOYFUL("Reacciona a lo que Kura consiguió o compartió, con alegría propia y una observación concreta; evita felicitaciones prefabricadas."),
    PLAYFUL("Sigue el juego desde tu personalidad si encaja: puedes sorprenderte, picarte o reírte. No fuerces la misma broma ni una pregunta final.")
}

internal object MoodReader {
    private const val MAX_IDLE_MS = 30 * 60 * 1000L

    fun isGrief(message: String): Boolean = Regex("\\b(?:falleci[oó]|muri[oó]|perd[ií] a)\\b")
        .containsMatchIn(message.lowercase())

    fun forTurn(current: String, previousUser: String? = null,
                previousMood: ConversationMood = ConversationMood.NEUTRAL,
                previousAt: Long = 0L, now: Long = System.currentTimeMillis(),
                carriedTurns: Int = 0): ConversationMood {
        val mood = explicit(current)
        if (mood != ConversationMood.NEUTRAL) return mood
        val text = normalize(current).trim()
        if (text.matches(Regex("^[¿?¡! ]*(?:hola|buenas|como estas|que tal)(?: aria)?[¿?¡!. ]*$")))
            return ConversationMood.RELAXED
        if (Regex("\\b(?:revisa|arregla|codigo|proyecto|compila|error|prueba|trabajemos)\\b")
                .containsMatchIn(text)) return ConversationMood.FOCUSED
        val recent = previousAt == 0L || now - previousAt in 0..MAX_IDLE_MS
        val follows = MemorySelector.isFollowUp(current)
        val previousTerms = previousUser?.let(MemorySelector::keywords).orEmpty()
        val terms = MemorySelector.keywords(current)
        val sameTopic = terms.isNotEmpty() && terms.intersect(previousTerms).isNotEmpty()
        if (recent && carriedTurns < 3 && (follows || sameTopic)) {
            val held = previousMood.takeUnless { it == ConversationMood.NEUTRAL }
                ?: previousUser?.takeIf { previousAt == 0L }?.let(::explicit)
            if (held != null && held != ConversationMood.NEUTRAL) return held
        }
        if (current.trim().endsWith('?') || current.trim().startsWith('¿')) return ConversationMood.CURIOUS
        return ConversationMood.NEUTRAL
    }

    private fun normalize(message: String) = Normalizer.normalize(message.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")

    private fun explicit(message: String): ConversationMood {
        val s = normalize(message)
        if (Regex("\\b(?:infarto|emergencia|urgencia|accidente|peligro|no puedo respirar)\\b").containsMatchIn(s))
            return ConversationMood.URGENT
        val negatedSadness = Regex("\\bno (?:estoy|me siento|ando) (?:triste|mal|deprimido|preocupado|asustado)\\b").containsMatchIn(s)
        if (!negatedSadness && Regex("\\b(?:estoy triste|me siento triste|me siento mal|estoy desanimado|estoy decaido|tengo miedo|estoy asustado|estoy deprimido|fallecio|murio|me duele|estoy preocupado|me preocupa|tengo ansiedad)\\b").containsMatchIn(s))
            return ConversationMood.VULNERABLE
        if (Regex("\\b(?:estoy cansado|estoy agotado|estoy exhausto|tengo sueno|me muero de sueno|no he dormido)\\b").containsMatchIn(s))
            return ConversationMood.TIRED
        if (Regex("\\b(?:lo logre|funciono|salio bien|estoy feliz|estoy contento|me siento alegre|estoy alegre|me alegra|buenas noticias|ya termine)\\b").containsMatchIn(s))
            return ConversationMood.JOYFUL
        if (Regex("\\b(?:jaja+|jeje+|xd|es broma|te estoy molestando)\\b").containsMatchIn(s))
            return ConversationMood.PLAYFUL
        if (Regex("\\b(?:me da verguenza|me sonrojo|que verguenza|estoy avergonzado|me da pena decirlo)\\b").containsMatchIn(s))
            return ConversationMood.SHY
        if (Regex("\\b(?:me frustra|estoy frustrado|estoy enojado|me enfada|me irrita|me fastidia|me molesta|odio|no funciona|sigue fallando)\\b").containsMatchIn(s))
            return ConversationMood.FRUSTRATED
        return ConversationMood.NEUTRAL
    }
}
