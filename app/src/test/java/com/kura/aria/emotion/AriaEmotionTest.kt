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
        assertEquals(AriaEmotion.SAD, AriaEmotion.fromExchange("Estoy triste hoy", "Aquí estoy contigo."))
        assertEquals(AriaEmotion.SERIOUS, AriaEmotion.fromExchange("Tuve un accidente", "Vamos a tomarlo con calma."))
        assertEquals(AriaEmotion.HAPPY, AriaEmotion.fromExchange("Ya terminé", "Genial, quedó listo."))
        assertEquals(AriaEmotion.NEUTRAL, AriaEmotion.fromExchange("Otro tema", "De acuerdo."))
        assertEquals(AriaEmotion.NEUTRAL, AriaEmotion.fromReply("De acuerdo."))
        assertEquals(AriaEmotion.HAPPY, AriaEmotion.fromExchange("Lo logré", "Qué bueno."))
    }
}
