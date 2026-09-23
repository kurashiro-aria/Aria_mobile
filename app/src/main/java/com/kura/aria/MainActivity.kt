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
import com.kura.aria.chat.ChatHistory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.RandomAccessFile

class MainActivity : AppCompatActivity() {
    private lateinit var conversation: LinearLayout
    private lateinit var status: TextView
    private lateinit var loadBrain: Button
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var engine: InferenceEngine
    private lateinit var chatHistory: ChatHistory
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var modelLoaded = false
    private var busy = false

    companion object {
        private const val PICK_GGUF = 1001
        private const val PREFS = "aria_runtime"
        private const val LAST_MODEL = "last_model"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 40, 32, 32) }
        val title = TextView(this).apply { text = "ARIA"; textSize = 30f; gravity = Gravity.CENTER }
        val subtitle = TextView(this).apply { text = "ARIA ${BuildConfig.VERSION_NAME} • IA local"; textSize = 14f; gravity = Gravity.CENTER }
        status = TextView(this).apply { text = "Inicializando ARIA…"; textSize = 14f; gravity = Gravity.CENTER; setPadding(0, 12, 0, 12) }
        loadBrain = Button(this).apply {
            text = "CARGAR CEREBRO 🧠"
            isEnabled = false
            setOnClickListener {
                if (!busy && savedModel() != null && !modelLoaded) {
                    uiScope.launch { restoreBrainIfNeeded() }
                } else chooseModel()
            }
        }
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

        chatHistory = ChatHistory(applicationContext)
        val savedMessages = chatHistory.readAll()
        if (savedMessages.isEmpty()) aria(AriaPersonality.welcome)
        else savedMessages.forEach { addMessage(it.role, it.text) }
        send.setOnClickListener { sendMessage() }
        try {
            engine = AiChat.getInferenceEngine(applicationContext)
            uiScope.launch { restoreBrainIfNeeded() }
        } catch (e: LinkageError) {
            status.text = "Biblioteca nativa incompatible: ${e.message}"
            loadBrain.isEnabled = false
        }
    }

    private suspend fun restoreBrainIfNeeded() {
        if (busy) return
        busy = true
        loadBrain.isEnabled = false
        send.isEnabled = false
        try {
            val state = withTimeout(30_000) {
                engine.state.first {
                    it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing
                }
            }
            if (state is InferenceEngine.State.ModelReady) {
                modelLoaded = true
                status.text = "Cerebro local: LISTO 🧠 • sesión activa"
            } else {
                val model = savedModel()
                if (model == null) {
                    modelLoaded = false
                    status.text = "Cerebro local: no cargado"
                } else {
                    if (state is InferenceEngine.State.Error) {
                        status.text = "Reiniciando motor local…"
                        withContext(Dispatchers.IO) { engine.cleanUp() }
                    }
                    check(engine.state.value is InferenceEngine.State.Initialized) {
                        "Motor en estado ${engine.state.value.javaClass.simpleName}; reinicia ARIA."
                    }
                    status.text = "Reconectando cerebro local…"
                    engine.loadModel(model.absolutePath)
                    engine.setSystemPrompt(AriaPersonality.promptWithRecentConversation(chatHistory.readAll()))
                    modelLoaded = true
                    status.text = "Cerebro local: LISTO 🧠 • restaurado"
                    if (chatHistory.readAll().isEmpty()) aria(AriaPersonality.restored)
                }
            }
        } catch (e: TimeoutCancellationException) {
            status.text = "El motor no terminó de iniciar; reinicia ARIA."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            modelLoaded = false
            status.text = "No pude restaurar el cerebro: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            busy = false
            loadBrain.isEnabled = ::engine.isInitialized
            loadBrain.text = if (savedModel() != null && !modelLoaded) "RECONECTAR CEREBRO 🧠"
                else if (modelLoaded) "CAMBIAR CEREBRO 🧠" else "CARGAR CEREBRO 🧠"
            send.isEnabled = modelLoaded && engine.state.value is InferenceEngine.State.ModelReady
        }
    }

    private fun savedModel(): File? {
        val name = getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_MODEL, null) ?: return null
        if (name.contains('/') || name.contains('\\')) return null
        val file = File(File(filesDir, "models"), name)
        return file.takeIf { it.isFile && it.canRead() }
    }

    private fun rememberModel(model: File) {
        check(getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(LAST_MODEL, model.name).commit()) { "No pude guardar el cerebro seleccionado." }
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
                if (target.exists()) check(target.delete()) { "No pude reemplazar el modelo anterior." }
                check(partial.renameTo(target)) { "No pude guardar el modelo importado." }
                target
            }
            val ggufInfo = withContext(Dispatchers.IO) { inspectGguf(model) }
            status.text = "Cabecera GGUF reconocida • ${ggufInfo.detail}\nCargando modelo y verificando tensores…"
            rememberModel(model)
            engine.loadModel(model.absolutePath)
            engine.setSystemPrompt(AriaPersonality.promptWithRecentConversation(chatHistory.readAll()))
            modelLoaded = true
            status.text = "Cerebro local: LISTO 🧠"
            if (chatHistory.readAll().isEmpty()) aria(AriaPersonality.ready)
        } catch (e: TimeoutCancellationException) {
            status.text = "El motor no respondió a tiempo; reinicia ARIA."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            modelLoaded = false
            val engineState = engine.state.value
            val detail = e.message?.takeIf { it.isNotBlank() } ?: "(sin mensaje)"
            status.text = "Error: ${e.javaClass.simpleName}\n${detail.take(240)}\nEstado motor: ${engineState.javaClass.simpleName}"
            aria("Mi trasplante falló: ${e.javaClass.simpleName}: $detail. Estado del motor: ${engineState.javaClass.simpleName}.")
        } finally {
            temporary?.delete()
            busy = false
            loadBrain.isEnabled = true
            loadBrain.text = if (savedModel() != null && !modelLoaded) "RECONECTAR CEREBRO 🧠"
                else if (modelLoaded) "CAMBIAR CEREBRO 🧠" else "CARGAR CEREBRO 🧠"
            send.isEnabled = modelLoaded && engine.state.value is InferenceEngine.State.ModelReady
        }
    }

    private fun sendMessage() {
        val message = input.text.toString().trim()
        if (message.isEmpty() || !modelLoaded || busy) return
        busy = true
        loadBrain.isEnabled = false
        user(message)
        uiScope.launch(Dispatchers.IO) { chatHistory.append("Kura", message) }
        input.text.clear(); send.isEnabled = false
        val reply = TextView(this).apply { text = "ARIA: Preparando respuesta…"; textSize = 17f; setPadding(8, 18, 8, 18) }
        conversation.addView(reply)
        uiScope.launch {
            try {
                val filter = VisibleReplyFilter()
                engine.sendUserPrompt(message, predictLength = 1024)
                    .flowOn(Dispatchers.IO).collect { token ->
                        val answer = filter.append(token)
                        if (answer.isNotBlank()) reply.text = "ARIA: $answer"
                    }
                val answer = filter.finish()
                if (answer.isNotBlank()) {
                    reply.text = "ARIA: $answer"
                    withContext(Dispatchers.IO) { chatHistory.append("ARIA", answer) }
                } else {
                    reply.text = "ARIA: No llegué a completar una respuesta. Prueba con una pregunta más corta."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reply.text = "ARIA: Error al pensar: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
                modelLoaded = engine.state.value is InferenceEngine.State.ModelReady
                send.isEnabled = modelLoaded
                loadBrain.isEnabled = true
                if (!modelLoaded) {
                    status.text = "El motor requiere reconectar el cerebro."
                    loadBrain.text = "RECONECTAR CEREBRO 🧠"
                }
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
                if (magic != "GGUF") GgufInfo(false, "cabecera '$magic', esperada 'GGUF' • $sizeMiB MiB")
                else if (version !in 2..3) GgufInfo(false, "versión GGUF no admitida: $version")
                else {
                    val tensors = java.lang.Long.reverseBytes(raf.readLong())
                    val metadata = java.lang.Long.reverseBytes(raf.readLong())
                    if (tensors <= 0 || metadata <= 0 || tensors > file.length() / 24 || metadata > file.length() / 12)
                        GgufInfo(false, "conteos de tensores/metadatos imposibles o modelo vacío")
                    else GgufInfo(true, "GGUF v$version • $sizeMiB MiB (cabecera; tensores aún sin validar)")
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
