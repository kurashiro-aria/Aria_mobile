package com.kura.aria

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.kura.aria.brain.AriaBrainEngine
import com.kura.aria.brain.BrainPipeline
import com.kura.aria.brain.BrainState
import com.kura.aria.brain.mock.MockCloudBrainEngine
import com.kura.aria.chat.ChatHistory
import com.kura.aria.chat.ReplyQuality
import com.kura.aria.chat.VisibleReplyFilter
import com.kura.aria.emotion.AriaEmotion
import com.kura.aria.emotion.MoodReader
import com.kura.aria.memory.AriaMemory
import com.kura.aria.personality.*
import com.kura.aria.voice.AriaVoiceDirector
import com.kura.aria.voice.LocalSpeechOutput
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Stage-2 development launcher. It deliberately proves the full ARIA path without loading a GGUF. */
class CloudMockActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val brain: AriaBrainEngine = MockCloudBrainEngine()
    private lateinit var history: ChatHistory
    private lateinit var memory: AriaMemory
    private lateinit var manager: ConversationManager
    private lateinit var status: TextView
    private lateinit var avatar: ImageView
    private lateinit var conversation: LinearLayout
    private lateinit var input: EditText
    private lateinit var send: Button
    private var speech: LocalSpeechOutput? = null
    private var busy = false
    private var lastRequestPreparationMs: Long? = null
    private var lastGenerationStartMs: Long? = null
    private var lastGenerationTotalMs: Long? = null
    private var lastGenerationState: String = "Disconnected"
    private var lastGenerationError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        history = ChatHistory(applicationContext)
        memory = AriaMemory(applicationContext)
        manager = ConversationManager(applicationContext)
        setContentView(buildUi())
        renderHistory()
        connectMock()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 24, 24, 24)
        setBackgroundColor(Color.parseColor("#100D16"))
        addView(TextView(this@CloudMockActivity).apply {
            text = "ARIA Cloud • Mock"
            textSize = 25f
            setTextColor(Color.WHITE)
        })
        status = TextView(this@CloudMockActivity).apply {
            text = "○ Conectando cerebro Cloud de prueba"
            setTextColor(Color.parseColor("#A970FF"))
        }
        addView(status)
        avatar = ImageView(this@CloudMockActivity).apply {
            setImageResource(R.drawable.aria_neutral)
            contentDescription = "ARIA"
            adjustViewBounds = true
        }
        addView(avatar, LinearLayout.LayoutParams(-1, 320))
        conversation = LinearLayout(this@CloudMockActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(ScrollView(this@CloudMockActivity).apply { addView(conversation) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val row = LinearLayout(this@CloudMockActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this@CloudMockActivity).apply {
            hint = "Habla con ARIA..."
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        send = Button(this@CloudMockActivity).apply {
            text = "➤"; isEnabled = false; setOnClickListener { sendMessage() }
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(send)
        addView(row)
    }

    private fun connectMock() {
        status.text = "○ Connecting • Mock"
        (brain as MockCloudBrainEngine).connect()
        updateAvailability()
    }

    private fun updateAvailability() {
        val ready = brain.state == BrainState.Ready && !busy
        send.isEnabled = ready
        lastGenerationState = brain.state.javaClass.simpleName
        status.text = when (brain.state) {
            BrainState.Ready -> "● ARIA Cloud • Mock"
            BrainState.Generating -> "● ARIA Cloud • Mock • Pensando"
            BrainState.Connecting -> "○ ARIA Cloud • Mock • Conectando"
            BrainState.Disconnected -> "○ ARIA Cloud • Mock • Desconectada"
            is BrainState.Error -> "○ ARIA Cloud • Mock • Error"
        }
    }

    private fun renderHistory() {
        val messages = history.readAll()
        if (messages.isEmpty()) addMessage("ARIA", AriaPersonality.welcome)
        else messages.forEach { addMessage(it.role, it.text) }
    }

    private fun sendMessage() {
        val message = input.text.toString().trim()
        if (message.isEmpty() || busy || brain.state != BrainState.Ready) return
        busy = true; input.text.clear(); addMessage("Kura", message); updateAvailability()
        val stateBefore = manager.snapshot()
        val preferences = memory.stylePreferences()
        val mood = MoodReader.forTurn(message, stateBefore.topic, stateBefore.socialMood, stateBefore.updatedAt, carriedTurns = stateBefore.socialTurns)
        val expression = ExpressionResolver.forTurn(message, mood, stateBefore.expression, stateBefore.updatedAt, preferences = preferences)
        avatar.setImageResource(drawableFor(AriaEmotion.fromInteraction(mood, expression, message, "")))
        scope.launch {
            var replyView: TextView? = null
            try {
                val previous = withContext(Dispatchers.IO) {
                    val old = history.readAll(); history.append("Kura", message); old
                }
                val turn = withContext(Dispatchers.Default) { ConversationBrain.interpret(previous, message, stateBefore) }
                val requestStarted = SystemClock.elapsedRealtime()
                val prepared = withContext(Dispatchers.IO) {
                    val recentUser = turn.recent.filter { it.role == "Kura" }.map { RoleplayInterpreter.spokenText(it.text) }
                    val relevant = if (turn.intent == ConversationIntent.ROLEPLAY_ACTION) emptyList()
                    else memory.relevantTo(turn.spokenText, recentUser)
                    ConversationContext.turnPrompt(previous, relevant, message, stateBefore, preferences, turn)
                }
                val request = BrainPipeline.request(prepared, if (message.length > 280) 512 else 384)
                lastRequestPreparationMs = SystemClock.elapsedRealtime() - requestStarted
                replyView = addMessage("ARIA", "Preparando respuesta…")
                val generationStarted = SystemClock.elapsedRealtime()
                lastGenerationStartMs = generationStarted - requestStarted
                val filter = VisibleReplyFilter()
                brain.generate(request).collect { chunk ->
                    val visible = filter.append(chunk)
                    if (visible.isNotBlank()) replyView.text = visible
                }
                lastGenerationTotalMs = SystemClock.elapsedRealtime() - generationStarted
                var answer = filter.finish()
                val previousAria = previous.lastOrNull { it.role == "ARIA" }?.text
                if (ReplyQuality.needsRetry(message, previousAria, answer)) answer = ReplyQuality.fallback(message)
                if (answer.isBlank()) answer = "No llegué a completar una respuesta. Prueba nuevamente."
                replyView.text = answer
                val emotion = AriaEmotion.fromInteraction(mood, expression, message, answer)
                avatar.setImageResource(drawableFor(emotion))
                withContext(Dispatchers.IO) {
                    history.append("ARIA", answer)
                    manager.record(message, answer, preferences)
                }
                if (getSharedPreferences("aria_runtime", MODE_PRIVATE).getBoolean("voice_enabled", false)) {
                    if (speech == null) speech = LocalSpeechOutput(applicationContext) {}
                    speech?.speak(answer, AriaVoiceDirector.forEmotion(emotion))
                }
                lastGenerationError = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                lastGenerationError = t.javaClass.simpleName
                replyView?.text = "Error del cerebro Cloud de prueba: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                busy = false
                lastGenerationState = brain.state.javaClass.simpleName
                updateAvailability()
            }
        }
    }

    private fun addMessage(role: String, text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 16f; setTextColor(Color.WHITE); setPadding(12, 10, 12, 10)
        gravity = if (role == "Kura") Gravity.END else Gravity.START
        conversation.addView(this)
    }

    private fun drawableFor(emotion: AriaEmotion): Int = when (emotion) {
        AriaEmotion.NEUTRAL -> R.drawable.aria_neutral
        AriaEmotion.HAPPY -> R.drawable.aria_happy
        AriaEmotion.AMUSED -> R.drawable.aria_amused
        AriaEmotion.THINKING -> R.drawable.aria_curious
        AriaEmotion.SURPRISED -> R.drawable.aria_surprised
        AriaEmotion.CONFUSED -> R.drawable.aria_confused
        AriaEmotion.ANNOYED -> R.drawable.aria_annoyed
        AriaEmotion.ANGRY -> R.drawable.aria_angry
        AriaEmotion.EMBARRASSED -> R.drawable.aria_embarrassed
        AriaEmotion.SAD -> R.drawable.aria_sad
        AriaEmotion.AFFECTIONATE -> R.drawable.aria_affectionate
        AriaEmotion.PLAYFUL -> R.drawable.aria_playful
        AriaEmotion.SERIOUS -> R.drawable.aria_serious
        AriaEmotion.TIRED -> R.drawable.aria_tired
        AriaEmotion.EXCITED -> R.drawable.aria_excited
    }

    override fun onDestroy() {
        speech?.close(); speech = null
        scope.cancel()
        runBlocking { brain.close() }
        super.onDestroy()
    }
}
