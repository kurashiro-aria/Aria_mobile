package com.kura.aria.emotion

import org.junit.Assert.assertEquals
import org.junit.Test

class AriaEmotionTest {
    @Test fun recognizesPortraitEmotions() {
        assertEquals(AriaEmotion.NEUTRAL, AriaEmotion.fromReply("De acuerdo, lo reviso."))
        assertEquals(AriaEmotion.HAPPY, AriaEmotion.fromReply("Genial, quedó listo."))
        assertEquals(AriaEmotion.EXCITED, AriaEmotion.fromReply("¡Lo logramos! Funcionó."))
        assertEquals(AriaEmotion.THINKING, AriaEmotion.fromReply("Hmm, interesante."))
        assertEquals(AriaEmotion.AMUSED, AriaEmotion.fromReply("Jajaja, me hizo gracia."))
        assertEquals(AriaEmotion.SURPRISED, AriaEmotion.fromReply("Wow, no me lo esperaba."))
        assertEquals(AriaEmotion.CONFUSED, AriaEmotion.fromReply("Qué raro, no entiendo."))
        assertEquals(AriaEmotion.ANNOYED, AriaEmotion.fromReply("Hmpf, qué pesado."))
        assertEquals(AriaEmotion.ANGRY, AriaEmotion.fromReply("Basta, me enfada."))
        assertEquals(AriaEmotion.EMBARRASSED, AriaEmotion.fromReply("Me da vergüenza 😳"))
        assertEquals(AriaEmotion.SAD, AriaEmotion.fromReply("Qué triste, me da pena."))
        assertEquals(AriaEmotion.AFFECTIONATE, AriaEmotion.fromReply("Cuídate, cariño 💜"))
        assertEquals(AriaEmotion.PLAYFUL, AriaEmotion.fromReply("Jeje, te pillé."))
        assertEquals(AriaEmotion.SERIOUS, AriaEmotion.fromReply("Esto es importante, ten cuidado."))
        assertEquals(AriaEmotion.TIRED, AriaEmotion.fromReply("Tengo sueño, estoy cansada."))
    }

    @Test fun portraitRespondsToTheSituationInsteadOfAnUnrelatedHappyWord() {
        assertEquals(AriaEmotion.SAD, AriaEmotion.fromExchange("Estoy triste hoy", ""))
        assertEquals(AriaEmotion.AFFECTIONATE, AriaEmotion.fromExchange("Estoy triste hoy", "Aquí estoy contigo."))
        assertEquals(AriaEmotion.SAD, AriaEmotion.fromExchange("Mi amiga falleció", "Lo siento mucho."))
        assertEquals(AriaEmotion.SERIOUS, AriaEmotion.fromExchange("Tuve un accidente", "Vamos a tomarlo con calma."))
        assertEquals(AriaEmotion.HAPPY, AriaEmotion.fromExchange("Ya terminé", "Genial, quedó listo."))
        assertEquals(AriaEmotion.NEUTRAL, AriaEmotion.fromExchange("Otro tema", "De acuerdo."))
        assertEquals(AriaEmotion.ANNOYED, AriaEmotion.fromExchange("Era broma jaja", "Hmpf, qué pesado."))
        assertEquals(AriaEmotion.NEUTRAL, AriaEmotion.fromReply("De acuerdo."))
        assertEquals(AriaEmotion.HAPPY, AriaEmotion.fromExchange("Lo logré", "Qué bueno."))
    }

    @Test fun ariaWordsCanChangeThePortraitWithinTheSameConversationMood() {
        assertEquals(AriaEmotion.NEUTRAL, AriaEmotion.fromExchange("Estoy cansado", "Entiendo."))
        assertEquals(AriaEmotion.AFFECTIONATE, AriaEmotion.fromExchange("Estoy cansado", "Cuídate 💜"))
        assertEquals(AriaEmotion.PLAYFUL, AriaEmotion.fromExchange("Jaja, era broma", "Jeje, te pillé."))
        assertEquals(AriaEmotion.HAPPY, AriaEmotion.fromExchange("Jaja, era broma", "Me alegra 😊"))
        assertEquals(AriaEmotion.HAPPY, AriaEmotion.fromExchange("Me da vergüenza", "Genial, lo hiciste 😊"))
        assertEquals(AriaEmotion.NEUTRAL, AriaEmotion.fromReply("Lo siento, te escucho."))
    }

    @Test fun spanishSynonymsSelectEveryExpressivePortrait() {
        val examples = mapOf(
            AriaEmotion.HAPPY to listOf("Estoy contenta", "Me siento alegre"),
            AriaEmotion.AMUSED to listOf("Me divierte", "Qué risa"),
            AriaEmotion.THINKING to listOf("Déjame pensar", "Voy a reflexionar"),
            AriaEmotion.SURPRISED to listOf("Estoy asombrada", "Qué sorpresa 😮"),
            AriaEmotion.CONFUSED to listOf("Estoy desconcertada", "No me queda claro"),
            AriaEmotion.ANNOYED to listOf("Me fastidia", "Me irrita"),
            AriaEmotion.ANGRY to listOf("Estoy indignada", "Me da rabia"),
            AriaEmotion.EMBARRASSED to listOf("Estoy sonrojada", "Me siento avergonzada"),
            AriaEmotion.SAD to listOf("Estoy desanimada", "Me siento melancólica"),
            AriaEmotion.AFFECTIONATE to listOf("Me importas", "Te quiero"),
            AriaEmotion.PLAYFUL to listOf("Qué travieso", "Soy bromista"),
            AriaEmotion.SERIOUS to listOf("Es un asunto delicado", "Eso es preocupante"),
            AriaEmotion.TIRED to listOf("Estoy exhausta", "Me siento soñolienta"),
            AriaEmotion.EXCITED to listOf("Estoy entusiasmada", "Qué emoción")
        )
        assertEquals(AriaEmotion.entries.size - 1, examples.size)
        examples.forEach { (emotion, phrases) -> phrases.forEach { phrase ->
            assertEquals(phrase, emotion, AriaEmotion.fromReply(phrase))
        } }
        assertEquals(AriaEmotion.HAPPY, AriaEmotion.fromReply("No estoy triste, estoy contenta"))
        assertEquals(AriaEmotion.NEUTRAL, AriaEmotion.fromReply("No estoy enojada"))
        assertEquals(AriaEmotion.NEUTRAL, AriaEmotion.fromReply("No me siento cansada"))
    }
}
