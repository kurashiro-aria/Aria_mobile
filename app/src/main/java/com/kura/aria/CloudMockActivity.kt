package com.kura.aria

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowInsets
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.kura.aria.brain.*
import com.kura.aria.brain.cloud.*
import com.kura.aria.brain.mock.MockCloudBrainEngine
import com.kura.aria.chat.*
import com.kura.aria.emotion.*
import com.kura.aria.memory.AriaMemory
import com.kura.aria.personality.*
import com.kura.aria.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.File

/** Stage-3 development launcher. Cloud is selected only when ARIA_CLOUD_ENDPOINT is supplied at build time. */
class CloudMockActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var brain: AriaBrainEngine
    private lateinit var history: ChatHistory; private lateinit var memory: AriaMemory; private lateinit var manager: ConversationManager
    private lateinit var status: TextView; private lateinit var avatar: ImageView; private lateinit var conversation: LinearLayout; private lateinit var input: EditText; private lateinit var send: Button
    private var speech: LocalSpeechOutput? = null; private var busy = false; private var generationJob: Job? = null; private var generationSequence = 0L
    private var lastRequestPreparationMs: Long? = null; private var lastRequestMs: Long? = null
    private var pendingBackupPassphrase: CharArray? = null
    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pass=pendingBackupPassphrase.also{pendingBackupPassphrase=null}?:return@registerForActivityResult
        if(uri!=null) scope.launch(Dispatchers.IO){runCatching{
            val file=com.kura.aria.memory.AriaMemoryBackup(applicationContext,memory,history)
                .export(File(cacheDir,"aria-backup"),pass)
            contentResolver.openOutputStream(uri)?.use{out->file.inputStream().use{it.copyTo(out)}}?:error("No pude abrir el destino")
            file.delete()
        }.onSuccess{withContext(Dispatchers.Main){Toast.makeText(this@CloudMockActivity,"Backup exportado",Toast.LENGTH_LONG).show()}}
            .onFailure{withContext(Dispatchers.Main){Toast.makeText(this@CloudMockActivity,it.message?:"No pude exportar",Toast.LENGTH_LONG).show()}}}
    }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) askPassphrase("Contraseña del backup") { pass -> scope.launch(Dispatchers.IO){runCatching{
            val file=File(cacheDir,"aria-import-${System.currentTimeMillis()}.aria")
            contentResolver.openInputStream(uri)?.use{input->file.outputStream().use{input.copyTo(it)}}?:error("No pude abrir el backup")
            val result=com.kura.aria.memory.AriaMemoryBackup(applicationContext,memory,history).importBackup(file,pass);file.delete();result
        }.onSuccess{result->withContext(Dispatchers.Main){Toast.makeText(this@CloudMockActivity,"Importados ${result.memories} recuerdos y ${result.messages} mensajes",Toast.LENGTH_LONG).show()}}
            .onFailure{withContext(Dispatchers.Main){Toast.makeText(this@CloudMockActivity,it.message?:"Backup inválido",Toast.LENGTH_LONG).show()}}} }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); history = ChatHistory(applicationContext); memory = AriaMemory(applicationContext); manager = ConversationManager(applicationContext)
        setContentView(buildUi()); renderHistory(); configureBrain()
    }

    private fun configureBrain() {
        val endpoint = BuildConfig.ARIA_CLOUD_ENDPOINT.trim()
        val config=CloudBrainConfig(endpoint.ifEmpty { "https://mock.invalid" },BuildConfig.ARIA_CLOUD_CLIENT_TOKEN)
        brain = if (endpoint.isNotEmpty()) CloudInferenceEngine(config, HttpCloudBrainClient(config, BuildConfig.DEBUG)) else MockCloudBrainEngine()
        scope.launch {
            status.text = "○ Conectando ARIA Cloud"
            when (val b = brain) { is MockCloudBrainEngine -> b.connect(); is CloudInferenceEngine -> b.connect() }
            updateAvailability()
        }
    }

    private fun buildUi() = FrameLayout(this).apply {
        setBackgroundColor(Color.parseColor("#100D16"))
        val root = LinearLayout(this@CloudMockActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
            fitsSystemWindows = true
        }
        root.addView(TextView(this@CloudMockActivity).apply {
            text = "ARIA"
            textSize = 26f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(-1, dp(40)))
        status = TextView(this@CloudMockActivity).apply {
            text = "○ Inicializando"
            textSize = 14f
            setTextColor(Color.parseColor("#A970FF"))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(30)))
        root.addView(Button(this@CloudMockActivity).apply {
            text = "DATOS DE ARIA"
            setTextColor(Color.WHITE)
            background = rounded("#5A5860", 8)
            setOnClickListener { showDataDialog() }
        }, LinearLayout.LayoutParams(-1, dp(52)))
        val chatStage = FrameLayout(this@CloudMockActivity)
        conversation = LinearLayout(this@CloudMockActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(190), 0, dp(8))
        }
        val chatScroll = ScrollView(this@CloudMockActivity).apply {
            addView(conversation)
            isFillViewport = true
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(0, 0, 0, insets.getInsets(WindowInsets.Type.ime()).bottom)
                insets
            }
        }
        chatStage.addView(chatScroll, FrameLayout.LayoutParams(-1, -1))
        avatar = ImageView(this@CloudMockActivity).apply {
            setImageResource(R.drawable.aria_neutral)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded("#241B31", 18)
            clipToOutline = true
            elevation = dp(10).toFloat()
            contentDescription = "ARIA"
        }
        chatStage.addView(avatar, FrameLayout.LayoutParams(dp(118), dp(158)).apply {
            gravity = Gravity.TOP or Gravity.START
            marginStart = dp(2)
            topMargin = dp(6)
        })
        root.addView(chatStage, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        val row = LinearLayout(this@CloudMockActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this@CloudMockActivity).apply {
            hint = "Habla con ARIA..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#AAA0B8"))
            background = rounded("#1A1523", 22)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            maxLines = 3
        }
        send = Button(this@CloudMockActivity).apply {
            text = "➤"
            textSize = 20f
            setTextColor(Color.WHITE)
            background = rounded("#6F3CC3", 22)
            isEnabled = false
            setOnClickListener { sendMessage() }
        }
        row.addView(input, LinearLayout.LayoutParams(0, dp(54), 1f))
        row.addView(send, LinearLayout.LayoutParams(dp(58), dp(54)).apply { marginStart = dp(8) })
        root.addView(row, LinearLayout.LayoutParams(-1, dp(60)))
        addView(root, FrameLayout.LayoutParams(-1, -1))
        setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            root.setPadding(dp(18), dp(14) + bars.top, dp(18), dp(10) + bars.bottom)
            insets
        }
    }


    private fun rounded(fill: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(fill))
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun updateAvailability() {
        val ready=::brain.isInitialized && brain.state==BrainState.Ready && !busy; send.isEnabled=ready
        status.text=when (if (::brain.isInitialized) brain.state else BrainState.Disconnected) {
            BrainState.Ready -> if (brain is MockCloudBrainEngine) "● ARIA Cloud • Mock" else "● ARIA Cloud conectada"
            BrainState.Generating -> "ARIA está pensando…"; BrainState.Connecting -> "○ Conectando ARIA Cloud"
            BrainState.Disconnected -> "● Sin conexión"; is BrainState.Error -> "● Sin conexión • ${((brain.state) as BrainState.Error).message}"
        }
    }

    private fun renderHistory(){ val messages=history.readRecent(200); if(messages.isEmpty()) addMessage("ARIA",AriaPersonality.welcome) else messages.forEach{addMessage(it.role,it.text)} }

    private fun sendMessage() {
        val message=input.text.toString().trim(); if(message.isEmpty()||busy||brain.state!=BrainState.Ready)return
        AriaMemory.command(message)?.let { command ->
            val answer = when(command) {
                is com.kura.aria.memory.MemoryCommand.Save -> memory.remember(command.text, command.categoryHint)
                    .let { "Lo guardé como recuerdo #${it.id}: ${it.content}" }
                is com.kura.aria.memory.MemoryCommand.Delete -> if(memory.forget(command.id)) "Olvidé el recuerdo #${command.id}." else "No encontré el recuerdo #${command.id}."
                is com.kura.aria.memory.MemoryCommand.Correct -> memory.correct(command.id,command.text)
                    ?.let { "Corregí el recuerdo #${it.id}: ${it.content}" } ?: "No encontré el recuerdo #${command.id}."
                com.kura.aria.memory.MemoryCommand.ListAll -> "Tengo ${memory.count()} recuerdos locales. Puedes pedirme uno por tema."
            }
            input.text.clear(); addMessage("Kura",message); addMessage("ARIA",answer)
            scope.launch(Dispatchers.IO){history.append("Kura",message);history.append("ARIA",answer)}
            return
        }
        generationJob?.cancel(); val sequence=++generationSequence; busy=true; input.text.clear(); addMessage("Kura",message); updateAvailability()
        val stateBefore=manager.snapshot(); val preferences=memory.stylePreferences(); val mood=MoodReader.forTurn(message,stateBefore.topic,stateBefore.socialMood,stateBefore.updatedAt,carriedTurns=stateBefore.socialTurns)
        val expression=ExpressionResolver.forTurn(message,mood,stateBefore.expression,stateBefore.updatedAt,preferences=preferences)
        avatar.setImageResource(drawableFor(AriaEmotion.fromInteraction(mood,expression,message,"")))
        generationJob=scope.launch {
            var replyView:TextView?=null
            try {
                val previous=withContext(Dispatchers.IO){val old=history.readRecent(40);history.append("Kura",message);old}
                val turn=withContext(Dispatchers.Default){ConversationBrain.interpret(previous,message,stateBefore)}
                val prepStart=SystemClock.elapsedRealtime(); val prepared=withContext(Dispatchers.IO){
                    val recent=turn.recent.filter{it.role=="Kura"}.map{RoleplayInterpreter.spokenText(it.text)}
                    val relevant=if(turn.intent==ConversationIntent.ROLEPLAY_ACTION) emptyList() else memory.relevantTo(turn.spokenText,recent)
                    CloudContextBuilder.build(previous,relevant,message,stateBefore,preferences,turn)
                }
                val request=BrainPipeline.request(prepared,if(message.length>280)512 else 300); lastRequestPreparationMs=SystemClock.elapsedRealtime()-prepStart
                replyView=addMessage("ARIA","Preparando respuesta…"); val requestStart=SystemClock.elapsedRealtime(); val filter=VisibleReplyFilter()
                brain.generate(request).collect{chunk-> if(sequence!=generationSequence)return@collect; val visible=filter.append(chunk);if(visible.isNotBlank())replyView.text=visible}
                if(sequence!=generationSequence)return@launch; lastRequestMs=SystemClock.elapsedRealtime()-requestStart
                var answer=filter.finish(); val previousAria=previous.lastOrNull{it.role=="ARIA"}?.text
                if(ReplyQuality.needsRetry(message,previousAria,answer))answer=ReplyQuality.fallback(message); if(answer.isBlank())answer="No llegué a completar una respuesta. Prueba nuevamente."
                replyView.text=answer; val emotion=AriaEmotion.fromInteraction(mood,expression,message,answer);avatar.setImageResource(drawableFor(emotion))
                withContext(Dispatchers.IO){history.append("ARIA",answer);manager.record(message,answer,preferences);com.kura.aria.memory.LocalMemoryProcessor.process(memory,message)}
                if(getSharedPreferences("aria_runtime",MODE_PRIVATE).getBoolean("voice_enabled",false)){if(speech==null)speech=LocalSpeechOutput(applicationContext){};speech?.speak(answer,AriaVoiceDirector.forEmotion(emotion))}
            } catch(cancelled:CancellationException){ replyView?.let{conversation.removeView(it)}; throw cancelled }
            catch(t:Throwable){ if(sequence==generationSequence) replyView?.text=CloudInferenceEngine.userMessage(t) }
            finally { if(sequence==generationSequence){busy=false;updateAvailability();if(brain.state is BrainState.Error && brain is CloudInferenceEngine) scope.launch{delay(1500);(brain as CloudInferenceEngine).recover();updateAvailability()}} }
        }
    }

    private fun addMessage(role:String,text:String)=TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(if (role == "Kura") Color.parseColor("#100D16") else Color.WHITE)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        gravity = if (role == "Kura") Gravity.END else Gravity.START
        background = rounded(if (role == "Kura") "#FFFFFF" else "#6F3CC3", 16)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
            gravity = if (role == "Kura") Gravity.END else Gravity.START
            topMargin = dp(4); bottomMargin = dp(4)
            marginStart = if (role == "Kura") dp(42) else 0
            marginEnd = if (role == "Kura") 0 else dp(42)
        }
        conversation.addView(this)
    }
    private fun showDataDialog(){
        val size=memory.storageBytes(); val details="Recuerdos: ${memory.count()}\nHistorial: ${history.count()} mensajes\nAlmacenamiento de memoria: ${size/1024} KiB\n\nLos backups se cifran con la contraseña que elijas."
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Datos de ARIA").setMessage(details)
            .setPositiveButton("Exportar memoria"){_,_->askPassphrase("Crear contraseña del backup"){pass->pendingBackupPassphrase=pass;exportBackup.launch("ARIA_MEMORY_BACKUP_${java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US).format(java.util.Date())}.aria")}}
            .setNeutralButton("Importar memoria"){_,_->importBackup.launch(arrayOf("application/octet-stream","application/zip","*/*"))}
            .setNegativeButton("Cerrar",null).show()
    }
    private fun askPassphrase(title:String,onReady:(CharArray)->Unit){val field=EditText(this).apply{inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;hint="Mínimo 8 caracteres"}
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title).setView(field).setPositiveButton("Continuar"){_,_->val value=field.text.toString();if(value.length>=8)onReady(value.toCharArray())else Toast.makeText(this,"La contraseña debe tener al menos 8 caracteres",Toast.LENGTH_LONG).show()}.setNegativeButton("Cancelar",null).show()}
    private fun drawableFor(e:AriaEmotion)=when(e){AriaEmotion.NEUTRAL->R.drawable.aria_neutral;AriaEmotion.HAPPY->R.drawable.aria_happy;AriaEmotion.AMUSED->R.drawable.aria_amused;AriaEmotion.THINKING->R.drawable.aria_curious;AriaEmotion.SURPRISED->R.drawable.aria_surprised;AriaEmotion.CONFUSED->R.drawable.aria_confused;AriaEmotion.ANNOYED->R.drawable.aria_annoyed;AriaEmotion.ANGRY->R.drawable.aria_angry;AriaEmotion.EMBARRASSED->R.drawable.aria_embarrassed;AriaEmotion.SAD->R.drawable.aria_sad;AriaEmotion.AFFECTIONATE->R.drawable.aria_affectionate;AriaEmotion.PLAYFUL->R.drawable.aria_playful;AriaEmotion.SERIOUS->R.drawable.aria_serious;AriaEmotion.TIRED->R.drawable.aria_tired;AriaEmotion.EXCITED->R.drawable.aria_excited}
    override fun onDestroy(){generationSequence++;generationJob?.cancel();speech?.close();scope.cancel();runBlocking{if(::brain.isInitialized)brain.close()};super.onDestroy()}
}
