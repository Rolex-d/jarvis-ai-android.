package com.jarvis.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class ChatMessage(val sender: String, val text: String)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var deviceController: DeviceController
    private var speechRecognizer: SpeechRecognizer? = null
    private var onSpeechFinished: ((String) -> Unit)? = null
    private var onStateChanged: ((JarvisState) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        deviceController = DeviceController(this)

        setContent {
            JarvisHudScreen()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setPitch(0.95f)
            tts.setSpeechRate(1.05f)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runOnUiThread { onStateChanged?.invoke(JarvisState.IDLE) }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread { onStateChanged?.invoke(JarvisState.IDLE) }
                }
            })
        }
    }

    private fun speak(text: String) {
        val clean = text.replace("*", "").replace("#", "").trim()
        onStateChanged?.invoke(JarvisState.SPEAKING)
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
    }

    private fun startListening() {
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    onStateChanged?.invoke(JarvisState.LISTENING)
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    onStateChanged?.invoke(JarvisState.THINKING)
                }
                override fun onError(error: Int) {
                    onStateChanged?.invoke(JarvisState.IDLE)
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        onSpeechFinished?.invoke(text)
                    } else {
                        onStateChanged?.invoke(JarvisState.IDLE)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Jarvis")
        }
        onStateChanged?.invoke(JarvisState.LISTENING)
        speechRecognizer?.startListening(intent)
    }

    private suspend fun askGemini(apiKey: String, prompt: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext "Boss, tap the settings icon at the top right and paste your Gemini API key to unlock AI answers."
        }
        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=$apiKey"
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
            }

            val body = JSONObject().put(
                "contents", JSONArray().put(
                    JSONObject().put(
                        "parts", JSONArray().put(
                            JSONObject().put("text", "You are Iron Man's JARVIS. Answer concisely, smartly, and call the user boss. User says: $prompt")
                        )
                    )
                )
            )

            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode in 200..299) {
                val response = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
                "Connection glitch (${conn.responseCode}), boss. Check your API key in Settings. Details: ${err.take(80)}"
            }
        } catch (e: Exception) {
            "Unable to reach the AI mainframe, boss. Please check your internet connection."
        }
    }

    @Composable
    fun JarvisHudScreen() {
        val prefs = remember { getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE) }
        var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
        var showSettings by remember { mutableStateOf(false) }

        var state by remember { mutableStateOf(JarvisState.IDLE) }
        var inputText by remember { mutableStateOf("") }
        val messages = remember {
            mutableStateListOf(ChatMessage("JARVIS", "All systems online, boss. Tap the Arc Reactor to speak or type a command below."))
        }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        fun processCommand(cmd: String) {
            val cleanCmd = cmd.trim()
            if (cleanCmd.isEmpty()) return
            messages.add(ChatMessage("YOU", cleanCmd))
            scope.launch {
                listState.animateScrollToItem(messages.size - 1)
                state = JarvisState.THINKING

                val localReply = deviceController.handleCommand(cleanCmd)
                val finalReply = localReply ?: askGemini(apiKey, cleanCmd)

                messages.add(ChatMessage("JARVIS", finalReply))
                listState.animateScrollToItem(messages.size - 1)
                speak(finalReply)
            }
        }

        SideEffect {
            onStateChanged = { newState -> state = newState }
            onSpeechFinished = { spoken -> processCommand(spoken) }
        }

        val micPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) startListening()
            else messages.add(ChatMessage("JARVIS", "Microphone permission is required for voice commands, boss."))
        }

        val bgDark = Color(0xFF020812)
        val cyan = Color(0xFF00D9FF)
        val cardDark = Color(0xFF071526)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgDark)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top HUD Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "J.A.R.V.I.S.",
                        color = cyan,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp
                    )
                    Text(
                        text = when (state) {
                            JarvisState.IDLE -> "STATUS: STANDBY • TAP REACTOR"
                            JarvisState.LISTENING -> "STATUS: LISTENING..."
                            JarvisState.THINKING -> "STATUS: PROCESSING..."
                            JarvisState.SPEAKING -> "STATUS: TRANSMITTING..."
                        },
                        color = cyan.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "API Settings", tint = cyan)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Interactive Arc Reactor Mic Button
            ArcReactor(
                state = state,
                onClick = {
                    if (state == JarvisState.LISTENING) {
                        speechRecognizer?.stopListening()
                        state = JarvisState.IDLE
                    } else {
                        val hasPerm = ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasPerm) startListening()
                        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Scrollable Command & Response Log
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(1.dp, cyan.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                    .background(cardDark.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages) { msg ->
                    val isJarvis = msg.sender == "JARVIS"
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = msg.sender,
                            color = if (isJarvis) cyan else Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = msg.text,
                            color = Color(0xFFE2F1F8),
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Text Command Input Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Type a command...", color = cyan.copy(alpha = 0.45f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = cyan,
                        unfocusedBorderColor = cyan.copy(alpha = 0.35f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = cyan
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val cmd = inputText
                        inputText = ""
                        processCommand(cmd)
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .background(cyan.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                        .border(1.dp, cyan, RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send", tint = cyan)
                }
            }
        }

        // Settings Dialog for Gemini API Key
        if (showSettings) {
            var tempKey by remember { mutableStateOf(apiKey) }
            AlertDialog(
                onDismissRequest = { showSettings = false },
                containerColor = cardDark,
                title = { Text("JARVIS AI Core Settings", color = cyan, fontFamily = FontFamily.Monospace) },
                text = {
                    Column {
                        Text("Paste your Gemini API key below:", color = Color.White, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tempKey,
                            onValueChange = { tempKey = it },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = cyan,
                                unfocusedBorderColor = cyan.copy(alpha = 0.4f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        apiKey = tempKey.trim()
                        prefs.edit().putString("api_key", apiKey).apply()
                        showSettings = false
                    }) {
                        Text("SAVE", color = cyan, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSettings = false }) {
                        Text("CANCEL", color = Color.Gray)
                    }
                }
            )
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts.shutdown()
        super.onDestroy()
    }
}
