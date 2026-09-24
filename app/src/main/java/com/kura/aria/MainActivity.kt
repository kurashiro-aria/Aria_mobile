package com.kura.aria

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.kura.aria.personality.AriaPersonality
import com.kura.aria.personality.ConversationContext
import com.kura.aria.personality.ConversationManager
import com.kura.aria.personality.InitiativePolicy
import com.kura.aria.chat.VisibleReplyFilter
import com.kura.aria.chat.ChatHistory
import com.kura.aria.chat.ReplyQuality
import com.kura.aria.memory.AriaMemory
import com.kura.aria.memory.MemoryCommand
import com.kura.aria.emotion.AriaEmotion
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.RandomAccessFile
import java.util.Calendar

class MainActivity : AppCompatActivity() {
    private lateinit var conversation: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var loadBrain: Button
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var avatarCard: ImageView
    private val portraits = mutableMapOf<Int, Bitmap>()
    private var lastEmotion = AriaEmotion.NEUTRAL
    private var displayedEmotion: AriaEmotion? = null
    private lateinit var engine: InferenceEngine
    private lateinit var chatHistory: ChatHistory
    private lateinit var ariaMemory: AriaMemory
    private lateinit var conversationManager: ConversationManager
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var modelLoaded = false
    private var busy = false
    private var lastLoadMs: Long? = null
    private var lastGeneration: GenerationStats? = null
    private var lastContextChars = 0
    private var lastMemoryCount = 0
    private var repeatedReplies = 0
    private var generationFailures = 0
    private var initiativeJob: Job? = null

    private data class GenerationStats(val firstTokenMs: Long?, val firstVisibleMs: Long?, val totalMs: Long, val chunks: Int) {
        val approximateTokensPerSecond: Double?
            get() = if (firstTokenMs == null || totalMs <= firstTokenMs || chunks < 2) null
                else (chunks - 1) * 1000.0 / (totalMs - firstTokenMs)
    }

    companion object {
        private const val PICK_GGUF = 1001
        private const val PREFS = "aria_runtime"
        private const val LAST_MODEL = "last_model"
        private const val INITIATIVE_ENABLED = "initiative_enabled"
        private const val LAST_INITIATIVE = "last_initiative"
        private const val BG = "#100D16"
        private const val PANEL = "#1A1523"
        private const val PANEL_2 = "#241B31"
        private const val PURPLE = "#A970FF"
        private const val CHAT_PURPLE = "#6F3CC3"
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

        avatarCard = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            background = rounded(PANEL_2, 18f, PURPLE)
            clipToOutline = true
            contentDescription = "ARIA, expresión neutral"
        }
        conversation = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(218), 0, dp(10)) }
        scroll = ScrollView(this).apply { addView(conversation); isFillViewport = true }
        val chatStage = FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(avatarCard, FrameLayout.LayoutParams(dp(150), dp(200), Gravity.TOP or Gravity.START).apply {
                marginStart = dp(4); topMargin = dp(8)
            })
        }
        avatarCard.elevation = dp(10).toFloat()
        root.addView(chatStage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM or Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Habla con ARIA..."; setHintTextColor(Color.parseColor(MUTED)); setTextColor(Color.parseColor(TEXT)); maxLines = 4
            background = rounded(PANEL, 22f, "#3A2A4C"); setPadding(dp(16), dp(11), dp(16), dp(11))
        }
        send = Button(this).apply { text = "➤"; textSize = 20f; isEnabled = false; setTextColor(Color.WHITE); background = rounded(CHAT_PURPLE, 22f) }
        inputRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(send, LinearLayout.LayoutParams(dp(58), dp(52)).apply { marginStart = dp(8) })
        root.addView(inputRow)

        loadBrain = Button(this).apply {
            text = "CARGAR CEREBRO 🧠"; isEnabled = false; visibility = View.GONE
            setOnClickListener { if (!busy && savedModel() != null && !modelLoaded) uiScope.launch { restoreBrainIfNeeded() } else chooseModel() }
        }
        root.addView(loadBrain)
        setContentView(root)
        showPortrait(AriaEmotion.NEUTRAL)

        chatHistory = ChatHistory(applicationContext)
        ariaMemory = AriaMemory(applicationContext)
        conversationManager = ConversationManager(applicationContext)
        val savedMessages = chatHistory.readAll()
        if (savedMessages.isEmpty()) aria(AriaPersonality.welcome) else savedMessages.forEach { addMessage(it.role, it.text) }
        savedMessages.lastOrNull { it.role == "ARIA" }?.let {
            lastEmotion = AriaEmotion.fromReply(it.text)
            showPortrait(lastEmotion)
        }
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
            if (!modelLoaded && savedModel() != null) menu.add("Reconectar cerebro")
            menu.add("Cambiar / cargar cerebro")
            menu.add("Memoria")
            menu.add(if (initiativeEnabled()) "Iniciativa: activada" else "Iniciativa: desactivada")
            menu.add("Rendimiento")
            menu.add("Personalidad")
            menu.add("Voz")
            menu.add("Interfaz")
            menu.add("Sistema • ${BuildConfig.VERSION_NAME}")
            menu.add("Ajustes")
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    "Cambiar / cargar cerebro" -> chooseModel()
                    "Reconectar cerebro" -> uiScope.launch { restoreBrainIfNeeded() }
                    "Memoria" -> showMemoryDialog()
                    "Iniciativa: activada", "Iniciativa: desactivada" -> {
                        val enabled = !initiativeEnabled()
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(INITIATIVE_ENABLED, enabled).apply()
                        toast(if (enabled) "ARIA podrá retomar asuntos pendientes al volver" else "Iniciativa desactivada")
                    }
                    "Rendimiento" -> showPerformanceDialog()
                    "Personalidad" -> toast("ARIA Personality v2 activa")
                    "Voz" -> toast("Voz: próximamente")
                    "Interfaz" -> toast("Interfaz ARIA Character")
                    "Ajustes" -> toast("Ajustes: próximamente")
                    else -> if (it.title?.toString()?.startsWith("Sistema") == true)
                        toast("ARIA ${BuildConfig.VERSION_NAME} • llama.cpp local")
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
                    val loadStarted = SystemClock.elapsedRealtime()
                    engine.loadModel(model.absolutePath)
                    engine.setSystemPrompt(AriaPersonality.systemPrompt())
                    lastLoadMs = SystemClock.elapsedRealtime() - loadStarted
                    if (getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_MODEL, null) == null) rememberModel(model)
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
            if (modelLoaded) scheduleInitiative()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::engine.isInitialized && !busy && savedModel() != null &&
            (!modelLoaded || engine.state.value !is InferenceEngine.State.ModelReady)) {
            uiScope.launch { restoreBrainIfNeeded() }
        } else if (::engine.isInitialized && modelLoaded) {
            scheduleInitiative()
        }
    }

    private fun initiativeEnabled() = getSharedPreferences(PREFS, MODE_PRIVATE)
        .getBoolean(INITIATIVE_ENABLED, false)

    private fun scheduleInitiative() {
        if (!initiativeEnabled()) return
        initiativeJob?.cancel()
        initiativeJob = uiScope.launch {
            delay(3_000)
            if (busy || !modelLoaded || !initiativeEnabled() ||
                !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@launch
            val now = System.currentTimeMillis()
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val suggestion = InitiativePolicy.suggestion(conversationManager.snapshot(), true, now,
                prefs.getLong(LAST_INITIATIVE, 0L), Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) ?: return@launch
            withContext(Dispatchers.IO) { chatHistory.append("ARIA", suggestion) }
            prefs.edit().putLong(LAST_INITIATIVE, now).apply()
            aria(suggestion)
        }
    }

    private fun savedModel(): File? {
        val modelDir = File(filesDir, "models")
        val name = getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_MODEL, null)
            ?: return modelDir.listFiles()?.filter {
                it.isFile && it.canRead() && it.name.endsWith(".gguf", ignoreCase = true) && !it.name.startsWith("backup-")
            }?.singleOrNull()
        if (name.contains('/') || name.contains('\\')) return null
        val file = File(modelDir, name); return file.takeIf { it.isFile && it.canRead() }
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
        var imported: File? = null
        var replaced: File? = null
        var targetName = ""
        var modelCommitted = false
        try {
            val name = displayName(uri).replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "modelo.gguf"
            targetName = name
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
                val target = File(dir, name)
                if (target.exists()) {
                    val backup = File.createTempFile("backup-", ".gguf", dir)
                    check(backup.delete() && target.renameTo(backup)) { "No pude preservar el modelo anterior." }
                    replaced = backup
                }
                check(partial.renameTo(target)) { "No pude guardar el modelo importado." }
                imported = target
                target
            }
            setStatus("○ Cargando cerebro", false)
            val loadStarted = SystemClock.elapsedRealtime()
            engine.loadModel(model.absolutePath)
            engine.setSystemPrompt(AriaPersonality.systemPrompt())
            lastLoadMs = SystemClock.elapsedRealtime() - loadStarted
            rememberModel(model)
            modelCommitted = true
            replaced?.delete()
            replaced = null
            modelLoaded = true; setStatus("● Activa", true)
            if (chatHistory.readAll().isEmpty()) aria(AriaPersonality.ready)
        } catch (e: TimeoutCancellationException) { setStatus("○ Motor sin responder", false) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { modelLoaded = false; setStatus("○ Error de cerebro", false); aria("Mi trasplante falló: ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}.") }
        finally {
            var restoreFailed = false
            if (!modelCommitted && (imported != null || replaced != null)) withContext(NonCancellable + Dispatchers.IO) {
                imported?.delete()
                replaced?.let { backup ->
                    val original = File(File(filesDir, "models"), targetName)
                    restoreFailed = !backup.renameTo(original)
                }
            }
            if (restoreFailed) toast("No pude restaurar el cerebro anterior.")
            temporary?.delete(); busy = false; loadBrain.isEnabled = true
            loadBrain.text = if (savedModel() != null && !modelLoaded) "RECONECTAR CEREBRO 🧠" else if (modelLoaded) "CAMBIAR CEREBRO 🧠" else "CARGAR CEREBRO 🧠"
            send.isEnabled = modelLoaded && engine.state.value is InferenceEngine.State.ModelReady
        }
    }

    private fun sendMessage() {
        val message = input.text.toString().trim(); if (message.isEmpty() || !modelLoaded || busy) return
        AriaMemory.command(message)?.let { command ->
            input.text.clear()
            val answer = try {
                when (command) {
                    is MemoryCommand.Save -> {
                        val memory = ariaMemory.remember(command.text)
                        "Lo guardé como recuerdo #${memory.id}: ${memory.content}"
                    }
                    is MemoryCommand.Delete -> if (ariaMemory.forget(command.id))
                        "Olvidé el recuerdo #${command.id}." else "No encontré el recuerdo #${command.id}."
                    is MemoryCommand.Correct -> ariaMemory.correct(command.id, command.text)
                        ?.let { "Corregí el recuerdo #${it.id}: ${it.content}" }
                        ?: "No encontré el recuerdo #${command.id}."
                    MemoryCommand.ListAll -> ariaMemory.recallAll().takeIf { it.isNotEmpty() }
                        ?.joinToString("\n") { "#${it.id}: ${it.content}" }
                        ?: "Todavía no tengo recuerdos que me hayas pedido guardar. Puedes decirme: «ARIA, recuerda que…»."
                }
            } catch (e: Exception) { e.message ?: "No pude modificar la memoria local." }
            user(message); aria(answer)
            lastEmotion = AriaEmotion.fromReply(answer)
            showPortrait(lastEmotion)
            uiScope.launch(Dispatchers.IO) {
                chatHistory.append("Kura", message)
                chatHistory.append("ARIA", answer)
            }
            return
        }
        busy = true; loadBrain.isEnabled = false; user(message)
        input.text.clear(); send.isEnabled = false; setStatus("● Pensando", true)
        val listeningEmotion = AriaEmotion.fromExchange(message, "")
        if (listeningEmotion != AriaEmotion.NEUTRAL) showPortrait(listeningEmotion)
        else if (message.startsWith("¿") || message.endsWith("?")) showPortrait(AriaEmotion.THINKING)
        val reply = messageView("ARIA", "Preparando respuesta…"); conversation.addView(reply); scrollToBottom()
        uiScope.launch {
            try {
                val previousHistory = withContext(Dispatchers.IO) {
                    val previous = chatHistory.readAll()
                    chatHistory.append("Kura", message)
                    previous
                }
                val modelMessage = withContext(Dispatchers.IO) {
                    val recentUserMessages = previousHistory.filter { it.role == "Kura" }
                        .map { it.text }.takeLast(2)
                    val relevant = ariaMemory.relevantTo(message, recentUserMessages)
                    lastMemoryCount = relevant.size
                    ConversationContext.turnPrompt(previousHistory, relevant, message,
                        conversationManager.snapshot())
                }
                lastContextChars = modelMessage.length
                val previousUser = previousHistory.lastOrNull { it.role == "Kura" }?.text
                val previewExpression: (String) -> Unit = { visible ->
                    showPortrait(AriaEmotion.fromExchange(message, visible, previousUser))
                }
                var answer = collectVisibleReply(AriaPersonality.directResponsePrompt(modelMessage), 768, reply,
                    previewExpression)
                val previousAria = previousHistory.lastOrNull { it.role == "ARIA" }?.text
                if (answer.isBlank() || ReplyQuality.repeats(previousAria, answer)) {
                    if (ReplyQuality.repeats(previousAria, answer)) repeatedReplies++
                    reply.text = "Ajustando respuesta…"
                    showPortrait(AriaEmotion.THINKING)
                    answer = collectVisibleReply(
                        AriaPersonality.directResponsePrompt(ReplyQuality.retryPrompt(previousHistory, message)),
                        256, reply, previewExpression
                    )
                    if (ReplyQuality.repeats(previousAria, answer)) {
                        repeatedReplies++
                        answer = ReplyQuality.fallback(message)
                    }
                }
                if (answer.isNotBlank()) {
                    reply.text = answer
                    lastEmotion = AriaEmotion.fromExchange(message, answer, previousUser)
                    showPortrait(lastEmotion)
                    withContext(Dispatchers.IO) {
                        chatHistory.append("ARIA", answer)
                        conversationManager.record(message, answer)
                    }
                }
                else { generationFailures++; reply.text = "No llegué a completar una respuesta. Prueba con una pregunta más corta."; lastEmotion = AriaEmotion.NEUTRAL; showPortrait(lastEmotion) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { generationFailures++; reply.text = "Error al pensar: ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}"; lastEmotion = AriaEmotion.NEUTRAL; showPortrait(lastEmotion) }
            finally {
                busy = false; modelLoaded = engine.state.value is InferenceEngine.State.ModelReady; send.isEnabled = modelLoaded; loadBrain.isEnabled = true
                setStatus(if (modelLoaded) "● Activa" else "○ Cerebro desconectado", modelLoaded)
                if (!modelLoaded) loadBrain.text = "RECONECTAR CEREBRO 🧠"; scrollToBottom()
            }
        }
    }

    private suspend fun collectVisibleReply(prompt: String, tokenLimit: Int, reply: TextView,
                                            onFirstPhrase: (String) -> Unit = {}): String {
        val filter = VisibleReplyFilter()
        val started = SystemClock.elapsedRealtime()
        var firstTokenMs: Long? = null
        var firstVisibleMs: Long? = null
        var chunks = 0
        var lastRenderedAt = 0L
        var expressionPreviewed = false
        engine.sendUserPrompt(prompt, predictLength = tokenLimit).flowOn(Dispatchers.IO).collect { token ->
            chunks++
            val answer = filter.append(token)
            val now = SystemClock.uptimeMillis()
            if (firstTokenMs == null) firstTokenMs = SystemClock.elapsedRealtime() - started
            if (firstVisibleMs == null && answer.isNotBlank())
                firstVisibleMs = SystemClock.elapsedRealtime() - started
            if (answer.isNotBlank() && (lastRenderedAt == 0L || now - lastRenderedAt >= 80L)) {
                reply.text = answer
                lastRenderedAt = now
            }
            if (!expressionPreviewed && answer.length >= 18 &&
                (answer.length >= 45 || answer.any { it == '.' || it == '!' || it == '?' })) {
                expressionPreviewed = true
                onFirstPhrase(answer)
            }
        }
        lastGeneration = GenerationStats(firstTokenMs, firstVisibleMs, SystemClock.elapsedRealtime() - started, chunks)
        return filter.finish()
    }

    private fun showPerformanceDialog() {
        val generation = lastGeneration
        val details = buildString {
            append("Carga del modelo y personalidad: ")
            append(lastLoadMs?.let { "${it / 1000.0} s" } ?: "sin medir")
            append("\nPrimera palabra visible: ")
            append(generation?.firstVisibleMs?.let { "${it / 1000.0} s" } ?: "sin medir")
            append("\nRespuesta completa: ")
            append(generation?.let { "${it.totalMs / 1000.0} s" } ?: "sin medir")
            append("\nVelocidad aproximada: ")
            append(generation?.approximateTokensPerSecond?.let { String.format(java.util.Locale.US, "%.1f tokens/s", it) }
                ?: "sin medir")
            append("\nCerebro: ").append(savedModel()?.name ?: "sin cargar")
            append("\nContexto del último turno: ").append(lastContextChars).append(" caracteres")
            append("\nRecuerdos recuperados: ").append(lastMemoryCount)
            append("\nRepeticiones detectadas: ").append(repeatedReplies)
            append("\nFallos de respuesta: ").append(generationFailures)
        }
        AlertDialog.Builder(this).setTitle("Rendimiento de ARIA")
            .setMessage(details).setPositiveButton("Cerrar", null).show()
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
        val isKura = who == "Kura"
        text = message; textSize = 16f; setTextColor(Color.parseColor(if (isKura) BG else "#FFFFFF"))
        setPadding(dp(14), dp(11), dp(14), dp(11))
        background = rounded(if (isKura) "#FFFFFF" else CHAT_PURPLE, 18f)
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
    private fun showPortrait(emotion: AriaEmotion) {
        if (displayedEmotion == emotion) return
        val animateChange = displayedEmotion != null
        displayedEmotion = emotion
        val (drawable, label) = when (emotion) {
            AriaEmotion.NEUTRAL -> R.drawable.aria_neutral to emotion.label
            AriaEmotion.HAPPY -> R.drawable.aria_happy to emotion.label
            AriaEmotion.AMUSED -> R.drawable.aria_amused to emotion.label
            AriaEmotion.THINKING -> R.drawable.aria_curious to emotion.label
            AriaEmotion.SURPRISED -> R.drawable.aria_surprised to emotion.label
            AriaEmotion.CONFUSED -> R.drawable.aria_confused to emotion.label
            AriaEmotion.ANNOYED -> R.drawable.aria_annoyed to emotion.label
            AriaEmotion.ANGRY -> R.drawable.aria_angry to emotion.label
            AriaEmotion.EMBARRASSED -> R.drawable.aria_embarrassed to emotion.label
            AriaEmotion.SAD -> R.drawable.aria_sad to emotion.label
            AriaEmotion.AFFECTIONATE -> R.drawable.aria_affectionate to emotion.label
            AriaEmotion.PLAYFUL -> R.drawable.aria_playful to emotion.label
            AriaEmotion.SERIOUS -> R.drawable.aria_serious to emotion.label
            AriaEmotion.TIRED -> R.drawable.aria_tired to emotion.label
            AriaEmotion.EXCITED -> R.drawable.aria_excited to emotion.label
        }
        val portrait = portraits[drawable] ?: BitmapFactory.decodeResource(resources, drawable,
            BitmapFactory.Options().apply { inSampleSize = 2 })?.also { portraits[drawable] = it }
        if (portrait != null) avatarCard.setImageBitmap(portrait) else avatarCard.setImageResource(drawable)
        avatarCard.contentDescription = "ARIA, expresión $label"
        if (animateChange) {
            avatarCard.animate().cancel()
            avatarCard.alpha = 0.65f
            avatarCard.animate().alpha(1f).setDuration(160L).start()
        }
        avatarCard.post {
            val image = avatarCard.drawable ?: return@post
            if (avatarCard.width == 0 || avatarCard.height == 0) return@post
            val width = image.intrinsicWidth.toFloat()
            val height = image.intrinsicHeight.toFloat()
            val scale = maxOf(avatarCard.width / (width * 0.55f), avatarCard.height / (height * 0.55f))
            avatarCard.imageMatrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(avatarCard.width / 2f - width * 0.58f * scale,
                    avatarCard.height / 2f - height * 0.49f * scale)
            }
        }
    }
    private fun showMemoryDialog() {
        val memories = try { ariaMemory.recallAll() } catch (e: Exception) { toast(e.message ?: "Memoria no disponible"); return }
        if (memories.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Memoria de ARIA")
                .setMessage("No hay recuerdos guardados por ti. Escribe «ARIA, recuerda que…» en el chat.")
                .setPositiveButton("Entendido", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Memoria de ARIA")
            .setItems(memories.map { "#${it.id} · ${it.content}" }.toTypedArray()) { _, index ->
                val item = memories[index]
                AlertDialog.Builder(this).setTitle("Olvidar recuerdo #${item.id}")
                    .setMessage(item.content)
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Olvidar") { _, _ ->
                        try { ariaMemory.forget(item.id); toast("Recuerdo eliminado") }
                        catch (e: Exception) { toast(e.message ?: "No pude borrarlo") }
                    }.show()
            }.setNegativeButton("Cerrar", null).show()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(fill: String, radius: Float, stroke: String? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(Color.parseColor(fill)); cornerRadius = dp(radius.toInt()).toFloat()
        if (stroke != null) setStroke(dp(1), Color.parseColor(stroke))
    }

    override fun onDestroy() { uiScope.cancel(); super.onDestroy() }
}
