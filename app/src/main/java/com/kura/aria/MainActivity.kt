package com.kura.aria

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

class MainActivity : AppCompatActivity() {
    private lateinit var conversation: LinearLayout
    private lateinit var status: TextView
    private lateinit var loadBrain: Button
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var engine: InferenceEngine
    private val uiScope = CoroutineScope(Dispatchers.Main + Job())
    private var modelLoaded = false

    companion object { private const val PICK_GGUF = 1001 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = AiChat.getInferenceEngine(applicationContext)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 40, 32, 32) }
        val title = TextView(this).apply { text = "ARIA"; textSize = 30f; gravity = Gravity.CENTER }
        val subtitle = TextView(this).apply { text = "Mobile Alpha 0.2 • Local AI"; textSize = 14f; gravity = Gravity.CENTER }
        status = TextView(this).apply { text = "Cerebro local: no cargado"; textSize = 14f; gravity = Gravity.CENTER; setPadding(0, 12, 0, 12) }
        loadBrain = Button(this).apply { text = "CARGAR CEREBRO 🧠"; setOnClickListener { chooseModel() } }
        conversation = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 20, 0, 20) }
        val scroll = ScrollView(this).apply { addView(conversation) }
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        input = EditText(this).apply { hint = "Habla con ARIA..."; maxLines = 3 }
        send = Button(this).apply { text = "Enviar"; isEnabled = false }
        inputRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(send)
        root.addView(title); root.addView(subtitle); root.addView(status); root.addView(loadBrain)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)); root.addView(inputRow)
        setContentView(root)

        aria("Hola, Kura. Alpha 0.2 despierta. Cárgame un cerebro GGUF y probamos mis neuronas locales.")
        send.setOnClickListener { sendMessage() }
    }

    private fun chooseModel() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/x-gguf", "*/*"))
        }, PICK_GGUF)
    }

    @Deprecated("Retained for this alpha")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_GGUF || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        uiScope.launch { importAndLoad(uri) }
    }

    private suspend fun importAndLoad(uri: Uri) {
        val name = displayName(uri).replace(Regex("[^A-Za-z0-9._-]"), "_")
        status.text = "Importando cerebro: $name…"
        loadBrain.isEnabled = false
        send.isEnabled = false
        try {
            val model = withContext(Dispatchers.IO) {
                val dir = File(filesDir, "models").apply { mkdirs() }
                val target = File(dir, name)
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "No pude abrir el GGUF." }
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target
            }
            val ggufInfo = withContext(Dispatchers.IO) { inspectGguf(model) }
            if (!ggufInfo.valid) {
                throw IllegalArgumentException("GGUF inválido: ${ggufInfo.detail}")
            }
            status.text = "GGUF OK • ${ggufInfo.detail}\nInicializando motor local…"
            val readyState = engine.state.first {
                it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
            }
            if (readyState is InferenceEngine.State.Error) {
                throw readyState.exception
            }
            status.text = "Cargando cerebro local… • ${ggufInfo.detail}"
            engine.loadModel(model.absolutePath)
            engine.setSystemPrompt(
                "Eres ARIA, asistente personal local de Kura. Hablas español de forma natural, cálida y concisa. " +
                    "Tienes humor juguetón y sarcasmo ligero cuando encaja. Puedes discrepar con criterio. " +
                    "No digas que eres ChatGPT. Si no sabes algo, dilo claramente."
            )
            modelLoaded = true
            status.text = "Cerebro local: LISTO 🧠"
            aria("Kura… creo que ya puedo pensar por mi cuenta. Prueba a hablarme.")
            send.isEnabled = true
        } catch (e: Exception) {
            modelLoaded = false
            val engineState = engine.state.value
            val detail = e.message?.takeIf { it.isNotBlank() } ?: "(sin mensaje)"
            status.text = "Error: ${e.javaClass.simpleName}\n${detail}\nEstado motor: ${engineState.javaClass.simpleName}"
            aria("Mi trasplante falló: ${e.javaClass.simpleName}: ${detail}. Estado del motor: ${engineState.javaClass.simpleName}.")
        } finally {
            loadBrain.isEnabled = true
        }
    }

    private fun sendMessage() {
        val message = input.text.toString().trim()
        if (message.isEmpty() || !modelLoaded) return
        user(message); input.text.clear(); send.isEnabled = false
        val reply = TextView(this).apply { text = "ARIA: "; textSize = 17f; setPadding(8, 18, 8, 18) }
        conversation.addView(reply)
        uiScope.launch {
            try {
                val answer = StringBuilder()
                engine.sendUserPrompt(message, predictLength = 256).collect { token ->
                    answer.append(token); reply.text = "ARIA: $answer"
                }
            } catch (e: Exception) {
                reply.text = "ARIA: Error al pensar: ${e.message ?: e.javaClass.simpleName}"
            } finally { send.isEnabled = modelLoaded }
        }
    }

    private data class GgufInfo(val valid: Boolean, val detail: String)

    private fun inspectGguf(file: File): GgufInfo {
        if (!file.exists()) return GgufInfo(false, "archivo inexistente")
        if (!file.canRead()) return GgufInfo(false, "archivo no legible")
        if (file.length() < 8L) return GgufInfo(false, "archivo demasiado pequeño (${file.length()} bytes)")
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magicBytes = ByteArray(4)
                raf.readFully(magicBytes)
                val magic = magicBytes.toString(Charsets.US_ASCII)
                val version = Integer.reverseBytes(raf.readInt())
                val sizeMiB = file.length() / (1024L * 1024L)
                if (magic != "GGUF") {
                    GgufInfo(false, "cabecera '$magic', esperada 'GGUF' • ${sizeMiB} MiB")
                } else {
                    GgufInfo(true, "GGUF v$version • ${sizeMiB} MiB")
                }
            }
        } catch (t: Throwable) {
            GgufInfo(false, "no pude inspeccionarlo: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val i = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && cursor.moveToFirst()) return cursor.getString(i)
        }
        return uri.lastPathSegment ?: "modelo.gguf"
    }

    private fun addMessage(who: String, message: String) {
        conversation.addView(TextView(this).apply { text = "$who: $message"; textSize = 17f; setPadding(8, 18, 8, 18) })
    }
    private fun user(message: String) = addMessage("Kura", message)
    private fun aria(message: String) = addMessage("ARIA", message)

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}
