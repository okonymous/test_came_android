package com.astra.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val AstraBg = Color(0xFF050A12)
private val AstraPanel = Color(0xE60A1320)
private val AstraPanel2 = Color(0xD90C1828)
private val AstraCyan = Color(0xFF66ECFF)
private val AstraBlue = Color(0xFF2F86FF)
private val AstraDeepBlue = Color(0xFF10376A)
private val AstraMuted = Color(0xFF91A3BD)
private val AstraWhite = Color(0xFFF3FBFF)
private val AstraError = Color(0xFFFF8C9A)

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
        val client = remember { GeminiClient() }
        val weatherClient = remember { RealtimeWeather(this) }
        val scope = rememberCoroutineScope()

        var apiKey by remember { mutableStateOf(prefs.geminiApiKey) }
        var model by remember { mutableStateOf(prefs.model) }
        var assistantName by remember { mutableStateOf(prefs.assistantName) }
        var input by remember { mutableStateOf("") }
        var answer by remember { mutableStateOf("Halo! Saya Astra. Siap membantu, merencanakan, dan mengeksekusi perintah Anda.") }
        var state by remember { mutableStateOf(AstraState.IDLE) }
        var pendingAction by remember { mutableStateOf<AssistantAction?>(null) }
        var showSettings by remember { mutableStateOf(apiKey.isBlank()) }
        var memory by remember { mutableStateOf(prefs.loadMemory()) }
        var errorText by remember { mutableStateOf<String?>(null) }
        var now by remember { mutableStateOf(LocalDateTime.now()) }
        var weather by remember { mutableStateOf<WeatherSnapshot?>(null) }
        var weatherStatus by remember { mutableStateOf("Mengambil cuaca...") }

        fun hasLocationPermission(): Boolean {
            return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        }

        fun refreshWeather() {
            if (!hasLocationPermission()) {
                weatherStatus = "Izinkan lokasi untuk cuaca real-time"
                return
            }
            weatherStatus = "Memperbarui cuaca..."
            scope.launch {
                try {
                    weather = weatherClient.load()
                    weatherStatus = "LIVE WEATHER"
                } catch (e: Exception) {
                    weatherStatus = e.message ?: "Cuaca belum tersedia"
                }
            }
        }

        val locationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) refreshWeather()
            else weatherStatus = "Izin lokasi belum diberikan"
        }

        LaunchedEffect(Unit) {
            while (true) {
                now = LocalDateTime.now()
                delay(1000)
            }
        }

        LaunchedEffect(Unit) {
            if (hasLocationPermission()) {
                refreshWeather()
            } else {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }

            while (true) {
                delay(15 * 60 * 1000L)
                if (hasLocationPermission()) refreshWeather()
            }
        }

        fun persist(role: String, text: String) {
            memory = (memory + MemoryTurn(role, text)).takeLast(16).toMutableList()
            prefs.saveMemory(memory)
        }

        fun friendlyUiError(e: Exception): String {
            val raw = e.message.orEmpty()
            return when {
                "503" in raw -> "Gemini sedang ramai. Astra sudah mencoba model cadangan, tetapi kapasitas masih penuh. Coba lagi sebentar."
                "429" in raw -> "Batas penggunaan Gemini sementara tercapai. Tunggu sebentar lalu coba lagi."
                "403" in raw || "401" in raw -> "Gemini API key ditolak. Periksa API key di Settings."
                "404" in raw -> "Model Gemini tidak tersedia untuk project ini. Ganti model di Settings."
                else -> raw.ifBlank { "Terjadi gangguan saat menghubungi Gemini." }
            }
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
                    errorText = friendlyUiError(e)
                    answer = "Astra belum bisa menyelesaikan perintah ini."
                    state = AstraState.IDLE
                }
            }
        }

        fun openQuickAction(label: String) {
            runCatching {
                when (label) {
                    "WhatsApp" -> AndroidActions.execute(
                        this@MainActivity,
                        AssistantAction("open_app", mapOf("name" to "whatsapp"))
                    )
                    "Email" -> startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")))
                    "Phone" -> startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
                    "Maps" -> AndroidActions.execute(
                        this@MainActivity,
                        AssistantAction("open_app", mapOf("name" to "maps"))
                    )
                    "Camera" -> startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                    "Settings" -> showSettings = true
                }
            }.onFailure {
                errorText = "Aksi $label tidak bisa dibuka di perangkat ini."
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

            if (granted) launchSpeech()
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        Surface(modifier = Modifier.fillMaxSize(), color = AstraBg) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF07111F), AstraBg, Color(0xFF03070D))
                        )
                    )
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    val wide = maxWidth >= 700.dp

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        AstraHeader(
                            assistantName = assistantName,
                            onSettings = { showSettings = true }
                        )

                        if (wide) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(330.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                SideInfoCard(
                                    title = weather?.city ?: "CUACA REAL-TIME",
                                    primary = weather?.temperatureC
                                        ?.takeUnless { it.isNaN() }
                                        ?.roundToInt()
                                        ?.let { "$it°C" }
                                        ?: "--°",
                                    secondary = weather?.let {
                                        it.description + "\nTerasa " +
                                            it.feelsLikeC.roundToInt() + "°C • Angin " +
                                            it.windKmh.roundToInt() + " km/j"
                                    } ?: weatherStatus,
                                    icon = { Icon(Icons.Default.CloudQueue, null, tint = AstraCyan) },
                                    modifier = Modifier
                                        .weight(0.62f)
                                        .fillMaxHeight()
                                )

                                OrbScene(
                                    state = state,
                                    name = assistantName,
                                    modifier = Modifier
                                        .weight(1.65f)
                                        .fillMaxHeight()
                                )

                                SideInfoCard(
                                    title = now.format(DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy", Locale("id", "ID"))),
                                    primary = now.format(DateTimeFormatter.ofPattern("HH:mm")),
                                    secondary = "Tetap fokus.\nHal besar menantimu.",
                                    icon = { Icon(Icons.Default.CalendarMonth, null, tint = AstraCyan) },
                                    modifier = Modifier
                                        .weight(0.72f)
                                        .fillMaxHeight()
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(132.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                SideInfoCard(
                                    title = weather?.city ?: "CUACA",
                                    primary = weather?.temperatureC
                                        ?.takeUnless { it.isNaN() }
                                        ?.roundToInt()
                                        ?.let { "$it°C" }
                                        ?: "--°",
                                    secondary = weather?.description ?: weatherStatus,
                                    icon = { Icon(Icons.Default.CloudQueue, null, tint = AstraCyan) },
                                    modifier = Modifier.weight(1f)
                                )
                                SideInfoCard(
                                    title = now.format(
                                        DateTimeFormatter.ofPattern("EEE, dd MMM", Locale("id", "ID"))
                                    ),
                                    primary = now.format(DateTimeFormatter.ofPattern("HH:mm")),
                                    secondary = "Waktu perangkat",
                                    icon = { Icon(Icons.Default.CalendarMonth, null, tint = AstraCyan) },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            OrbScene(
                                state = state,
                                name = assistantName,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(310.dp)
                            )
                        }

                        CommandConsole(
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
                            }
                        )

                        VoiceExecuteRow(
                            state = state,
                            input = input,
                            onVoice = { listen() },
                            onExecute = { processCommand(input) }
                        )

                        QuickActionsRow(
                            wide = wide,
                            onAction = { openQuickAction(it) }
                        )

                        Spacer(Modifier.height(8.dp))
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
                    prefs.geminiApiKey = apiKey
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
private fun AstraHeader(
    assistantName: String,
    onSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(74.dp)
    ) {
        IconButton(
            onClick = {},
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = AstraCyan)
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = assistantName.uppercase(),
                fontWeight = FontWeight.Light,
                fontSize = 30.sp,
                letterSpacing = 10.sp,
                color = AstraWhite
            )
            Text(
                text = "PERSONAL INTELLIGENCE",
                fontSize = 9.sp,
                letterSpacing = 3.sp,
                color = AstraMuted
            )
        }

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF0A1E37), RoundedCornerShape(18.dp))
                    .border(1.dp, AstraBlue.copy(alpha = 0.75f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text("FREE", color = AstraWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Person, contentDescription = "Profile", tint = AstraWhite)
            }
        }
    }
}

@Composable
private fun SideInfoCard(
    title: String,
    primary: String,
    secondary: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassPanel(modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                icon()
                Text(
                    text = title,
                    color = AstraMuted,
                    fontSize = 11.sp,
                    letterSpacing = 1.1.sp,
                    maxLines = 2
                )
            }

            Column {
                Text(
                    text = primary,
                    color = AstraWhite,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = secondary,
                    color = AstraMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(AstraCyan, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text("ONLINE", color = AstraCyan, fontSize = 10.sp, letterSpacing = 1.6.sp)
            }
        }
    }
}

@Composable
private fun OrbScene(
    state: AstraState,
    name: String,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (state == AstraState.THINKING) 500 else 1300),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    GlassPanel(modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF071827),
                            Color(0xFF07111D),
                            Color(0xFF030812)
                        )
                    )
                )
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val c = Offset(w / 2f, h * 0.48f)
                val base = minOf(w, h) * 0.27f

                for (i in 0..7) {
                    val y = h * 0.70f + i * (h * 0.035f)
                    drawLine(
                        color = AstraBlue.copy(alpha = 0.10f - i * 0.008f),
                        start = Offset(w * 0.10f, y),
                        end = Offset(w * 0.90f, y),
                        strokeWidth = 1f
                    )
                }

                for (i in -5..5) {
                    val x = w / 2f + i * w * 0.08f
                    drawLine(
                        color = AstraBlue.copy(alpha = 0.11f),
                        start = Offset(w / 2f, h * 0.64f),
                        end = Offset(x, h * 0.96f),
                        strokeWidth = 1f
                    )
                }

                drawCircle(
                    color = AstraCyan.copy(alpha = 0.08f),
                    radius = base * 1.48f,
                    center = c,
                    style = Stroke(2f)
                )
                drawCircle(
                    color = AstraBlue.copy(alpha = 0.24f),
                    radius = base * 1.20f,
                    center = c,
                    style = Stroke(3f)
                )
                drawCircle(
                    color = AstraCyan.copy(alpha = 0.65f),
                    radius = base * 0.98f * pulse,
                    center = c,
                    style = Stroke(6f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White,
                            Color(0xFF8FF8FF),
                            Color(0xFF2B8CFF),
                            Color(0xFF041329)
                        ),
                        center = c,
                        radius = base
                    ),
                    radius = base * 0.86f,
                    center = c
                )

                repeat(12) { i ->
                    val angle = (Math.PI * 2 * i / 12.0).toFloat()
                    val p1 = Offset(
                        c.x + kotlin.math.cos(angle) * base * 1.06f,
                        c.y + kotlin.math.sin(angle) * base * 1.06f
                    )
                    val p2 = Offset(
                        c.x + kotlin.math.cos(angle) * base * 1.35f,
                        c.y + kotlin.math.sin(angle) * base * 1.35f
                    )
                    drawLine(
                        color = AstraCyan.copy(alpha = 0.20f),
                        start = p1,
                        end = p2,
                        strokeWidth = 2f
                    )
                }
            }

            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = name.uppercase(),
                    color = AstraWhite,
                    fontSize = 22.sp,
                    letterSpacing = 7.sp,
                    fontWeight = FontWeight.Light
                )
                Spacer(Modifier.height(94.dp))
                Text(
                    text = stateLabel(state),
                    color = AstraCyan,
                    fontSize = 11.sp,
                    letterSpacing = 2.4.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 22.dp, top = 62.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("THINK", "UNDERSTAND", "PLAN", "EXECUTE", "WITH YOU").forEach {
                    Text(it, color = AstraMuted.copy(alpha = 0.75f), fontSize = 8.sp, letterSpacing = 1.8.sp)
                }
            }
        }
    }
}

@Composable
private fun CommandConsole(
    input: String,
    onInput: (String) -> Unit,
    answer: String,
    error: String?,
    pending: AssistantAction?,
    state: AstraState,
    onMic: () -> Unit,
    onSend: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 250.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("✦", color = AstraCyan, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "COMMAND CONSOLE",
                    color = AstraMuted,
                    fontSize = 11.sp,
                    letterSpacing = 2.2.sp,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.GraphicEq, null, tint = AstraCyan)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A1726), RoundedCornerShape(18.dp))
                    .border(1.dp, AstraBlue.copy(alpha = 0.20f), RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(min = 72.dp, max = 150.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = answer,
                        color = AstraWhite,
                        fontSize = 16.sp,
                        lineHeight = 23.sp
                    )
                    if (error != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(error, color = AstraError, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    if (pending != null) {
                        Spacer(Modifier.height(14.dp))
                        PendingActionCard(pending, onConfirm, onCancel)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IconButton(
                    onClick = {},
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFF0C2746), CircleShape)
                        .border(1.dp, AstraBlue.copy(alpha = 0.7f), CircleShape)
                ) {
                    Icon(Icons.Default.Add, null, tint = AstraWhite)
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = onInput,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ketik atau bicara ke Astra…") },
                    minLines = 1,
                    maxLines = 3,
                    trailingIcon = {
                        IconButton(onClick = onMic) {
                            Icon(Icons.Default.Mic, null, tint = AstraCyan)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AstraCyan,
                        unfocusedBorderColor = AstraBlue.copy(alpha = 0.55f),
                        focusedTextColor = AstraWhite,
                        unfocusedTextColor = AstraWhite,
                        cursorColor = AstraCyan,
                        focusedContainerColor = Color(0xFF081522),
                        unfocusedContainerColor = Color(0xFF081522)
                    ),
                    shape = RoundedCornerShape(22.dp)
                )

                if (input.isNotBlank()) {
                    IconButton(onClick = onSend) {
                        Icon(Icons.Default.Send, null, tint = AstraCyan)
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceExecuteRow(
    state: AstraState,
    input: String,
    onVoice: () -> Unit,
    onExecute: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Button(
            onClick = onVoice,
            enabled = state != AstraState.THINKING,
            modifier = Modifier
                .weight(1f)
                .height(78.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF0C2747),
                contentColor = AstraCyan
            ),
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(Icons.Default.Mic, null, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(14.dp))
            Column(horizontalAlignment = Alignment.Start) {
                Text("VOICE", color = AstraWhite, fontSize = 16.sp, letterSpacing = 1.5.sp)
                Text("BICARA DENGAN ASTRA", color = AstraMuted, fontSize = 8.sp, letterSpacing = 1.sp)
            }
        }

        Button(
            onClick = onExecute,
            enabled = input.isNotBlank() && state != AstraState.THINKING,
            modifier = Modifier
                .weight(1f)
                .height(78.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AstraBlue,
                contentColor = AstraWhite,
                disabledContainerColor = Color(0xFF162432),
                disabledContentColor = AstraMuted
            ),
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(14.dp))
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    if (state == AstraState.THINKING) "THINKING" else "EXECUTE",
                    fontSize = 16.sp,
                    letterSpacing = 1.5.sp
                )
                Text("WUJUDKAN SEKARANG", fontSize = 8.sp, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun QuickActionsRow(
    wide: Boolean,
    onAction: (String) -> Unit
) {
    val actions = listOf(
        Triple("WhatsApp", "Chat", Icons.Default.Chat),
        Triple("Email", "Tulis & Kelola", Icons.Default.Email),
        Triple("Phone", "Panggilan", Icons.Default.Phone),
        Triple("Maps", "Navigasi", Icons.Default.LocationOn),
        Triple("Camera", "Foto & Video", Icons.Default.CameraAlt),
        Triple("Settings", "Atur Astra", Icons.Default.Settings)
    )

    if (wide) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            actions.forEach { (label, sub, icon) ->
                QuickActionCard(
                    label = label,
                    sub = sub,
                    icon = icon,
                    onClick = { onAction(label) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            actions.forEach { (label, sub, icon) ->
                QuickActionCard(
                    label = label,
                    sub = sub,
                    icon = icon,
                    onClick = { onAction(label) },
                    modifier = Modifier.width(112.dp)
                )
            }
        }
    }
}

@Composable
private fun QuickActionCard(
    label: String,
    sub: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(118.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A1726)),
        contentPadding = PaddingValues(10.dp),
        shape = RoundedCornerShape(20.dp),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = Brush.linearGradient(listOf(AstraBlue.copy(alpha = 0.55f), AstraCyan.copy(alpha = 0.15f)))
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = AstraCyan, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, color = AstraWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = AstraMuted, fontSize = 9.sp, textAlign = TextAlign.Center)
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
                    label = { Text("Gemini API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = onModel,
                    label = { Text("Model utama") },
                    singleLine = true
                )
                Text(
                    text = "Rekomendasi: gemini-3.5-flash-lite. Jika 429/503, Astra otomatis mencoba 3.5 Flash lalu 3.8 Flash.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "API key dan memory disimpan terenkripsi di perangkat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AstraMuted
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
            .background(AstraPanel, RoundedCornerShape(26.dp))
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        AstraBlue.copy(alpha = 0.45f),
                        AstraCyan.copy(alpha = 0.12f),
                        AstraBlue.copy(alpha = 0.25f)
                    )
                ),
                shape = RoundedCornerShape(26.dp)
            )
    ) {
        content()
    }
}

private fun stateLabel(state: AstraState): String = when (state) {
    AstraState.IDLE -> "SYSTEM ONLINE"
    AstraState.LISTENING -> "LISTENING"
    AstraState.THINKING -> "THINKING"
    AstraState.SPEAKING -> "RESPONDING"
    AstraState.ACTION_PENDING -> "ACTION READY"
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
