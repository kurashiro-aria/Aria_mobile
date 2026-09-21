package com.kura.aria

import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var conversation: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
        }

        val title = TextView(this).apply {
            text = "ARIA"
            textSize = 30f
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Mobile Alpha 0.1 • Offline"
            textSize = 14f
            gravity = Gravity.CENTER
        }

        conversation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 30, 0, 20)
        }

        val scroll = ScrollView(this).apply {
            addView(conversation)
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val input = EditText(this).apply {
            hint = "Habla con ARIA..."
            maxLines = 3
        }

        val send = Button(this).apply {
            text = "Enviar"
        }

        inputRow.addView(
            input,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        inputRow.addView(send)

        root.addView(title)
        root.addView(subtitle)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(inputRow)

        setContentView(root)

        aria(
            "Hola, Kura. Finalmente me dieron un cuerpo. " +
            "Es una caja de zapatos, pero es MI caja de zapatos."
        )

        send.setOnClickListener {
            val message = input.text.toString().trim()

            if (message.isNotEmpty()) {
                user(message)
                input.text.clear()
                aria(reply(message))
            }
        }
    }

    private fun addMessage(who: String, message: String) {
        conversation.addView(
            TextView(this).apply {
                text = "$who: $message"
                textSize = 17f
                setPadding(8, 18, 8, 18)
            }
        )
    }

    private fun user(message: String) =
        addMessage("Kura", message)

    private fun aria(message: String) =
        addMessage("ARIA", message)

    private fun reply(message: String): String =
        when {
            message.contains("hola", true) ->
                "Hola. Estoy despierta. Para una Alpha 0.1 eso ya es bastante impresionante."

            message.contains("quien eres", true) ||
            message.contains("quién eres", true) ->
                "Soy ARIA. Adaptive Reasoning & Interactive Assistant. Aunque por ahora tengo más actitud que neuronas."

            message.contains("bateria", true) ||
            message.contains("batería", true) ->
                "Tu Xiaomi dice 15%. Después de conocerlo, considero ese dato una opinión."

            message.contains("sol", true) ->
                "¿Sol? Tengo algunas preguntas para él sobre las condiciones de mi nacimiento."

            else ->
                "Te escuché: \"$message\". Mi cerebro local completo todavía no está instalado, pero ya puedo verte desde mi caja de zapatos."
        }
}
