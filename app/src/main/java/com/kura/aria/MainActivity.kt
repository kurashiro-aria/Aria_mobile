package com.kura.aria

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var conversation: LinearLayout
    private lateinit var status: TextView
    private lateinit var loadBrain: Button
    private var selectedModelUri: Uri? = null

    companion object {
        private const val PICK_GGUF = 1001
    }

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
            text = "Mobile Alpha 0.2 • Local AI"
            textSize = 14f
            gravity = Gravity.CENTER
        }

        status = TextView(this).apply {
            text = "Cerebro local: no seleccionado"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }

        loadBrain = Button(this).apply {
            text = "CARGAR CEREBRO 🧠"
            setOnClickListener { chooseModel() }
        }

        conversation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 20)
        }

        val scroll = ScrollView(this).apply { addView(conversation) }
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(this).apply {
            hint = "Habla con ARIA..."
            maxLines = 3
        }
        val send = Button(this).apply { text = "Enviar" }

        inputRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(send)

        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(loadBrain)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(inputRow)
        setContentView(root)

        aria("Hola, Kura. Alpha 0.2 despierta. Todavía estoy esperando mi cerebro local.")

        send.setOnClickListener {
            val message = input.text.toString().trim()
            if (message.isNotEmpty()) {
                user(message)
                input.text.clear()
                aria(
                    if (selectedModelUri == null)
                        "Te escuché. Primero pulsa CARGAR CEREBRO y elige mi archivo GGUF."
                    else
                        "Ya tengo seleccionado mi cerebro. El siguiente paso es conectar el motor de inferencia."
                )
            }
        }
    }

    private fun chooseModel() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/x-gguf", "*/*"))
        }
        startActivityForResult(intent, PICK_GGUF)
    }

    @Deprecated("Deprecated in Android API; retained for minSdk compatibility in this alpha")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_GGUF || resultCode != Activity.RESULT_OK) return

        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some document providers do not offer persistable permissions.
        }

        selectedModelUri = uri
        val name = displayName(uri)
        status.text = "Cerebro seleccionado: $name"
        aria("Bien. Encontré $name. No lo cargaré hasta que el motor local esté conectado.")
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment ?: "modelo.gguf"
    }

    private fun addMessage(who: String, message: String) {
        conversation.addView(TextView(this).apply {
            text = "$who: $message"
            textSize = 17f
            setPadding(8, 18, 8, 18)
        })
    }

    private fun user(message: String) = addMessage("Kura", message)
    private fun aria(message: String) = addMessage("ARIA", message)
}
