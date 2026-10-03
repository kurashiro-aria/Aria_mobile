package com.kura.aria

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import android.speech.SpeechRecognizer
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.kura.aria.personality.AriaPersonality
import com.kura.aria.personality.ConversationContext
import com.kura.aria.personality.ConversationBrain
import com.kura.aria.personality.ConversationIntent
import com.kura.aria.personality.ConversationManager
import com.kura.aria.personality.ExpressionResolver
import com.kura.aria.personality.ExpressionStyle
import com.kura.aria.personality.RoleplayInterpreter
import com.kura.aria.personality.InitiativePolicy
import com.kura.aria.chat.VisibleReplyFilter
import com.kura.aria.chat.ChatHistory
import com.kura.aria.chat.ReplyQuality
import com.kura.aria.memory.AriaMemory
import com.kura.aria.memory.MemoryCommand
import com.kura.aria.emotion.AriaEmotion
import com.kura.aria.emotion.MoodReader
import com.kura.aria.voice.AriaVoiceDirector
import com.kura.aria.voice.AndroidVoiceDirector
import com.kura.aria.voice.LocalSpeechOutput
import com.kura.aria.voice.LocalSpeechInput
import com.kura.aria.voice.QwenB2SpeechOutput
import com.kura.aria.voice.AriaVoiceDirector.cloudCandidates
import com.kura.aria.voice.CloudVoiceClient
import com.kura.aria.voice.CloudVoiceException
import com.kura.aria.voice.CloudVoicePlayer
import com.kura.aria.voice.CloudVoiceRequest
import com.kura.aria.voice.HttpCloudVoiceClient
import com.kura.aria.voice.CloudVoiceSessionGate
import com.kura.aria.brain.BrainPipeline
import com.kura.aria.brain.BrainState
import com.kura.aria.brain.CloudContextBuilder
import com.kura.aria.brain.cloud.CloudBrainConfig
import com.kura.aria.brain.cloud.CloudInferenceEngine
import com.kura.aria.brain.cloud.HttpCloudBrainClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.util.Calendar

class MainActivity : AppCompatActivity() {
    private lateinit var conversation: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var networkStatus: TextView
    private lateinit var voiceProgress: TextView
    private lateinit var loadBrain: Button
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var microphone: Button
    private lateinit var avatarCard: ImageView
    private val portraits = mutableMapOf<Int, Bitmap>()
    private var lastEmotion = AriaEmotion.NEUTRAL
    private var lastExpressionStyle = ExpressionStyle.NATURAL
    private var displayedEmotion: AriaEmotion? = null
    private lateinit var engine: InferenceEngine
    private lateinit var cloudBrain: CloudInferenceEngine
    private lateinit var chatHistory: ChatHistory
    private lateinit var ariaMemory: AriaMemory
    private lateinit var conversationManager: ConversationManager
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var modelLoaded = false
    private var busy = false
    private var lastLoadMs: Long? = null
    private var lastGeneration: GenerationStats? = null
    private var lastPreparationMs: Long? = null
    private var lastContextChars = 0
    private var lastMemoryCount = 0
    private var repeatedReplies = 0
    private var generationFailures = 0
    private var initiativeJob: Job? = null
    private var loadingOverlay: FrameLayout? = null
    private var loadingTimer: Job? = null
    private var loadingProgress: ProgressBar? = null
    private var loadingStage: TextView? = null
    private var loadingModelLabel: TextView? = null
    private var wakeButton: Button? = null
    private var speechOutput: LocalSpeechOutput? = null
    private var speechInput: LocalSpeechInput? = null
    private var dictationBase = ""
    private var b2SpeechOutput: QwenB2SpeechOutput? = null
    private var cloudVoiceClient: CloudVoiceClient? = null
    private lateinit var cloudVoicePlayer: CloudVoicePlayer
    private val cloudVoiceMutex = Mutex()
    private val cloudVoiceSession = CloudVoiceSessionGate()
    private var cloudVoiceJob: Job? = null
    private var samplePlayer: MediaPlayer? = null
    private var lastSpokenReply: Pair<String, AriaEmotion>? = null
    private var voiceInForeground = false
    private var pendingWakePermission = false
    private var b2Preparing = false
    private val wakeCommandListener: (String) -> Unit = { command -> runOnUiThread { acceptWakeCommand(command) } }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (pendingWakePermission) { pendingWakePermission = false; startWakeListening() }
            else startDictation()
        } else {
            pendingWakePermission = false
            toast("Necesito permiso del micrófono para escuchar")
        }
    }

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
        private const val VOICE_ENABLED = "voice_enabled"
        private const val VOICE_B2 = "voice_b2_experimental"
        private const val WAKE_ENABLED = "wake_listening_enabled"
        private const val BG = "#100D16"
        private const val PANEL = "#1A1523"
        private const val PANEL_2 = "#241B31"
        private const val PURPLE = "#A970FF"
        private const val CHAT_PURPLE = "#6F3CC3"
        private const val TEXT = "#F5F1FA"
        private const val MUTED = "#AAA0B8"
        // The engine is a process singleton; remember what it actually loaded across Activity recreation.
        private var activeModelName: String? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor(BG)
        window.navigationBarColor = Color.parseColor(BG)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(Color.parseColor(BG))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Leave the avatar's upper-left area visible while keeping the identity readable.
            setPadding(dp(154), 0, 0, 0)
        }
        val identity = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply { text = "ARIA"; textSize = 26f; setTextColor(Color.parseColor(TEXT)) }
        status = TextView(this).apply { text = "● Inicializando"; textSize = 12f; setTextColor(Color.parseColor(MUTED)) }
        networkStatus = TextView(this).apply { text = "○ Offline"; textSize = 11f; setTextColor(Color.parseColor(MUTED)) }
        identity.addView(title); identity.addView(status); identity.addView(networkStatus)
        val menu = TextView(this).apply {
            text = "☰"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(Color.parseColor(TEXT)); setPadding(dp(16), dp(8), 0, dp(8))
            setOnClickListener { showAriaMenu(this) }
        }
        header.addView(identity, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); header.addView(menu)
        root.addView(header)
        voiceProgress = TextView(this).apply {
            textSize = 12f; setTextColor(Color.parseColor(PURPLE)); visibility = View.GONE
            setPadding(0, dp(3), 0, dp(3))
        }
        root.addView(voiceProgress)

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
        }
        avatarCard.elevation = dp(10).toFloat()
        root.addView(chatStage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM or Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Habla con ARIA..."; setHintTextColor(Color.parseColor(MUTED)); setTextColor(Color.parseColor(TEXT)); maxLines = 4
            background = rounded(PANEL, 22f, "#3A2A4C"); setPadding(dp(16), dp(11), dp(16), dp(11))
            setOnFocusChangeListener { _, focused ->
                if (focused) postDelayed({ scrollToBottom() }, 250L)
            }
        }
        send = Button(this).apply { text = "➤"; textSize = 20f; isEnabled = false; setTextColor(Color.WHITE); background = rounded(CHAT_PURPLE, 22f) }
        inputRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        microphone = Button(this).apply {
            text = "🎙"; textSize = 20f; contentDescription = "Dictar mensaje en el dispositivo"
            setTextColor(Color.WHITE); background = rounded(PANEL_2, 22f)
            setOnClickListener { toggleDictation() }
        }
        inputRow.addView(microphone, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(8) })
        inputRow.addView(send, LinearLayout.LayoutParams(dp(58), dp(52)).apply { marginStart = dp(8) })
        root.addView(inputRow)

        loadBrain = Button(this).apply {
            text = "CARGAR CEREBRO 🧠"; isEnabled = false; visibility = View.GONE
            setOnClickListener { connectCloudBrain() }
        }
        root.addView(loadBrain)
        val screen = FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(-1, -1))
            // The portrait floats above the conversation and uses the header's left corner.
            addView(avatarCard, FrameLayout.LayoutParams(dp(150), dp(200), Gravity.TOP or Gravity.START).apply {
                marginStart = dp(4); topMargin = dp(8)
            })
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                val bottomInset = maxOf(bars.bottom, ime.bottom)
                root.setPadding(dp(18), dp(14) + bars.top, dp(18), dp(14) + bottomInset)
                (avatarCard.layoutParams as FrameLayout.LayoutParams).also {
                    it.topMargin = dp(8) + bars.top
                    avatarCard.layoutParams = it
                }
                if (insets.isVisible(WindowInsets.Type.ime())) scrollToBottom()
                insets
            }
            requestApplyInsets()
        }
        setContentView(screen)
        cloudVoicePlayer = CloudVoicePlayer(applicationContext)
        showPortrait(AriaEmotion.NEUTRAL)
        updateNetworkStatus()

        chatHistory = ChatHistory(applicationContext)
        ariaMemory = AriaMemory(applicationContext)
        conversationManager = ConversationManager(applicationContext)
        if (voiceEnabled() && !b2Enabled()) startVoice()
        val savedMessages = chatHistory.readAll()
        if (savedMessages.isEmpty()) aria(AriaPersonality.welcome) else savedMessages.forEach { addMessage(it.role, it.text) }
        savedMessages.lastOrNull { it.role == "ARIA" }?.let {
            lastEmotion = AriaEmotion.fromReply(it.text)
            showPortrait(lastEmotion)
        }
        send.setOnClickListener { sendMessage() }
        AriaForegroundService.commandListener = wakeCommandListener
        connectCloudBrain()
    }

    private fun showAriaMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Estado de ARIA").isEnabled = false
            menu.add(if (modelLoaded) "Cerebro: ARIA Cloud" else "Cerebro Cloud: desconectado").isEnabled = false
            if (!modelLoaded) menu.add("Reconectar ARIA Cloud")
            menu.add("Memoria")
            menu.add(if (initiativeEnabled()) "Iniciativa: activada" else "Iniciativa: desactivada")
            menu.add("Rendimiento")
            menu.add("Personalidad")
            menu.add("Voz")
            menu.add("Voz Cloud experimental")
            menu.add("Escuchar muestra B2")
            menu.add("Repetir última respuesta")
            menu.add("Detener voz")
            menu.add("Interfaz")
            menu.add("Sistema • ${BuildConfig.VERSION_NAME}")
            menu.add("Ajustes")
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    "Reconectar ARIA Cloud" -> connectCloudBrain()
                    "Memoria" -> showMemoryDialog()
                    "Iniciativa: activada", "Iniciativa: desactivada" -> {
                        val enabled = !initiativeEnabled()
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(INITIATIVE_ENABLED, enabled).apply()
                        toast(if (enabled) "ARIA podrá retomar asuntos pendientes al volver" else "Iniciativa desactivada")
                    }
                    "Rendimiento" -> showPerformanceDialog()
                    "Personalidad" -> toast("ARIA Personality v2 activa")
                    "Voz" -> showVoiceDialog()
                    "Voz Cloud experimental" -> showCloudVoicePreviewDialog()
                    "Escuchar muestra B2" -> playB2Sample()
                    "Repetir última respuesta" -> lastSpokenReply?.let { (text, emotion) ->
                        cancelCloudVoicePlayback("replay")
                        if (b2Enabled()) b2SpeechOutput?.speak(text)
                        else {
                            startVoice()
                            speechOutput?.speak(text, AndroidVoiceDirector.forEmotion(emotion, lastExpressionStyle))
                        }
                    } ?: toast("Aún no hay una respuesta nueva para leer")
                    "Detener voz" -> { speechOutput?.stop(); b2SpeechOutput?.stop() }
                    "Interfaz" -> toast("Interfaz ARIA Character")
                    "Ajustes" -> showSettingsDialog()
                    else -> if (it.title?.toString()?.startsWith("Sistema") == true)
                        toast("ARIA ${BuildConfig.VERSION_NAME} • cerebro Cloud")
                }; true
            }
            show()
        }
    }

    private fun setStatus(text: String, ready: Boolean) {
        status.text = text
        status.setTextColor(Color.parseColor(if (ready) PURPLE else MUTED))
        updateNetworkStatus()
    }

    private fun updateNetworkStatus() {
        if (!::networkStatus.isInitialized) return
        val connected = (getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager)
            ?.activeNetwork
            ?.let { network ->
                val capabilities = (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .getNetworkCapabilities(network)
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        networkStatus.text = if (connected) "● Online" else "○ Offline"
        networkStatus.setTextColor(Color.parseColor(if (connected) PURPLE else MUTED))
    }

    private fun connectCloudBrain() {
        if (busy) return
        val endpoint = BuildConfig.ARIA_CLOUD_ENDPOINT.trim()
        if (endpoint.isBlank()) {
            modelLoaded = false
            send.isEnabled = false
            setStatus("○ Cloud no configurado", false)
            return
        }
        if (!::cloudBrain.isInitialized) {
            val config = CloudBrainConfig(endpoint, BuildConfig.ARIA_CLOUD_CLIENT_TOKEN)
            cloudBrain = CloudInferenceEngine(config, HttpCloudBrainClient(config, BuildConfig.DEBUG))
            cloudVoiceClient = HttpCloudVoiceClient(config, BuildConfig.DEBUG)
        }
        busy = true
        send.isEnabled = false
        setStatus("○ Conectando ARIA Cloud", false)
        uiScope.launch {
            modelLoaded = try { cloudBrain.connect() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { false }
            busy = false
            send.isEnabled = modelLoaded
            loadBrain.visibility = if (modelLoaded) View.GONE else View.VISIBLE
            loadBrain.text = "RECONECTAR ARIA CLOUD"
            loadBrain.isEnabled = !modelLoaded
            setStatus(if (modelLoaded) "● Activa" else "○ Cloud desconectada", modelLoaded)
            if (modelLoaded) {
                scheduleInitiative()
                deliverPendingWakeCommand()
            }
        }
    }

    private fun showLoadingScreen(modelName: String? = savedModel()?.name) {
        if (loadingOverlay != null) {
            loadingModelLabel?.text = modelName.orEmpty()
            return
        }
        val screen = findViewById<View>(android.R.id.content) as FrameLayout
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.parseColor(BG)) }
        overlay.addView(ImageView(this).apply {
            setImageResource(R.drawable.aria_sleeping)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "ARIA duerme mientras se carga su cerebro"
        }, FrameLayout.LayoutParams(-1, -1))
        val modelLabel = TextView(this).apply {
            text = modelName.orEmpty()
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.END
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = rounded("#AA15101E", 8f)
        }
        loadingModelLabel = modelLabel
        overlay.addView(modelLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(24)
            marginEnd = dp(16)
            width = resources.displayMetrics.widthPixels / 2
        })
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(30), 0, dp(30), 0)
        }
        controls.addView(TextView(this).apply {
            text = "ARIA está despertando"
            textSize = 27f
            setTextColor(Color.parseColor(TEXT))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })
        loadingProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(PURPLE))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#514060"))
            contentDescription = "Carga del cerebro de ARIA"
        }
        controls.addView(loadingProgress, LinearLayout.LayoutParams(-1, dp(12)))
        loadingStage = TextView(this).apply {
            text = "Preparando el cerebro"
            textSize = 15f
            setTextColor(Color.parseColor(TEXT))
            gravity = Gravity.CENTER
        }
        controls.addView(loadingStage, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        wakeButton = Button(this).apply {
            text = "DESPERTAR"
            textSize = 17f
            setTextColor(Color.WHITE)
            background = rounded(CHAT_PURPLE, 22f)
            visibility = View.GONE
            setOnClickListener { openChatFromLoadingScreen() }
        }
        controls.addView(wakeButton, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(24) })
        overlay.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(112) })
        screen.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        loadingOverlay = overlay
        window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        loadingTimer?.cancel()
        loadingTimer = uiScope.launch {
            while (isActive && loadingOverlay === overlay) {
                val state = if (::engine.isInitialized) engine.state.value else null
                val tensorProgress = if (state is InferenceEngine.State.LoadingModel)
                    engine.modelLoadProgress.coerceIn(0f, 1f) else 0f
                // llama.cpp reports tensor mapping/loading only. Context creation and
                // personality decoding have no measurable total; never assign them a percentage.
                val loadingTensors = state is InferenceEngine.State.LoadingModel && tensorProgress < 1f
                loadingProgress?.isIndeterminate = !loadingTensors
                if (loadingTensors) loadingProgress?.progress = (tensorProgress * 1000).toInt()
                loadingStage?.text = when {
                    loadingTensors -> "Cargando el GGUF"
                    state is InferenceEngine.State.LoadingModel -> "Preparando el contexto"
                    state is InferenceEngine.State.ProcessingSystemPrompt -> "Preparando la personalidad"
                    else -> "Preparando el cerebro"
                }
                delay(200)
            }
        }
    }

    private fun hideLoadingScreen(completed: Boolean) {
        val overlay = loadingOverlay ?: return
        loadingTimer?.cancel()
        loadingTimer = null
        if (completed) {
            loadingProgress?.isIndeterminate = false
            loadingProgress?.progress = 1000
            loadingStage?.text = "ARIA está lista"
            wakeButton?.visibility = View.VISIBLE
            return
        }
        openChatFromLoadingScreen()
    }

    private fun openChatFromLoadingScreen() {
        val overlay = loadingOverlay ?: return
        loadingTimer?.cancel()
        (overlay.parent as? FrameLayout)?.removeView(overlay)
        loadingOverlay = null
        loadingProgress = null
        loadingStage = null
        loadingModelLabel = null
        wakeButton = null
        window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        if (modelLoaded) scheduleInitiative()
    }

    private suspend fun restoreBrainIfNeeded() {
        if (busy) return
        if (savedModel() != null && engine.state.value !is InferenceEngine.State.ModelReady)
            showLoadingScreen()
        busy = true; loadBrain.isEnabled = false; send.isEnabled = false
        try {
            val state = withTimeout(30_000) { engine.state.first { it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing } }
            val model = savedModel()
            if (model == null) {
                modelLoaded = false
                val missing = getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_MODEL, null)
                setStatus(if (missing == null) "○ Sin cerebro" else "○ Cerebro anterior no disponible", false)
                if (missing != null) toast("No encuentro $missing. Elige un cerebro instalado o importa el archivo.")
            } else {
                if (state is InferenceEngine.State.ModelReady && activeModelName == model.name) {
                    modelLoaded = true; setStatus("● Activa · ${model.name}", true)
                } else {
                    if (state is InferenceEngine.State.Error) { setStatus("○ Reiniciando motor", false); withContext(Dispatchers.IO) { engine.cleanUp() } }
                    if (engine.state.value is InferenceEngine.State.ModelReady) withContext(Dispatchers.IO) { engine.cleanUp() }
                    activeModelName = null
                    check(engine.state.value is InferenceEngine.State.Initialized) { "Motor en estado ${engine.state.value.javaClass.simpleName}; reinicia ARIA." }
                    setStatus("○ Reconectando", false)
                    val loadStarted = SystemClock.elapsedRealtime()
                    engine.loadModel(model.absolutePath)
                    engine.setSystemPrompt(AriaPersonality.systemPrompt())
                    lastLoadMs = SystemClock.elapsedRealtime() - loadStarted
                    if (getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_MODEL, null) == null) rememberModel(model)
                    activeModelName = model.name
                    modelLoaded = true; setStatus("● Activa · ${model.name}", true)
                    if (chatHistory.readAll().isEmpty()) aria(AriaPersonality.restored)
                }
            }
        } catch (e: TimeoutCancellationException) { setStatus("○ Motor sin responder", false) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { modelLoaded = false; setStatus("○ Error de cerebro", false); toast(e.message ?: e.javaClass.simpleName) }
        finally {
            hideLoadingScreen(modelLoaded)
            busy = false; loadBrain.isEnabled = ::engine.isInitialized
            loadBrain.text = if (savedModel() != null && !modelLoaded) "RECONECTAR CEREBRO 🧠" else if (modelLoaded) "CAMBIAR CEREBRO 🧠" else "CARGAR CEREBRO 🧠"
            loadBrain.visibility = if (modelLoaded) View.GONE else View.VISIBLE
            send.isEnabled = modelLoaded && engine.state.value is InferenceEngine.State.ModelReady
            if (modelLoaded) uiScope.launch { delay(100); deliverPendingWakeCommand() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (wakeEnabled() && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) startWakeListening()
        if (::cloudBrain.isInitialized && !busy && cloudBrain.state != BrainState.Ready) {
            connectCloudBrain()
        } else if (::cloudBrain.isInitialized && modelLoaded && loadingOverlay == null) {
            scheduleInitiative()
            deliverPendingWakeCommand()
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

    private fun savedModel(): File? = ModelSelection.resolve(File(filesDir, "models"),
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_MODEL, null))

    private fun rememberModel(model: File) {
        check(getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(LAST_MODEL, model.name).commit()) { "No pude guardar el cerebro seleccionado." }
    }

    private fun chooseModel() {
        if (busy || !::engine.isInitialized) { toast("Espera a que ARIA termine de iniciar"); return }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // Providers disagree on the MIME type for .gguf; inspect the file after selection.
        }, PICK_GGUF)
    }

    private fun chooseInstalledModel() {
        if (busy || !::engine.isInitialized) return
        val models = ModelSelection.installed(File(filesDir, "models"))
        if (models.isEmpty()) { toast("No hay cerebros instalados"); return }
        val selected = getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_MODEL, null)
        val labels = models.map { model ->
            "${if (model.name == selected) "✓ " else ""}${model.name} · ${model.length() / (1024 * 1024)} MiB"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Elegir cerebro instalado")
            .setItems(labels) { _, index -> uiScope.launch { loadInstalledModel(models[index]) } }
            .setNegativeButton("Cancelar", null).show()
    }

    private suspend fun loadInstalledModel(model: File) {
        if (busy || !model.isFile || !model.canRead()) return
        if (modelLoaded && activeModelName == model.name && engine.state.value is InferenceEngine.State.ModelReady) return
        busy = true; modelLoaded = false; loadBrain.isEnabled = false; send.isEnabled = false
        try {
            if (engine.state.value !is InferenceEngine.State.Initialized) withContext(Dispatchers.IO) { engine.cleanUp() }
            activeModelName = null
            check(engine.state.value is InferenceEngine.State.Initialized) { "Motor no disponible" }
            showLoadingScreen(model.name)
            setStatus("○ Cargando ${model.name}", false)
            val started = SystemClock.elapsedRealtime()
            engine.loadModel(model.absolutePath)
            engine.setSystemPrompt(AriaPersonality.systemPrompt())
            rememberModel(model)
            lastLoadMs = SystemClock.elapsedRealtime() - started
            activeModelName = model.name
            modelLoaded = true; setStatus("● Activa · ${model.name}", true)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { setStatus("○ Error de cerebro", false); toast(e.message ?: "No se pudo cargar el cerebro") }
        finally {
            hideLoadingScreen(modelLoaded)
            busy = false; loadBrain.isEnabled = true
            loadBrain.text = if (savedModel() != null && !modelLoaded) "RECONECTAR CEREBRO 🧠" else if (modelLoaded) "CAMBIAR CEREBRO 🧠" else "CARGAR CEREBRO 🧠"
            loadBrain.visibility = if (modelLoaded) View.GONE else View.VISIBLE
            send.isEnabled = modelLoaded && engine.state.value is InferenceEngine.State.ModelReady
        }
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
            activeModelName = null
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
            showLoadingScreen(model.name)
            val loadStarted = SystemClock.elapsedRealtime()
            engine.loadModel(model.absolutePath)
            engine.setSystemPrompt(AriaPersonality.systemPrompt())
            lastLoadMs = SystemClock.elapsedRealtime() - loadStarted
            rememberModel(model)
            modelCommitted = true
            replaced?.delete()
            replaced = null
            activeModelName = model.name
            modelLoaded = true; setStatus("● Activa · ${model.name}", true)
            if (chatHistory.readAll().isEmpty()) aria(AriaPersonality.ready)
        } catch (e: TimeoutCancellationException) { setStatus("○ Motor sin responder", false) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { modelLoaded = false; setStatus("○ Error de cerebro", false); aria("Mi trasplante falló: ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}.") }
        finally {
            hideLoadingScreen(modelLoaded)
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
            loadBrain.visibility = if (modelLoaded) View.GONE else View.VISIBLE
            send.isEnabled = modelLoaded && engine.state.value is InferenceEngine.State.ModelReady
        }
    }

    private fun sendMessage() {
        val message = input.text.toString().trim(); if (message.isEmpty() || !modelLoaded || busy) return
        cancelCloudVoicePlayback("new_turn")
        stopDictation()
        if (wakeEnabled()) wakeService(AriaForegroundService.ACTION_WAKE_PAUSE)
        speechOutput?.stop()
        b2SpeechOutput?.stop()
        samplePlayer?.release(); samplePlayer = null
        AriaMemory.command(message)?.let { command ->
            input.text.clear()
            val answer = try {
                when (command) {
                    is MemoryCommand.Save -> {
                        val memory = ariaMemory.remember(command.text, command.categoryHint)
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
            lastSpokenReply = answer to lastEmotion
            lastExpressionStyle = ExpressionStyle.NATURAL
            if (voiceEnabled()) speakReply(answer, lastEmotion, lastExpressionStyle)
            if (wakeEnabled()) wakeService(AriaForegroundService.ACTION_WAKE_RESUME)
            return
        }
        val stateBefore = conversationManager.snapshot()
        val stylePreferences = ariaMemory.stylePreferences()
        val turnMood = MoodReader.forTurn(message, stateBefore.topic,
            stateBefore.socialMood, stateBefore.updatedAt, carriedTurns = stateBefore.socialTurns)
        val turnExpression = ExpressionResolver.forTurn(message, turnMood,
            stateBefore.expression, stateBefore.updatedAt, preferences = stylePreferences)
        busy = true; loadBrain.isEnabled = false; user(message)
        input.text.clear(); send.isEnabled = false; setStatus("● Pensando", true)
        val listeningEmotion = AriaEmotion.fromInteraction(turnMood, turnExpression, message, "")
        if (listeningEmotion != AriaEmotion.NEUTRAL) showPortrait(listeningEmotion)
        else if (message.startsWith("¿") || message.endsWith("?")) showPortrait(AriaEmotion.THINKING)
        val reply = messageView("ARIA", "Preparando respuesta…"); conversation.addView(reply); scrollToBottom()
        uiScope.launch {
            try {
                val preparationStarted = SystemClock.elapsedRealtime()
                val previousHistory = withContext(Dispatchers.IO) {
                    val previous = chatHistory.readAll()
                    chatHistory.append("Kura", message)
                    previous
                }
                val turn = withContext(Dispatchers.Default) {
                    ConversationBrain.interpret(previousHistory, message, stateBefore)
                }
                val modelMessage = withContext(Dispatchers.IO) {
                    val recentUserMessages = turn.recent.filter { it.role == "Kura" }
                        .map { com.kura.aria.personality.RoleplayInterpreter.spokenText(it.text) }
                    val relevant = if (turn.intent == ConversationIntent.ROLEPLAY_ACTION)
                        emptyList() else ariaMemory.relevantTo(turn.spokenText, recentUserMessages)
                    lastMemoryCount = relevant.size
                    CloudContextBuilder.build(previousHistory, relevant, message, stateBefore,
                        stylePreferences, turn)
                }
                lastContextChars = modelMessage.length
                lastPreparationMs = SystemClock.elapsedRealtime() - preparationStarted
                val previewExpression: (String) -> Unit = { visible ->
                    showPortrait(AriaEmotion.fromInteraction(turnMood, turnExpression, message, visible))
                }
                val tokenBudget = if (message.length > 280) 512 else 300
                var answer = collectVisibleReply(modelMessage, tokenBudget, reply, message,
                    previewExpression)
                val previousAria = previousHistory.lastOrNull { it.role == "ARIA" }?.text
                if (ReplyQuality.needsRetry(message, previousAria, answer)) {
                    if (ReplyQuality.repeats(previousAria, answer)) repeatedReplies++
                    reply.text = "Ajustando respuesta…"
                    showPortrait(AriaEmotion.THINKING)
                    answer = collectVisibleReply(
                        CloudContextBuilder.retry(modelMessage,
                            ReplyQuality.retryPrompt(turn.recent, message)),
                        160, reply, message, previewExpression
                    )
                    if (ReplyQuality.needsRetry(message, previousAria, answer)) {
                        repeatedReplies++
                        answer = ReplyQuality.fallback(message)
                    }
                }
                if (answer.isNotBlank()) {
                    reply.text = ariaMessageText(answer)
                    lastEmotion = AriaEmotion.fromInteraction(turnMood, turnExpression, message, answer)
                    showPortrait(lastEmotion)
                    withContext(Dispatchers.IO) {
                        chatHistory.append("ARIA", answer)
                        conversationManager.record(message, answer, stylePreferences)
                        com.kura.aria.memory.LocalMemoryProcessor.process(ariaMemory, message)
                    }
                    lastSpokenReply = answer to lastEmotion
                    lastExpressionStyle = turnExpression.style
                    if (voiceEnabled() && (voiceInForeground || wakeEnabled()))
                        speakReply(answer, lastEmotion, lastExpressionStyle)
                }
                else { generationFailures++; reply.text = "No llegué a completar una respuesta. Prueba con una pregunta más corta."; lastEmotion = AriaEmotion.NEUTRAL; showPortrait(lastEmotion) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                generationFailures++
                reply.text = CloudInferenceEngine.userMessage(e)
                lastEmotion = AriaEmotion.CONFUSED
                showPortrait(lastEmotion)
            }
            finally {
                busy = false; modelLoaded = cloudBrain.state == BrainState.Ready; send.isEnabled = modelLoaded; loadBrain.isEnabled = !modelLoaded
                setStatus(if (modelLoaded) "● Activa" else "○ Cerebro desconectado", modelLoaded)
                if (!modelLoaded) loadBrain.text = "RECONECTAR CEREBRO 🧠"; scrollToBottom()
                if (wakeEnabled()) uiScope.launch {
                    delay(2_000)
                    wakeService(AriaForegroundService.ACTION_WAKE_RESUME)
                }
                uiScope.launch { delay(100); deliverPendingWakeCommand() }
            }
        }
    }

    private suspend fun collectVisibleReply(prompt: String, tokenLimit: Int, reply: TextView, userMessage: String,
                                            onFirstPhrase: (String) -> Unit = {}): String {
        val filter = VisibleReplyFilter()
        val started = SystemClock.elapsedRealtime()
        var firstTokenMs: Long? = null
        var firstVisibleMs: Long? = null
        var chunks = 0
        var lastRenderedAt = 0L
        var expressionPreviewed = false
        cloudBrain.generate(BrainPipeline.request(prompt, tokenLimit)).flowOn(Dispatchers.IO).collect { token ->
            chunks++
            val answer = filter.append(token)
            val copiedAction = ReplyQuality.copiesKuraAction(userMessage, answer)
            if (filter.transcriptDetected || copiedAction) reply.text = "Ajustando respuesta…"
            val now = SystemClock.uptimeMillis()
            if (firstTokenMs == null) firstTokenMs = SystemClock.elapsedRealtime() - started
            if (firstVisibleMs == null && answer.isNotBlank())
                firstVisibleMs = SystemClock.elapsedRealtime() - started
            if (answer.isNotBlank() && !copiedAction && !filter.transcriptDetected &&
                !(RoleplayInterpreter.hasRoleplay(userMessage) && answer.startsWith("*") &&
                    answer.count { it == '*' } < 2) &&
                (lastRenderedAt == 0L || now - lastRenderedAt >= 80L)) {
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
            append("Conexión Cloud: ")
            append(lastLoadMs?.let { "${it / 1000.0} s" } ?: "sin medir")
            append("\nPreparación del turno: ")
            append(lastPreparationMs?.let { "${it / 1000.0} s" } ?: "sin medir")
            append("\nPrimera palabra visible: ")
            append(generation?.firstVisibleMs?.let { "${it / 1000.0} s" } ?: "sin medir")
            append("\nRespuesta completa: ")
            append(generation?.let { "${it.totalMs / 1000.0} s" } ?: "sin medir")
            append("\nVelocidad aproximada: ")
            append(generation?.approximateTokensPerSecond?.let { String.format(java.util.Locale.US, "%.1f tokens/s", it) }
                ?: "sin medir")
            append("\nCerebro activo: ").append(if (modelLoaded) "ARIA Cloud" else "sin conexión")
            append("\nModelo remoto: ").append(if (::cloudBrain.isInitialized) cloudBrain.lastModel ?: "sin informar" else "sin informar")
            append("\nTiempo del servidor: ").append(if (::cloudBrain.isInitialized) cloudBrain.lastServerProcessingMs?.let { "$it ms" } ?: "sin medir" else "sin medir")
            append("\nContexto del último turno: ").append(lastContextChars).append(" caracteres")
            append("\nRecuerdos recuperados: ").append(lastMemoryCount)
            val conversationState = conversationManager.snapshot()
            append("\nEstado social: ").append(conversationState.socialMood.name)
            append("\nEstilo expresivo: ").append(conversationState.expression.style.name)
            append("\nAvatar actual: ").append(lastEmotion.label)
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
        text = if (isKura) message else ariaMessageText(message)
        if (!isKura) movementMethod = LinkMovementMethod.getInstance()
        textSize = 16f; setTextColor(Color.parseColor(if (isKura) BG else "#FFFFFF"))
        setPadding(dp(14), dp(11), dp(14), dp(11))
        background = rounded(if (isKura) "#FFFFFF" else CHAT_PURPLE, 18f)
        gravity = if (who == "Kura") Gravity.END else Gravity.START
        contentDescription = "$who: $message"
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (who == "Kura") Gravity.END else Gravity.START; topMargin = dp(6); bottomMargin = dp(6); if (who == "Kura") marginStart = dp(42) else marginEnd = dp(42)
        }
    }
    private fun ariaMessageText(message: String): SpannableString {
        val label = "🔊  $message"
        return SpannableString(label).apply {
            setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) { replayCloudVoice(message) }
            }, 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun replayCloudVoice(answer: String) {
        val spokenText = com.kura.aria.voice.spokenTextForCloud(answer)
        if (spokenText.isBlank()) {
            toast("Este mensaje no contiene texto hablable")
            return
        }
        val direction = AriaVoiceDirector.forCloudEmotion(AriaEmotion.fromReply(answer))
        val voiceId = "Leda"
        val cacheKey = "v1|$voiceId|${direction.emotion}|${direction.intensity}|${direction.expressionStyle}|$spokenText"
        cancelCloudVoicePlayback("replay")
        cloudVoicePlayer.cached(cacheKey)?.let { cached ->
            runCatching { cloudVoicePlayer.play(cached) }
                .onFailure { toast("No pude reproducir la voz guardada") }
            return
        }
        if (cloudVoiceClient == null || !modelLoaded) {
            toast("Voz no disponible sin conexión")
            return
        }
        val session = cloudVoiceSession.begin()
        cloudVoiceJob = uiScope.launch {
            try { speakCloudVoice(spokenText, direction, voiceId, "replay-${java.util.UUID.randomUUID()}", session) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { toast(cloudVoiceErrorMessage(error)) }
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
            // Keep the 150 x 200 card; show more of each portrait inside it.
            val scale = maxOf(avatarCard.width / (width * 0.65f), avatarCard.height / (height * 0.65f))
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
            .setItems(memories.map { "#${it.id} · ${when (it.category) {
                "experiencia_compartida", "experiencia" -> "Experiencia"
                "preferencia_conversacion" -> "Preferencia de conversación"
                "proyecto" -> "Proyecto"
                else -> "Recuerdo"
            }} · ${it.content}" }.toTypedArray()) { _, index ->
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

    private fun voiceEnabled() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(VOICE_ENABLED, true)
    // B2 permanece congelada. Conservamos la preferencia y el código para una tarea futura.
    private fun b2Enabled() = false
    private fun wakeEnabled() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(WAKE_ENABLED, false)

    private fun wakeService(action: String) {
        val intent = Intent(this, AriaForegroundService::class.java).setAction(action)
        if (action == AriaForegroundService.ACTION_WAKE_ON) ContextCompat.startForegroundService(this, intent)
        else startService(intent)
    }

    private fun showSettingsDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        val wakeSwitch = Switch(this).apply {
            text = "Escucha de micrófono siempre activa"
            isChecked = wakeEnabled()
        }
        content.addView(wakeSwitch)
        content.addView(TextView(this).apply {
            text = "Di «Aria» y después tu mensaje. Muestra una notificación mientras escucha; el teléfono puede limitar sesiones largas."
            setPadding(0, 0, 0, dp(16))
        })
        val speechSwitch = Switch(this).apply {
            text = "Voz activa · leer respuestas automáticamente"
            isChecked = voiceEnabled()
        }
        content.addView(speechSwitch)
        content.addView(TextView(this).apply {
            text = "B2 experimental está pausada. La lectura usa la voz española instalada en Android."
        })
        wakeSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) enableWakeListening()
            else {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(WAKE_ENABLED, false).apply()
                wakeService(AriaForegroundService.ACTION_WAKE_OFF)
            }
        }
        speechSwitch.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(VOICE_ENABLED, checked).apply()
            if (checked && !b2Enabled()) startVoice()
            if (!checked) { speechOutput?.stop(); b2SpeechOutput?.stop() }
        }
        AlertDialog.Builder(this).setTitle("Ajustes de voz").setView(content)
            .setPositiveButton("Listo", null).show()
    }

    private fun enableWakeListening() {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this) &&
            !SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Instala un servicio de reconocimiento de voz en Android")
            return
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            AlertDialog.Builder(this).setTitle("Reconocimiento del sistema")
                .setMessage("Este teléfono no ofrece reconocimiento local. El servicio de voz configurado en Android podría usar internet para procesar el micrófono. ¿Activar la escucha con ese servicio?")
                .setPositiveButton("Activar") { _, _ -> requestWakePermission() }
                .setNegativeButton("Cancelar", null).show()
        } else requestWakePermission()
    }

    private fun requestWakePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            startWakeListening()
        else { pendingWakePermission = true; microphonePermission.launch(Manifest.permission.RECORD_AUDIO) }
    }

    private fun startWakeListening() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(WAKE_ENABLED, true).apply()
        try { wakeService(AriaForegroundService.ACTION_WAKE_ON) }
        catch (e: Exception) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(WAKE_ENABLED, false).apply()
            toast("No pude activar el micrófono: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun acceptWakeCommand(command: String) {
        if (!modelLoaded || busy) {
            AriaForegroundService.pendingCommand = command
            toast("ARIA está ocupada. Tu mensaje queda pendiente")
            return
        }
        input.setText(command)
        input.setSelection(input.text.length)
        sendMessage()
    }

    private fun deliverPendingWakeCommand() {
        if (!modelLoaded || busy) return
        val command = AriaForegroundService.pendingCommand ?: return
        AriaForegroundService.pendingCommand = null
        acceptWakeCommand(command)
    }

    private fun toggleDictation() {
        if (speechInput != null) { stopDictation(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startDictation()
    }

    private fun startDictation() {
        cancelCloudVoicePlayback("dictation")
        if (wakeEnabled()) wakeService(AriaForegroundService.ACTION_WAKE_PAUSE)
        speechOutput?.stop()
        b2SpeechOutput?.stop()
        dictationBase = input.text.toString().trim()
        try {
            val listener = LocalSpeechInput(this,
                onText = { heard ->
                    input.setText(listOf(dictationBase, heard).filter { it.isNotBlank() }.joinToString(" "))
                    input.setSelection(input.text.length)
                },
                onFinished = { error ->
                    stopDictation()
                    if (error != null && !error.startsWith("Falta el modelo español")) toast(error)
                },
                onMissingLanguage = { showSpeechSetupDialog() })
            speechInput = listener
            if (!listener.onDevice) toast("Dictado con el servicio de voz del teléfono")
            microphone.text = "■"
            microphone.contentDescription = "Detener dictado"
            listener.start()
        } catch (e: Exception) {
            stopDictation()
            toast(e.message ?: "No pude iniciar el dictado local")
        }
    }

    private fun stopDictation() {
        val wasListening = speechInput != null
        speechInput?.close()
        speechInput = null
        microphone.text = "🎙"
        microphone.contentDescription = "Dictar mensaje en el dispositivo"
        if (wasListening && wakeEnabled()) wakeService(AriaForegroundService.ACTION_WAKE_RESUME)
    }

    private fun showSpeechSetupDialog() {
        AlertDialog.Builder(this).setTitle("Falta reconocimiento en español")
            .setMessage("El micrófono usa el servicio de reconocimiento del teléfono. ARIA ya tiene permiso, pero ese servicio no tiene español disponible. Puedes pedir la descarga del modelo o instalar español desde los ajustes de voz de Android. La voz B2 se configura por separado.")
            .setPositiveButton("Descargar español") { _, _ ->
                if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                    toast("El servicio local no está disponible; abre Ajustes de voz")
                    return@setPositiveButton
                }
                try {
                    val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                    recognizer.triggerModelDownload(LocalSpeechInput.spanishIntent())
                    toast("Descarga solicitada. Revisa las notificaciones y vuelve a probar el micrófono")
                    uiScope.launch { delay(5_000); recognizer.destroy() }
                } catch (e: Exception) {
                    toast("El servicio no inició la descarga: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            .setNeutralButton("Ajustes de voz") { _, _ ->
                val intent = Intent("android.settings.VOICE_INPUT_SETTINGS")
                if (intent.resolveActivity(packageManager) != null) startActivity(intent)
                else toast("Busca «Reconocimiento de voz sin conexión» en Ajustes de Android")
            }
            .setNegativeButton("Cerrar", null).show()
    }

    private fun startVoice() {
        if (speechOutput == null) speechOutput = LocalSpeechOutput(applicationContext) { status ->
            runOnUiThread { if (!isFinishing && !isDestroyed) toast(status) }
        }
    }

    private fun speakReply(answer: String, emotion: AriaEmotion,
                           expression: ExpressionStyle = ExpressionStyle.NATURAL) {
        cancelCloudVoicePlayback("new_reply")
        speechOutput?.stop()
        val spokenText = com.kura.aria.voice.spokenTextForCloud(answer)
        if (spokenText.isBlank()) return
        startVoice()
        speechOutput?.speak(spokenText, AndroidVoiceDirector.forEmotion(emotion, expression))
    }

    private fun cancelCloudVoicePlayback(reason: String = "replacement") {
        cloudVoiceSession.cancel()
        cloudVoiceJob?.cancel()
        cloudVoiceJob = null
        if (::cloudVoicePlayer.isInitialized) cloudVoicePlayer.stop()
        Log.d("ARIA.CloudVoice", "cancel_reason=$reason")
    }

    private fun ensureCloudVoiceSession(session: Long) {
        if (!cloudVoiceSession.isCurrent(session))
            throw CancellationException("stale_cloud_voice_session")
    }

    /**
     * The provider currently returns complete WAV responses, not an audio
     * stream. Generate natural sentence chunks in order; while one chunk is
     * playing, the producer prepares the next one. Cancellation closes both
     * sides so an old answer cannot continue speaking over a new turn.
     */
    private suspend fun speakCloudVoice(
        spokenText: String,
        direction: com.kura.aria.voice.CloudVoiceDirection,
        voiceId: String,
        requestPrefix: String,
        session: Long
    ) = coroutineScope {
        val client = cloudVoiceClient ?: return@coroutineScope
        val chunks = com.kura.aria.voice.speechChunks(spokenText)
        if (chunks.isEmpty()) return@coroutineScope
        val voiceStarted = SystemClock.elapsedRealtime()
        var firstAudioMs: Long? = null
        var playbackStartedMs: Long? = null
        var queueWaitMs = 0L
        var chunkGenerationMs = 0L
        val files = kotlinx.coroutines.channels.Channel<Triple<File, Long, Long>>(capacity = 1)
        val producer = launch(Dispatchers.IO) {
            try {
                chunks.forEachIndexed { index, chunk ->
                    ensureActive()
                    ensureCloudVoiceSession(session)
                    val cacheKey = "v1|$voiceId|${direction.emotion}|${direction.intensity}|${direction.expressionStyle}|$chunk"
                    val chunkStarted = SystemClock.elapsedRealtime()
                    val cached = cloudVoicePlayer.cached(cacheKey)
                    val file = cached ?: run {
                        val result = synthesizeCloudVoiceWithRetry(client, CloudVoiceRequest(
                            "$requestPrefix-$index", chunk, direction, voiceId))
                        cloudVoicePlayer.cache(cacheKey, result.audio)
                    }
                    val generationMs = if (cached == null) SystemClock.elapsedRealtime() - chunkStarted else 0L
                    chunkGenerationMs += generationMs
                    if (firstAudioMs == null) firstAudioMs = SystemClock.elapsedRealtime() - voiceStarted
                    files.send(Triple(file, SystemClock.elapsedRealtime(), generationMs))
                }
                files.close()
            } catch (cancelled: CancellationException) {
                files.close(cancelled)
                throw cancelled
            } catch (error: Throwable) {
                files.close(error)
                throw error
            }
        }
        val consumer = launch {
            for ((file, queuedAt, _) in files) {
                ensureActive()
                ensureCloudVoiceSession(session)
                queueWaitMs += SystemClock.elapsedRealtime() - queuedAt
                if (playbackStartedMs == null) playbackStartedMs = SystemClock.elapsedRealtime() - voiceStarted
                cloudVoicePlayer.playAndAwait(file)
            }
        }
        try { joinAll(producer, consumer) }
        finally {
            files.cancel()
            if (producer.isActive) producer.cancel()
            if (consumer.isActive) consumer.cancel()
            Log.d("ARIA.CloudVoice", "chunk_count=${chunks.size} first_audio_ms=${firstAudioMs ?: -1} playback_start_ms=${playbackStartedMs ?: -1} queue_wait_ms=$queueWaitMs chunk_generation_ms=$chunkGenerationMs total_ms=${SystemClock.elapsedRealtime() - voiceStarted}")
        }
    }

    private fun cloudVoiceErrorMessage(error: Throwable): String = when (error) {
        is CloudVoiceException.Rejected -> "Voz Cloud rechazó la autenticación; mantengo el texto"
        is CloudVoiceException.NoConnection -> "Voz Cloud sin conexión; mantengo el texto"
        is CloudVoiceException.DnsFailure -> "No se pudo resolver Voz Cloud; mantengo el texto"
        is CloudVoiceException.ConnectFailure -> "No se pudo conectar Voz Cloud; mantengo el texto"
        is CloudVoiceException.TlsFailure -> "Falló la conexión segura de Voz Cloud; mantengo el texto"
        is CloudVoiceException.TransportFailure -> "Falló la descarga de Voz Cloud; mantengo el texto"
        is CloudVoiceException.Timeout -> "Voz Cloud tardó demasiado; mantengo el texto"
        is CloudVoiceException.RateLimited -> if (error.gateway) "Gateway de voz temporalmente limitado; mantengo el texto" else "Proveedor de voz temporalmente limitado; mantengo el texto"
        is CloudVoiceException.HttpError -> "Voz Cloud devolvió HTTP ${error.httpStatus}; mantengo el texto"
        is CloudVoiceException.Unavailable -> "Proveedor de voz Cloud rechazó la generación; mantengo el texto"
        is CloudVoiceException.InvalidAudio -> "Voz Cloud devolvió audio inválido; mantengo el texto"
        else -> "Voz Cloud no disponible; mantengo el texto"
    }

    /** The Worker owns provider retries; Android keeps one logical synthesis request. */
    private suspend fun synthesizeCloudVoiceWithRetry(
        client: CloudVoiceClient,
        request: CloudVoiceRequest
    ): com.kura.aria.voice.CloudVoiceResult {
        return cloudVoiceMutex.withLock { client.synthesize(request) }
    }

    private fun playB2Sample() {
        speechOutput?.stop()
        b2SpeechOutput?.stop()
        samplePlayer?.release()
        samplePlayer = null
        try {
            val player = MediaPlayer()
            samplePlayer = player
            assets.openFd("aria-b2-reference.wav").use { sample ->
                player.setDataSource(sample.fileDescriptor, sample.startOffset, sample.length)
            }
            player.setOnCompletionListener { it.release(); if (samplePlayer === it) samplePlayer = null }
            player.prepare()
            player.start()
        } catch (e: Exception) {
            samplePlayer?.release(); samplePlayer = null
            toast("No pude reproducir la muestra B2: ${e.message ?: "audio no disponible"}")
        }
    }

    private fun showVoiceDialog() {
        AlertDialog.Builder(this).setTitle("Voz local de ARIA")
            .setMessage("B2 experimental está pausada para dedicar los recursos a la conversación. Puedes escuchar la muestra B2 en el menú. La lectura automática usa la voz española de Android.")
            .setPositiveButton("Usar voz Android") { _, _ ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(VOICE_ENABLED, true)
                    .putBoolean(VOICE_B2, false).apply()
                b2SpeechOutput?.close(); b2SpeechOutput = null
                b2Preparing = false
                voiceProgress.visibility = View.GONE
                startVoice()
            }
            .setNegativeButton("Cerrar", null).show()
    }

    private fun showCloudVoicePreviewDialog() {
        val client = cloudVoiceClient
        if (client == null || !modelLoaded) {
            toast("Conecta ARIA Cloud antes de probar voces")
            return
        }
        val phrase = "Hola Kura. Por fin puedo hablar contigo desde la nube. ¿Qué te parece mi voz?"
        val styles = listOf("neutral", "happy", "playful", "affectionate", "surprised", "soft", "whisper")
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(6))
        }
        content.addView(TextView(this).apply {
            text = "Prueba aislada: no activa la voz automática ni B2. Todas las candidatas dirán exactamente la misma frase."
            setPadding(0, 0, 0, dp(12))
        })
        val voiceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                cloudCandidates.map { "${it.id} · ${it.description}" })
        }
        val styleSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, styles)
        }
        content.addView(TextView(this).apply { text = "Voz candidata" })
        content.addView(voiceSpinner)
        content.addView(TextView(this).apply { text = "Interpretación"; setPadding(0, dp(12), 0, 0) })
        content.addView(styleSpinner)
        val metrics = TextView(this).apply { setPadding(0, dp(12), 0, 0); text = phrase }
        content.addView(metrics)
        val testButton = Button(this).apply { text = "PROBAR VOZ CLOUD" }
        content.addView(testButton)
        val dialog = AlertDialog.Builder(this).setTitle("Voz Cloud experimental")
            .setView(content).setNegativeButton("Cerrar", null).create()
        testButton.setOnClickListener {
            val candidate = cloudCandidates[voiceSpinner.selectedItemPosition]
            val style = styles[styleSpinner.selectedItemPosition]
            val direction = AriaVoiceDirector.forPreview(style)
            val cacheKey = "v1|${candidate.id}|$style|$phrase"
            cloudVoicePlayer.cached(cacheKey)?.let { cached ->
                metrics.text = "Reproduciendo desde caché · ${candidate.id} · $style"
                runCatching { cloudVoicePlayer.play(cached) }
                    .onFailure { metrics.text = "No pude reproducir el audio guardado" }
                return@setOnClickListener
            }
            testButton.isEnabled = false
            metrics.text = "Generando ${candidate.id} · $style…"
            uiScope.launch {
                try {
                    val result = client.synthesize(CloudVoiceRequest(
                        "voice-${java.util.UUID.randomUUID()}", phrase, direction, candidate.id))
                    val file = cloudVoicePlayer.cache(cacheKey, result.audio)
                    metrics.text = buildString {
                        append("Lista · ").append(result.voiceId)
                        append("\nAndroid ↔ Worker + audio: ").append(result.totalMs).append(" ms")
                        result.downloadMs?.let { append("\nDescarga audio: ").append(it).append(" ms") }
                        result.audioPrepareMs?.let { append("\nPreparación audio: ").append(it).append(" ms") }
                        result.providerMs?.let { append("\nProveedor TTS: ").append(it).append(" ms") }
                        result.gatewayMs?.let { append("\nTotal Worker: ").append(it).append(" ms") }
                        result.model?.let { append("\nModelo: ").append(it) }
                    }
                    cloudVoicePlayer.play(file)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: CloudVoiceException) { metrics.text = error.message ?: "Voz Cloud no disponible" }
                catch (_: Throwable) { metrics.text = "No pude generar o reproducir esta voz" }
                finally { testButton.isEnabled = true }
            }
        }
        dialog.setOnDismissListener { cloudVoicePlayer.stop() }
        dialog.show()
    }

    private fun prepareB2Voice(replayLastReply: Boolean) {
        // Intentionally inaccessible while B2 is paused (including old saved preferences).
        if (!b2Enabled()) return
        if (b2Preparing) { toast("B2 sigue descargando o cargando; mira el avance sobre el chat"); return }
        val output = b2SpeechOutput ?: QwenB2SpeechOutput(applicationContext, { status ->
            if (!isFinishing && !isDestroyed) {
                voiceProgress.text = status
                voiceProgress.visibility = View.VISIBLE
                if (status.startsWith("Error B2:") || status == "Voz B2 lista para probar") toast(status)
            }
        }, { _ ->
            if (!isFinishing && !isDestroyed && voiceEnabled()) {
                lastSpokenReply?.let { (text, emotion) ->
                    startVoice()
                    speechOutput?.speak(text, AriaVoiceDirector.forEmotion(emotion))
                }
            }
        }).also { b2SpeechOutput = it }
        b2Preparing = true
        if (replayLastReply) toast("Preparando B2. Mantén ARIA abierta durante la descarga inicial.")
        output.prepare { ready ->
            b2Preparing = false
            if (ready && b2SpeechOutput === output) {
                speechOutput?.stop()
                if (replayLastReply) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(VOICE_ENABLED, true)
                        .putBoolean(VOICE_B2, true).apply()
                    if (voiceInForeground) output.speak(lastSpokenReply?.first
                        ?: "Hola, Kura. Soy ARIA y estoy aquí contigo.")
                }
            }
        }
    }

    override fun onStart() { super.onStart(); voiceInForeground = true; updateNetworkStatus() }
    override fun onStop() {
        voiceInForeground = false
        cancelCloudVoicePlayback("lifecycle_stop")
        stopDictation()
        if (!wakeEnabled() || !voiceEnabled()) { speechOutput?.stop(); b2SpeechOutput?.stop() }
        samplePlayer?.release(); samplePlayer = null
        super.onStop()
    }
    override fun onDestroy() {
        if (AriaForegroundService.commandListener === wakeCommandListener)
            AriaForegroundService.commandListener = null
        speechOutput?.close(); speechOutput = null
        b2SpeechOutput?.close(); b2SpeechOutput = null
        if (::cloudVoicePlayer.isInitialized) cloudVoicePlayer.close()
        if (::cloudBrain.isInitialized) runBlocking { cloudBrain.close() }
        uiScope.cancel()
        super.onDestroy()
    }
}
