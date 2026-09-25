package com.astra.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.util.Locale

private val AstraBg = Color(0xFF05080D)
private val AstraPanel = Color(0xE60B121B)
private val AstraCyan = Color(0xFF5CEBFF)
private val AstraBlue = Color(0xFF4A8CFF)
private val AstraMuted = Color(0xFF8DA0B7)
private val AstraWhite = Color(0xFFEAF8FF)

enum class AstraState { IDLE, LISTENING, THINKING, SPEAKING, ACTION_PENDING }

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        setContent {
            AstraTheme {
                AstraApp()
            }
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts.language = Locale("id", "ID")
            tts.setSpeechRate(1.02f)
            tts.setPitch(0.96f)
        }
    }

    private fun speak(text: String, onDone: () -> Unit) {
        if (!ttsReady) {
            onDone()
            return
        }
        val utteranceId = "astra-" + System.currentTimeMillis()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = runOnUiThread(onDone)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = runOnUiThread(onDone)
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }

    @Composable
    private fun AstraApp() {
        val prefs = remember { SecurePrefs(this) }
        val client = remember { OpenAIClient() }
        val scope = rememberCoroutineScope()

        var apiKey by remember { mutableStateOf(prefs.apiKey) }
        var model by remember { mutableStateOf(prefs.model) }
        var assistantName by remember { mutableStateOf(prefs.assistantName) }
        var input by remember { mutableStateOf("") }
        var answer by remember { mutableStateOf("Sistem siap. Tekan VOICE dan bicara dengan Astra.") }
        var state by remember { mutableStateOf(AstraState.IDLE) }
        var pendingAction by remember { mutableStateOf<AssistantAction?>(null) }
        var showSettings by remember { mutableStateOf(apiKey.isBlank()) }
        var memory by remember { mutableStateOf(prefs.loadMemory()) }
        var errorText by remember { mutableStateOf<String?>(null) }

        fun persist(role: String, text: String) {
            memory = (memory + MemoryTurn(role, text)).takeLast(16).toMutableList()
            prefs.saveMemory(memory)
        }

        fun processCommand(command: String) {
            val clean = command.trim()
            if (clean.isBlank() || state == AstraState.THINKING) return
            if (apiKey.isBlank()) {
                showSettings = true
                return
            }

            input = ""
            errorText = null
            state = AstraState.THINKING
            persist("user", clean)

            scope.launch {
                try {
                    val plan = client.plan(
                        apiKey = apiKey,
                        model = model,
                        assistantName = assistantName,
                        userText = clean,
                        memory = memory.dropLast(1)
                    )
                    answer = plan.spoken
                    persist("assistant", plan.spoken)
                    pendingAction = plan.action.takeIf { it.type != "none" }
                    state = if (pendingAction == null) AstraState.SPEAKING else AstraState.ACTION_PENDING
                    speak(plan.spoken) {
                        if (pendingAction == null) state = AstraState.IDLE
                    }
                } catch (e: Exception) {
                    errorText = e.message ?: "Terjadi kesalahan."
                    answer = "Koneksi ke otak Astra gagal. Cek API key, model, dan koneksi internet."
                    state = AstraState.IDLE
                }
            }
        }

        val speechLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val heard = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (heard.isNullOrBlank()) {
                state = AstraState.IDLE
            } else {
                processCommand(heard)
            }
        }

        fun launchSpeech() {
            state = AstraState.LISTENING
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Bicara dengan " + assistantName)
            }
            speechLauncher.launch(intent)
        }

        val micPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) launchSpeech() else state = AstraState.IDLE
        }

        fun listen() {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (granted) {
                launchSpeech()
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        Surface(modifier = Modifier.fillMaxSize(), color = AstraBg) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0x332F8DFF), AstraBg),
                            center = Offset(500f, 300f),
                            radius = 1200f
                        )
                    )
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp)
                ) {
                    val wide = maxWidth >= 720.dp

                    if (wide) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            StatusPane(
                                name = assistantName,
                                model = model,
                                state = state,
                                modifier = Modifier.weight(0.9f),
                                onSettings = { showSettings = true }
                            )
                            ControlPane(
                                input = input,
                                onInput = { input = it },
                                answer = answer,
                                error = errorText,
                                pending = pendingAction,
                                state = state,
                                onMic = { listen() },
                                onSend = { processCommand(input) },
                                onConfirm = {
                                    val result = pendingAction?.let {
                                        AndroidActions.execute(this@MainActivity, it)
                                    }
                                    pendingAction = null
                                    if (result != null) answer = result
                                    state = AstraState.IDLE
                                },
                                onCancel = {
                                    pendingAction = null
                                    answer = "Aksi dibatalkan."
                                    state = AstraState.IDLE
                                },
                                modifier = Modifier.weight(1.5f)
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            StatusPane(
                                name = assistantName,
                                model = model,
                                state = state,
                                modifier = Modifier.weight(0.84f),
                                onSettings = { showSettings = true }
                            )
                            ControlPane(
                                input = input,
                                onInput = { input = it },
                                answer = answer,
                                error = errorText,
                                pending = pendingAction,
                                state = state,
                                onMic = { listen() },
                                onSend = { processCommand(input) },
                                onConfirm = {
                                    val result = pendingAction?.let {
                                        AndroidActions.execute(this@MainActivity, it)
                                    }
                                    pendingAction = null
                                    if (result != null) answer = result
                                    state = AstraState.IDLE
                                },
                                onCancel = {
                                    pendingAction = null
                                    answer = "Aksi dibatalkan."
                                    state = AstraState.IDLE
                                },
                                modifier = Modifier.weight(1.2f)
                            )
                        }
                    }
                }
            }
        }

        if (showSettings) {
            SettingsDialog(
                apiKey = apiKey,
                model = model,
                assistantName = assistantName,
                onApiKey = { apiKey = it },
                onModel = { model = it },
                onName = { assistantName = it },
                onSave = {
                    prefs.apiKey = apiKey
                    prefs.model = model
                    prefs.assistantName = assistantName
                    assistantName = prefs.assistantName
                    showSettings = false
                },
                onClearMemory = {
                    prefs.clearMemory()
                    memory = mutableListOf()
                    answer = "Memory lokal Astra sudah dikosongkan."
                },
                onDismiss = { showSettings = false }
            )
        }
    }
}

@Composable
private fun AstraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AstraCyan,
            secondary = AstraBlue,
            background = AstraBg,
            surface = AstraPanel,
            onPrimary = Color.Black,
            onBackground = AstraWhite,
            onSurface = AstraWhite
        ),
        content = content
    )
}

@Composable
private fun StatusPane(
    name: String,
    model: String,
    state: AstraState,
    modifier: Modifier,
    onSettings: () -> Unit
) {
    GlassPanel(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            IconButton(
                onClick = onSettings,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = AstraMuted)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = name.uppercase(),
                        letterSpacing = 5.sp,
                        fontWeight = FontWeight.Bold,
                        fontSize = 21.sp,
                        color = AstraWhite
                    )
                    Text(
                        text = "PERSONAL INTELLIGENCE",
                        letterSpacing = 2.sp,
                        fontSize = 10.sp,
                        color = AstraMuted
                    )
                }

                AstraCore(state)

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stateLabel(state),
                        color = AstraCyan,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(text = model, color = AstraMuted, fontSize = 12.sp)
                    Text(
                        text = "Z FOLD MODE • ENCRYPTED LOCAL MEMORY",
                        color = AstraMuted,
                        fontSize = 9.sp,
                        letterSpacing = 0.8.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun AstraCore(state: AstraState) {
    val transition = rememberInfiniteTransition(label = "astra-core")
    val pulse by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (state == AstraState.THINKING) 600 else 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(modifier = Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val base = size.minDimension / 2f
            drawCircle(
                color = AstraCyan.copy(alpha = 0.12f),
                radius = base * 0.95f * pulse,
                center = center,
                style = Stroke(width = 2f)
            )
            drawCircle(
                color = AstraBlue.copy(alpha = 0.28f),
                radius = base * 0.72f,
                center = center,
                style = Stroke(width = 4f)
            )
            drawCircle(
                color = AstraCyan.copy(alpha = 0.50f),
                radius = base * 0.48f * pulse,
                center = center,
                style = Stroke(width = 8f, cap = StrokeCap.Round)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(AstraWhite, AstraCyan, AstraBlue.copy(alpha = 0.25f)),
                    center = center,
                    radius = base * 0.4f
                ),
                radius = base * 0.31f,
                center = center
            )
        }

        Text(
            text = when (state) {
                AstraState.IDLE -> "ASTRA"
                AstraState.LISTENING -> "LISTEN"
                AstraState.THINKING -> "THINK"
                AstraState.SPEAKING -> "VOICE"
                AstraState.ACTION_PENDING -> "READY"
            },
            color = Color.Black.copy(alpha = 0.72f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.2.sp
        )
    }
}

@Composable
private fun ControlPane(
    input: String,
    onInput: (String) -> Unit,
    answer: String,
    error: String?,
    pending: AssistantAction?,
    state: AstraState,
    onMic: () -> Unit,
    onSend: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier
) {
    GlassPanel(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "COMMAND CONSOLE",
                color = AstraMuted,
                fontSize = 11.sp,
                letterSpacing = 2.sp
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = answer,
                    color = AstraWhite,
                    fontSize = 18.sp,
                    lineHeight = 27.sp
                )

                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(text = error, color = Color(0xFFFF8F8F), fontSize = 12.sp)
                }

                if (pending != null) {
                    Spacer(Modifier.height(16.dp))
                    PendingActionCard(
                        action = pending,
                        onConfirm = onConfirm,
                        onCancel = onCancel
                    )
                }
            }

            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Ketik atau bicara ke Astra…") },
                minLines = 1,
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AstraCyan,
                    unfocusedBorderColor = AstraMuted.copy(alpha = 0.35f),
                    focusedTextColor = AstraWhite,
                    unfocusedTextColor = AstraWhite,
                    cursorColor = AstraCyan
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onMic,
                    enabled = state != AstraState.THINKING,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("VOICE")
                }

                Button(
                    onClick = onSend,
                    enabled = input.isNotBlank() && state != AstraState.THINKING,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Send, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state == AstraState.THINKING) "THINKING" else "EXECUTE")
                }
            }

            Text(
                text = "Aksi perangkat selalu meminta konfirmasi sebelum dijalankan.",
                color = AstraMuted,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PendingActionCard(
    action: AssistantAction,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AstraCyan.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
            .border(1.dp, AstraCyan.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text(
            text = "ACTION READY",
            color = AstraCyan,
            fontSize = 11.sp,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(text = describeAction(action), color = AstraWhite)
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                Text("JALANKAN")
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Clear, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("BATAL")
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    apiKey: String,
    model: String,
    assistantName: String,
    onApiKey: (String) -> Unit,
    onModel: (String) -> Unit,
    onName: (String) -> Unit,
    onSave: () -> Unit,
    onClearMemory: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Astra Core Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = assistantName,
                    onValueChange = onName,
                    label = { Text("Nama asisten") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKey,
                    label = { Text("OpenAI API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = onModel,
                    label = { Text("Model ID") },
                    singleLine = true
                )
                Text(
                    text = "Default: gpt-5.6-sol. API key disimpan terenkripsi di perangkat.",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = onClearMemory) {
                    Text("Hapus memory lokal")
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text("Simpan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Tutup")
            }
        }
    )
}

@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .background(AstraPanel, RoundedCornerShape(24.dp))
            .border(1.dp, AstraCyan.copy(alpha = 0.16f), RoundedCornerShape(24.dp))
    ) {
        content()
    }
}

private fun stateLabel(state: AstraState): String = when (state) {
    AstraState.IDLE -> "SYSTEM ONLINE"
    AstraState.LISTENING -> "LISTENING"
    AstraState.THINKING -> "PROCESSING"
    AstraState.SPEAKING -> "RESPONDING"
    AstraState.ACTION_PENDING -> "ACTION AWAITING CONFIRMATION"
}

private fun describeAction(action: AssistantAction): String = when (action.type) {
    "whatsapp" -> "WhatsApp ke " + action.params["number"].orEmpty() + ": " + action.params["message"].orEmpty()
    "email" -> "Email ke " + action.params["to"].orEmpty() + " • " + action.params["subject"].orEmpty()
    "dial" -> "Buka dialer: " + action.params["number"].orEmpty()
    "sms" -> "SMS ke " + action.params["number"].orEmpty() + ": " + action.params["message"].orEmpty()
    "alarm" -> "Set alarm " + action.params["hour"].orEmpty() + ":" + (action.params["minute"] ?: "00")
    "maps" -> "Buka Maps: " + action.params["query"].orEmpty()
    "open_url" -> "Buka URL: " + action.params["url"].orEmpty()
    "open_app" -> "Buka aplikasi: " + action.params["name"].orEmpty()
    else -> "Aksi: " + action.type
}
