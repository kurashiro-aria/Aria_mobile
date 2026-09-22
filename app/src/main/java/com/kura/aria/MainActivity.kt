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
import com.kura.aria.personality.AriaPersonality
import com.kura.aria.chat.VisibleReplyFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
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
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var modelLoaded = false
    private var busy = false

    companion object { private const val PICK_GGUF = 1001 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 40, 32, 32) }
        val title = TextView(this).apply { text = "ARIA"; textSize = 30f; gravity = Gravity.CENTER }
        val subtitle = TextView(this).apply { text = "Mobile Alpha 0.2.2 • IA local"; textSize = 14f; gravity = Gravity.CENTER }
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

        aria(AriaPersonality.welcome)
        send.setOnClickListener { sendMessage() }
        try {
            engine = AiChat.getInferenceEngine(applicationContext)
            uiScope.launch {
                // The engine is a process singleton: an Activity may be recreated while it is ready.
                try {
                    val state = withTimeout(30_000) {
                        engine.state.first {
                            it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing
                        }
                    }
                    modelLoaded = state is InferenceEngine.State.ModelReady
                    send.isEnabled = modelLoaded && !busy
                    if (modelLoaded) status.text = "Cerebro local: LISTO 🧠 • sesión activa"
                    if (state is InferenceEngine.State.Error) status.text = "Error del motor: ${state.exception.message}"
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    status.text = "El motor no terminó de iniciar; reinicia ARIA."
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    status.text = "No se pudo iniciar el motor: ${e.message}"
                }
            }
        } catch (e: LinkageError) {
            status.text = "Biblioteca nativa incompatible: ${e.message}"
            loadBrain.isEnabled = false
        }
    }

    private fun chooseModel() {
        if (busy || !::engine.isInitialized) return
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
        if (busy) return
        busy = true
        modelLoaded = false
        loadBrain.isEnabled = false
        send.isEnabled = false
        var temporary: File? = null
        try {
            val name = displayName(uri).replace(Regex("[^A-Za-z0-9._-]"), "_")
                .take(120).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "modelo.gguf"
            status.text = "Preparando importación: $name…"
            withTimeout(30_000) {
                engine.state.first {
                    it is InferenceEngine.State.Initialized || it is InferenceEngine.State.ModelReady ||
                        it is InferenceEngine.State.Error
                }
            }
            // Unload before replacing a file which may still be memory-mapped by llama.cpp.
            if (engine.state.value !is InferenceEngine.State.Initialized) {
                withContext(Dispatchers.IO) { engine.cleanUp() }
            }
            status.text = "Importando cerebro: $name…"
            val model = withContext(Dispatchers.IO) {
                val dir = File(filesDir, "models").apply { check(isDirectory || mkdirs()) }
                val partial = File.createTempFile("import-", ".part", dir)
                temporary = partial
                contentResolver.openInputStream(uri).use { source ->
                    requireNotNull(source) { "No pude abrir el GGUF." }
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val info = inspectGguf(partial)
                require(info.valid) { "GGUF inválido: ${info.detail}" }
                val target = File(dir, name)
                check(partial.renameTo(target)) { "No pude guardar el modelo importado." }
                target
            }
            val ggufInfo = withContext(Dispatchers.IO) { inspectGguf(model) }
            status.text = "Cabecera GGUF reconocida • ${ggufInfo.detail}\nCargando modelo y verificando tensores…"
            engine.loadModel(model.absolutePath)
            engine.setSystemPrompt(AriaPersonality.systemPrompt)
            modelLoaded = true
            status.text = "Cerebro local: LISTO 🧠"
            aria(AriaPersonality.ready)
            send.isEnabled = true
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            status.text = "El motor no respondió a tiempo; reinicia ARIA."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            modelLoaded = false
            val engineState = engine.state.value
            val detail = e.message?.takeIf { it.isNotBlank() } ?: "(sin mensaje)"
            status.text = "Error: ${e.javaClass.simpleName}\n${detail.take(240)}\nEstado motor: ${engineState.javaClass.simpleName}"
            aria("Mi trasplante falló: ${e.javaClass.simpleName}: ${detail}. Estado del motor: ${engineState.javaClass.simpleName}.")
        } finally {
            temporary?.delete()
            busy = false
            loadBrain.isEnabled = true
            send.isEnabled = modelLoaded && engine.state.value is InferenceEngine.State.ModelReady
        }
    }

    private fun sendMessage() {
        val message = input.text.toString().trim()
        if (message.isEmpty() || !modelLoaded || busy) return
        busy = true
        loadBrain.isEnabled = false
        user(message); input.text.clear(); send.isEnabled = false
        val reply = TextView(this).apply { text = "ARIA: Preparando respuesta…"; textSize = 17f; setPadding(8, 18, 8, 18) }
        conversation.addView(reply)
        uiScope.launch {
            try {
                val filter = VisibleReplyFilter()
                engine.sendUserPrompt(message, predictLength = 1024).collect { token ->
                    val answer = filter.append(token)
                    if (answer.isNotBlank()) reply.text = "ARIA: $answer"
                }
                val answer = filter.finish()
                reply.text = if (answer.isNotBlank()) "ARIA: $answer"
                    else "ARIA: No llegué a completar una respuesta. Prueba con una pregunta más corta."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reply.text = "ARIA: Error al pensar: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
                modelLoaded = engine.state.value is InferenceEngine.State.ModelReady
                send.isEnabled = modelLoaded
                loadBrain.isEnabled = true
                if (!modelLoaded) status.text = "El motor requiere recargar el cerebro."
            }
        }
    }

    private data class GgufInfo(val valid: Boolean, val detail: String)

    private fun inspectGguf(file: File): GgufInfo {
        if (!file.exists()) return GgufInfo(false, "archivo inexistente")
        if (!file.canRead()) return GgufInfo(false, "archivo no legible")
        if (file.length() < 24L) return GgufInfo(false, "archivo demasiado pequeño (${file.length()} bytes)")
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magicBytes = ByteArray(4)
                raf.readFully(magicBytes)
                val magic = magicBytes.toString(Charsets.US_ASCII)
                val version = Integer.reverseBytes(raf.readInt())
                val sizeMiB = file.length() / (1024L * 1024L)
                if (magic != "GGUF") {
                    GgufInfo(false, "cabecera '$magic', esperada 'GGUF' • ${sizeMiB} MiB")
                } else if (version !in 2..3) {
                    GgufInfo(false, "versión GGUF no admitida: $version")
                } else {
                    val tensors = java.lang.Long.reverseBytes(raf.readLong())
                    val metadata = java.lang.Long.reverseBytes(raf.readLong())
                    if (tensors <= 0 || metadata <= 0 || tensors > file.length() / 24 || metadata > file.length() / 12) {
                        GgufInfo(false, "conteos de tensores/metadatos imposibles o modelo vacío")
                    } else GgufInfo(true, "GGUF v$version • ${sizeMiB} MiB (cabecera; tensores aún sin validar)")
                }
            }
        } catch (t: Exception) {
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
