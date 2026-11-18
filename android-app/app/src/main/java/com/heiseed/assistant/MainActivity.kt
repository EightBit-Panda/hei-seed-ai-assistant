
package com.heiseed.assistant

import android.os.Bundle
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.speech.tts.TextToSpeech
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heiseed.assistant.ui.theme.HeiSeedTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private val HeiSeedDarkColorScheme = darkColorScheme(
    primary = Color(0xFF36E6C0),
    onPrimary = Color(0xFF00251F),
    secondary = Color(0xFF32C9F2),
    onSecondary = Color(0xFF001F2A),
    background = Color(0xFF010F26),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF102744),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF193957),
    onSurfaceVariant = Color(0xFFAFC7E8),
    outline = Color(0xFF42617F)
)

class MainActivity : ComponentActivity() {

    private lateinit var imageReceiver: ImageReceiver

    private val activityScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Process received images in arrival order to prevent stale UI/AI results.
    private val imageProcessingMutex = Mutex()

    private var latestBitmap by mutableStateOf<Bitmap?>(null)
    private var aiResponse by mutableStateOf("")
    private var aiStatus by mutableStateOf("Waiting for image from Seed")

    // Connected means a recent, authenticated heartbeat was received from Seed.
    private var deviceConnected by mutableStateOf(false)
    private var lastHeartbeatElapsedRealtime by mutableStateOf(0L)

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady by mutableStateOf(false)
    private var voiceEnabled by mutableStateOf(true)
    private var speechRate by mutableStateOf(1.0f)
    private val voicePreferences by lazy {
        getSharedPreferences("hei_seed_voice", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val apiKeyManager = ApiKeyManager(applicationContext)
        val deviceAuthManager = DeviceAuthManager(applicationContext)

        voiceEnabled = voicePreferences.getBoolean("voice_enabled", true)
        speechRate = voicePreferences.getFloat("speech_rate", 1.0f)

        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.forLanguageTag("nb-NO"))
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                textToSpeech?.setSpeechRate(speechRate)
                Log.i("HEI_SEED", if (ttsReady) {
                    "Norwegian text-to-speech ready"
                } else {
                    "Norwegian text-to-speech is not available"
                })
            } else {
                ttsReady = false
                Log.e("HEI_SEED", "Text-to-speech initialization failed")
            }
        }

        imageReceiver = ImageReceiver(
            context = applicationContext,
            onImageReceived = { file ->
                activityScope.launch {
                    imageProcessingMutex.withLock {
                        aiResponse = ""
                        aiStatus = "Image received. Loading image..."

                        Log.i(
                            "HEI_SEED",
                            "Image received: ${file.name}, size: ${file.length()} bytes"
                        )

                        try {
                            // Decode the newly received image on a background thread.
                            val newBitmap = withContext(Dispatchers.IO) {
                                BitmapFactory.decodeFile(file.absolutePath)
                            }

                            if (newBitmap == null) {
                                throw IllegalStateException("Could not decode received JPEG image")
                            }

                            // Update the displayed bitmap directly.
                            latestBitmap = newBitmap

                            Log.i(
                                "HEI_SEED",
                                "New image displayed: ${file.name}"
                            )

                            val apiKey = apiKeyManager.getApiKey()

                            if (apiKey.isNullOrBlank()) {
                                aiStatus = "No API key found. Please check Settings."
                                return@withLock
                            }

                            aiStatus = "Sending image to OpenAI..."

                            val openAIClient = OpenAIClient(apiKey)
                            aiResponse = openAIClient.analyzeImage(file)

                            aiStatus = "Analysis complete"

                            Log.i("HEI_SEED", "OpenAI analysis completed")

                        } catch (e: Exception) {
                            aiStatus = "AI analysis failed: ${e.message ?: "Unknown error"}"
                            Log.e("HEI_SEED", "Image processing failed", e)
                        }
                    }
                }
            },
            onHeartbeatReceived = {
                activityScope.launch {
                    lastHeartbeatElapsedRealtime = SystemClock.elapsedRealtime()
                    deviceConnected = true
                }
            }
        )

        imageReceiver.start()
        Log.i("HEI_SEED", "Starting image receiver")

        // Mark Seed disconnected if no authenticated heartbeat arrives for 10 seconds.
        activityScope.launch {
            while (true) {
                val lastHeartbeat = lastHeartbeatElapsedRealtime
                if (lastHeartbeat == 0L ||
                    SystemClock.elapsedRealtime() - lastHeartbeat > 10_000L
                ) {
                    deviceConnected = false
                }
                delay(1_000L)
            }
        }

        setContent {
            HeiSeedTheme {
                MaterialTheme(colorScheme = HeiSeedDarkColorScheme) {
                    HeiSeedHomeScreen(
                        latestBitmap = latestBitmap,
                        aiResponse = aiResponse,
                        aiStatus = aiStatus,
                        deviceConnected = deviceConnected,
                        apiKeyManager = apiKeyManager,
                        deviceAuthManager = deviceAuthManager,
                        ttsReady = ttsReady,
                        voiceEnabled = voiceEnabled,
                        speechRate = speechRate,
                        onVoiceEnabledChange = { enabled ->
                            voiceEnabled = enabled
                            voicePreferences.edit().putBoolean("voice_enabled", enabled).apply()
                            if (!enabled) textToSpeech?.stop()
                        },
                        onSpeechRateChange = { rate ->
                            speechRate = rate
                            voicePreferences.edit().putFloat("speech_rate", rate).apply()
                            textToSpeech?.setSpeechRate(rate)
                        },
                        onSpeakAgain = {
                            if (ttsReady && aiResponse.isNotBlank()) {
                                textToSpeech?.speak(
                                    aiResponse,
                                    TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    "hei_seed_current_response"
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        imageReceiver.stop()
        activityScope.cancel()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }
}

@Composable
fun HeiSeedHomeScreen(
    latestBitmap: Bitmap?,
    aiResponse: String,
    aiStatus: String,
    deviceConnected: Boolean,
    apiKeyManager: ApiKeyManager,
    deviceAuthManager: DeviceAuthManager,
    ttsReady: Boolean,
    voiceEnabled: Boolean,
    speechRate: Float,
    onVoiceEnabledChange: (Boolean) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onSpeakAgain: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val logoHeight = if (configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
        200.dp
    } else {
        150.dp
    }

    // Speak each newly completed analysis while keeping the text visible.
    LaunchedEffect(aiResponse, ttsReady) {
        if (aiResponse.isNotBlank() && ttsReady && voiceEnabled) {
            // The Activity callback owns the TTS engine; this effect is keyed
            // only to new analysis text and TTS readiness, not the toggle.
            onSpeakAgain()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {

            if (showSettings) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { showSettings = false }
                    ) {
                        Text("← Back")
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Text(
                        text = "Settings",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                var voiceSettingsExpanded by remember { mutableStateOf(false) }
                var deviceStatusExpanded by remember { mutableStateOf(false) }

                SettingsAccordionSection(
                    title = "Voice Settings",
                    subtitle = "Spoken responses and speech speed",
                    expanded = voiceSettingsExpanded,
                    onToggle = { voiceSettingsExpanded = !voiceSettingsExpanded }
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Text(
                            text = "Voice settings",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Spoken responses", fontWeight = FontWeight.Medium)
                                Text(
                                    "Automatically read new analyses aloud in Norwegian",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = voiceEnabled,
                                onCheckedChange = onVoiceEnabledChange
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Speech speed: ${String.format(Locale.getDefault(), "%.1fx", speechRate)}",
                            fontWeight = FontWeight.Medium
                        )
                        Slider(
                            value = speechRate,
                            onValueChange = onSpeechRateChange,
                            valueRange = 0.7f..1.3f,
                            steps = 5,
                            enabled = ttsReady
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Slower", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Normal", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Faster", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        if (!ttsReady) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Norwegian text-to-speech is not ready. Check the Google text-to-speech language settings on your phone.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                ApiKeyScreen(
                    apiKeyManager = apiKeyManager,
                    deviceAuthManager = deviceAuthManager
                )

                Spacer(modifier = Modifier.height(12.dp))

                SettingsAccordionSection(
                    title = "Device Status",
                    subtitle = "Seed connection and image receiver",
                    expanded = deviceStatusExpanded,
                    onToggle = { deviceStatusExpanded = !deviceStatusExpanded }
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Text(
                            text = if (deviceConnected) {
                                "Seed is connected"
                            } else {
                                "Seed is not connected"
                            },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (deviceConnected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Authenticated heartbeat and image receiver on port 8080",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

            } else {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    modifier = Modifier.size(8.dp),
                                    shape = RoundedCornerShape(50),
                                    color = if (deviceConnected) {
                                        Color(0xFF36E6C0)
                                    } else {
                                        Color(0xFFFF647C)
                                    }
                                ) {}
                                Spacer(modifier = Modifier.width(7.dp))
                                Text(
                                    text = if (deviceConnected) "Connected" else "Not connected",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    TextButton(
                        onClick = { showSettings = true }
                    ) {
                        Text(
                            text = "⚙ Settings",
                            fontSize = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.hei_seed_main_logo),
                        contentDescription = "Hei Seed - Your AI Companion for the World Around You",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(logoHeight),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Text(
                            text = "Latest image",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (latestBitmap != null) {
                                Image(
                                    bitmap = latestBitmap.asImageBitmap(),
                                    contentDescription = "Latest image received from Seed",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "No image received",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Text(
                            text = "Hei Seed's analysis",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = aiStatus,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )

                        if (aiResponse.isNotBlank()) {
                            Spacer(modifier = Modifier.height(16.dp))

                            HorizontalDivider()

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = aiResponse,
                                fontSize = 16.sp,
                                lineHeight = 24.sp
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            OutlinedButton(
                                onClick = onSpeakAgain,
                                enabled = ttsReady && aiResponse.isNotBlank()
                            ) {
                                Text("▶  Read response aloud again")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "AI assistant ready",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}



@Composable
fun SettingsAccordionSection(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                onClick = onToggle,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = subtitle,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = if (expanded) "−" else "+",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (expanded) {
                HorizontalDivider()
                content()
            }
        }
    }
}
