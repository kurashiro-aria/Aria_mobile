package com.kura.aria

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
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
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var loadBrain: Button
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var avatarCard: TextView
    private lateinit var engine: InferenceEngine
    private lateinit var chatHistory: ChatHistory
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var modelLoaded = false
    private var busy = false

    companion object {
        private const val PICK_GGUF = 1001
        private const val PREFS = "aria_runtime"
        private const val LAST_MODEL = "last_model"
        private const val BG = "#100D16"
        private const val PANEL = "#1A1523"
        private const val PANEL_2 = "#241B31"
        private const val PURPLE = "#A970FF"
        private const val TEXT = "#F5F1FA"
        private const val MUTED = "#AAA0B8"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor(BG)
        window.navigationBarColor = Color.parseColor(BG)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(Color.parseColor(BG))
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val identity = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply { text = "ARIA"; textSize = 26f; setTextColor(Color.parseColor(TEXT)) }
        status = TextView(this).apply { text = "● Inicializando"; textSize = 12f; setTextColor(Color.parseColor(MUTED)) }
        identity.addView(title); identity.addView(status)
        val menu = TextView(this).apply {
            text = "☰"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(Color.parseColor(TEXT)); setPadding(dp(16), dp(8), 0, dp(8))
            setOnClickListener { showAriaMenu(this) }
        }
        header.addView(identity, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); header.addView(menu)
        root.addView(header)

        val stage = FrameLayout(this).apply {
            background = rounded(PANEL, 22f, "#332641")
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val stageHint = TextView(this).apply {
            text = ""; setTextColor(Color.parseColor(MUTED)); textSize = 12f
        }
        avatarCard = TextView(this).apply {
            text = "ARIA\n◇"; gravity = Gravity.CENTER; textSize = 18f; setTextColor(Color.parseColor(TEXT))
            background = rounded(PANEL_2, 18f, PURPLE)
            contentDescription = "Ventana visual de ARIA"
        }
        stage.addView(stageHint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        stage.addView(avatarCard, FrameLayout.LayoutParams(dp(118), dp(92), Gravity.TOP or Gravity.END))
        root.addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(116)).apply { topMargin = dp(12) })

        conversation = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
        scroll = ScrollView(this).apply { addView(conversation); isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM or Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Habla con ARIA..."; setHintTextColor(Color.parseColor(MUTED)); setTextColor(Color.parseColor(TEXT)); maxLines = 4
            background = rounded(PANEL, 22f, "#3A2A4C"); setPadding(dp(16), dp(11), dp(16), dp(11))
        }
        send = Button(this).apply { text = "➤"; textSize = 20f; isEnabled = false; setTextColor(Color.WHITE); background = rounded("#6F3CC3", 22f) }
        inputRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(send, LinearLayout.LayoutParams(dp(58), dp(52)).apply { marginStart = dp(8) })
        root.addView(inputRow)

        loadBrain = Button(this).apply {
            text = "CARGAR CEREBRO 🧠"; isEnabled = false; visibility = View.GONE
            setOnClickListener { if (!busy && savedModel() != null && !modelLoaded) uiScope.launch { restoreBrainIfNeeded() } else chooseModel() }
        }
        root.addView(loadBrain)
        setContentView(root)

        chatHistory = ChatHistory(applicationContext)
        val savedMessages = chatHistory.readAll()
        if (savedMessages.isEmpty()) aria(AriaPersonality.welcome) else savedMessages.forEach { addMessage(it.role, it.text) }
        send.setOnClickListener { sendMessage() }
        try {
            engine = AiChat.getInferenceEngine(applicationContext)
            uiScope.launch { restoreBrainIfNeeded() }
        } catch (e: LinkageError) {
            setStatus("● Error de motor", false)
            loadBrain.isEnabled = false
        }
    }

    private fun showAriaMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Estado de ARIA").isEnabled = false
            menu.add(if (modelLoaded) "Cerebro: conectado" else "Cerebro: desconectado").isEnabled = false
            menu.add("Cambiar / cargar cerebro")
            menu.add("Memoria")
            menu.add("Personalidad")
            menu.add("Voz")
            menu.add("Interfaz")
            menu.add("Sistema • ${BuildConfig.VERSION_NAME}")
            menu.add("Ajustes")
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    "Cambiar / cargar cerebro" -> chooseModel()
                    "Memoria" -> toast("Memoria local activa • ${chatHistory.readAll().size} mensajes")
                    "Personalidad" -> toast("ARIA Personality v1.0 activa")
                    "Voz" -> toast("Voz: próximamente")
                    "Interfaz" -> toast("Interfaz ARIA Character")
                    "Ajustes" -> toast("Ajustes: próximamente")
                    else -> if (it.title.startsWith("Sistema")) toast("ARIA ${BuildConfig.VERSION_NAME} • llama.cpp local")
                }; true
            }
            show()
        }
    }

    private fun setStatus(text: String, ready: Boolean) {
        status.text = text
        status.setTextColor(Color.parseColor(if (ready) PURPLE else MUTED))
    }

    private suspend fun restoreBrainIfNeeded() {
        if (busy) return
        busy = true; loadBrain.isEnabled = false; send.isEnabled = false
        try {
            val state = withTimeout(30_000) { engine.state.first { it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing } }
            if (state is InferenceEngine.State.ModelReady) {
                modelLoaded = true; setStatus("● Activa", true)
            } else {
                val model = savedModel()
                if (model == null) { modelLoaded = false; setStatus("○ Sin cerebro", false) }
                else {
                    if (state is InferenceEngine.State.Error) { setStatus("○ Reiniciando motor", false); withContext(Dispatchers.IO) { engine.cleanUp() } }
                    check(engine.state.value is InferenceEngine.State.Initialized) { "Motor en estado ${engine.state.value.javaClass.simpleName}; reinicia ARIA." }
                    setStatus("○ Reconectando", false)
                    engine.loadModel(model.absolutePath)
                    engine.setSystemPrompt(AriaPersonality.promptWithRecentConversation(chatHistory.readAll()))
                    modelLoaded = true; setStatus("● Activa", true)
                    if (chatHistory.readAll().isEmpty()) aria(AriaPersonality.restored)
                }
            }
        } catch (e: TimeoutCancellationException) { setStatus("○ Motor sin responder", false) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { modelLoaded = false; setStatus("○ Error de cerebro", false); toast(e.message ?: e.javaClass.simpleName) }
        finally {
            busy = false; loadBrain.isEnabled = ::engine.isInitialized
            loadBrain.text = if (savedModel() != null && !modelLoaded) "RECONECTAR CEREBRO 🧠" else if (modelLoaded) "CAMBIAR CEREBRO 🧠" else "CARGAR CEREBRO 🧠"
            send.isEnabled = modelLoaded && engine.state.value is InferenceEngine.State.ModelReady
        }
    }

    private fun savedModel(): File? {
        val name = getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_MODEL, null) ?: return null
        if (name.contains('/') || name.contains('\\')) return null
        val file = File(File(filesDir, "models"), name); return file.takeIf { it.isFile && it.canRead() }
    }

    private fun rememberModel(model: File) {
        check(getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(LAST_MODEL, model.name).commit()) { "No pude guardar el cerebro seleccionado." }
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
        val uri = data?.data ?: return; uiScope.launch { importAndLoad(uri) }
    }

    private suspend fun importAndLoad(uri: Uri) {
        if (busy) return
        busy = true; modelLoaded = false; loadBrain.isEnabled = false; send.isEnabled = false
        var temporary: File? = null
        try {
            val name = displayName(uri).replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "modelo.gguf"
            setStatus("○ Importando cerebro", false)
            withTimeout(30_000) { engine.state.first { it is InferenceEngine.State.Initialized || it is InferenceEngine.State.ModelReady || it is InferenceEngine.State.Error } }
            if (engine.state.value !is InferenceEngine.State.Initialized) withContext(Dispatchers.IO) { engine.cleanUp() }
            val model = withContext(Dispatchers.IO) {
                val dir = File(filesDir, "models").apply { check(isDirectory || mkdirs()) }
                val partial = File.createTempFile("import-", ".part", dir); temporary = partial
                contentResolver.openInputStream(uri).use { source ->
                    requireNotNull(source) { "No pude abrir el GGUF." }
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) { currentCoroutineContext().ensureActive(); val count = source.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
                    }
                }
                val info = inspectGguf(partial); require(info.valid) { "GGUF inválido: ${info.detail}" }
                val target = File(dir, name); if (target.exists()) check(target.delete()) { "No pude reemplazar el modelo anterior." }
                check(partial.renameTo(target)) { "No pude guardar el modelo importado." }; target
            }
            rememberModel(model); setStatus("○ Cargando cerebro", false)
            engine.loadModel(model.absolutePath)
            engine.setSystemPrompt(AriaPersonality.promptWithRecentConversation(chatHistory.readAll()))
            modelLoaded = true; setStatus("● Activa", true)
            if (chatHistory.readAll().isEmpty()) aria(AriaPersonality.ready)
        } catch (e: TimeoutCancellationException) { setStatus("○ Motor sin responder", false) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { modelLoaded = false; setStatus("○ Error de cerebro", false); aria("Mi trasplante falló: ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}.") }
        finally {
            temporary?.delete(); busy = false; loadBrain.isEnabled = true
            loadBrain.text = if (savedModel() != null && !modelLoaded) "RECONECTAR CEREBRO 🧠" else if (modelLoaded) "CAMBIAR CEREBRO 🧠" else "CARGAR CEREBRO 🧠"
            send.isEnabled = modelLoaded && engine.state.value is InferenceEngine.State.ModelReady
        }
    }

    private fun sendMessage() {
        val message = input.text.toString().trim(); if (message.isEmpty() || !modelLoaded || busy) return
        busy = true; loadBrain.isEnabled = false; user(message); uiScope.launch(Dispatchers.IO) { chatHistory.append("Kura", message) }
        input.text.clear(); send.isEnabled = false; setStatus("● Pensando", true); avatarCard.text = "ARIA\n…"
        val reply = messageView("ARIA", "Preparando respuesta…"); conversation.addView(reply); scrollToBottom()
        uiScope.launch {
            try {
                val filter = VisibleReplyFilter()
                engine.sendUserPrompt(message, predictLength = 1024).flowOn(Dispatchers.IO).collect { token ->
                    val answer = filter.append(token); if (answer.isNotBlank()) reply.text = answer
                }
                val answer = filter.finish()
                if (answer.isNotBlank()) { reply.text = answer; withContext(Dispatchers.IO) { chatHistory.append("ARIA", answer) } }
                else reply.text = "No llegué a completar una respuesta. Prueba con una pregunta más corta."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reply.text = "Error al pensar: ${e.message ?: e.javaClass.simpleName}" }
            finally {
                busy = false; modelLoaded = engine.state.value is InferenceEngine.State.ModelReady; send.isEnabled = modelLoaded; loadBrain.isEnabled = true
                avatarCard.text = "ARIA\n◇"; setStatus(if (modelLoaded) "● Activa" else "○ Cerebro desconectado", modelLoaded)
                if (!modelLoaded) loadBrain.text = "RECONECTAR CEREBRO 🧠"; scrollToBottom()
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
                val magicBytes = ByteArray(4); raf.readFully(magicBytes); val magic = magicBytes.toString(Charsets.US_ASCII)
                val version = Integer.reverseBytes(raf.readInt()); val sizeMiB = file.length() / (1024L * 1024L)
                if (magic != "GGUF") GgufInfo(false, "cabecera '$magic', esperada 'GGUF' • $sizeMiB MiB")
                else if (version !in 2..3) GgufInfo(false, "versión GGUF no admitida: $version")
                else { val tensors = java.lang.Long.reverseBytes(raf.readLong()); val metadata = java.lang.Long.reverseBytes(raf.readLong())
                    if (tensors <= 0 || metadata <= 0 || tensors > file.length() / 24 || metadata > file.length() / 12) GgufInfo(false, "conteos imposibles") else GgufInfo(true, "GGUF v$version • $sizeMiB MiB") }
            }
        } catch (t: Exception) { GgufInfo(false, "no pude inspeccionarlo: ${t.message ?: t.javaClass.simpleName}") }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val i = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0 && cursor.moveToFirst()) return cursor.getString(i)
        }; return uri.lastPathSegment ?: "modelo.gguf"
    }

    private fun addMessage(who: String, message: String) { conversation.addView(messageView(who, message)); scrollToBottom() }
    private fun messageView(who: String, message: String) = TextView(this).apply {
        text = message; textSize = 16f; setTextColor(Color.parseColor(TEXT)); setPadding(dp(14), dp(11), dp(14), dp(11))
        background = rounded(if (who == "Kura") "#302047" else PANEL, 18f, if (who == "Kura") "#60408A" else "#30263B")
        gravity = if (who == "Kura") Gravity.END else Gravity.START
        contentDescription = "$who: $message"
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (who == "Kura") Gravity.END else Gravity.START; topMargin = dp(6); bottomMargin = dp(6); if (who == "Kura") marginStart = dp(42) else marginEnd = dp(42)
        }
    }
    private fun user(message: String) = addMessage("Kura", message)
    private fun aria(message: String) = addMessage("ARIA", message)
    private fun scrollToBottom() { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(fill: String, radius: Float, stroke: String? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(Color.parseColor(fill)); cornerRadius = dp(radius.toInt()).toFloat()
        if (stroke != null) setStroke(dp(1), Color.parseColor(stroke))
    }

    override fun onDestroy() { uiScope.cancel(); super.onDestroy() }
}
