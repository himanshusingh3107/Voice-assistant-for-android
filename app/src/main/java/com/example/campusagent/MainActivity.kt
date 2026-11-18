package com.example.campusagent

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: Long = System.currentTimeMillis() + (0..999).random(),
    val sender: MessageSender,
    val text: String,
    val actionBadge: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class MessageSender {
    USER,
    ASSISTANT,
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF6C8CFF),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFF1E293B),
                    onPrimaryContainer = Color(0xFFE2E8F0),
                    surface = Color(0xFF0F172A),
                    onSurface = Color(0xFFF8FAFC),
                    surfaceContainer = Color(0xFF1E293B),
                    surfaceContainerHigh = Color(0xFF273549),
                ),
            ) {
                CampusGeminiChatScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CampusGeminiChatScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtime = remember(context) { CampusAgentRuntime(context) }
    val prefs = remember(context) { context.getSharedPreferences("campus_settings", Context.MODE_PRIVATE) }

    var inputText by remember { mutableStateOf("") }
    var isThinking by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var voiceOutputEnabled by remember {
        mutableStateOf(prefs.getBoolean("voice_output", true))
    }
    var wakeWordEnabled by remember {
        mutableStateOf(prefs.getBoolean("wake_word_enabled", true))
    }

    LaunchedEffect(wakeWordEnabled) {
        if (wakeWordEnabled) {
            CampusWakeWordService.start(context)
        } else {
            CampusWakeWordService.stop(context)
        }
    }

    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                sender = MessageSender.ASSISTANT,
                text = "Hello! I am your Gemini AI Assistant. 🌟\n\nI can answer questions on any topic, perform Google web searches, and execute complex app actions (e.g. \"Open YouTube and play old hindi song\", \"Open Instagram and search witness.31 and follow that account\", \"Search python tutorial on Google\", \"Set alarm for 7 AM\"). How can I help you today?",
            ),
        )
    }

    val listState = rememberLazyListState()

    var ttsInstance by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        val tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsInstance?.language = Locale("en", "IN")
            }
        }
        ttsInstance = tts
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    fun speak(text: String) {
        if (voiceOutputEnabled && text.isNotBlank()) {
            val clean = text.replace(Regex("[*#_`~]"), "")
            ttsInstance?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "AgentResponse")
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        isListening = false
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = spoken?.firstOrNull()?.trim()
            if (!text.isNullOrBlank()) {
                inputText = text
            }
        }
    }

    fun handleSend(query: String) {
        val q = query.trim()
        if (q.isBlank() || isThinking) return

        messages.add(ChatMessage(sender = MessageSender.USER, text = q))
        inputText = ""
        isThinking = true

        scope.launch {
            listState.animateScrollToItem(messages.size - 1)
            val result = runtime.ask(q)
            val (answerText, badge) = when (result) {
                is AgentResult.Success -> {
                    val b = if (result.route == Route.LOCAL_TOOL) {
                        "⚡ Device Action"
                    } else if (result.reason.contains("News", ignoreCase = true)) {
                        "📰 Live News"
                    } else {
                        "✨ Gemini 3.5 Flash Lite"
                    }
                    Pair(result.answer, b)
                }
                is AgentResult.Failure -> {
                    val b = if (result.reason.contains("Offline", ignoreCase = true) || result.reason.contains("Internet", ignoreCase = true)) {
                        "📶 Offline"
                    } else {
                        "⚠️ Status"
                    }
                    Pair(result.message, b)
                }
            }

            messages.add(
                ChatMessage(
                    sender = MessageSender.ASSISTANT,
                    text = answerText,
                    actionBadge = badge,
                ),
            )
            isThinking = false
            speak(answerText)
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val suggestions = remember {
        listOf(
            Pair("🎬", "Open YouTube and play old hindi song"),
            Pair("📸", "Open Instagram and search witness.31 and follow that account"),
            Pair("🌐", "Search python tutorial on Google"),
            Pair("🗺️", "Search coffee shop on Maps"),
            Pair("⏰", "Set alarm for 7 AM"),
            Pair("📰", "Latest News Headlines"),
            Pair("📅", "Monday Timetable"),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF3B82F6).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("✨", fontSize = 18.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "Campus Gemini",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF22C55E)),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    "Gemini 3.5 Flash Lite Online",
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8),
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        voiceOutputEnabled = !voiceOutputEnabled
                        prefs.edit().putBoolean("voice_output", voiceOutputEnabled).apply()
                        if (!voiceOutputEnabled) ttsInstance?.stop()
                        Toast.makeText(
                            context,
                            if (voiceOutputEnabled) "Voice output ON 🔊" else "Voice output MUTED 🔇",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }) {
                        Text(if (voiceOutputEnabled) "🔊" else "🔇", fontSize = 18.sp)
                    }

                    IconButton(onClick = { showSettingsDialog = true }) {
                        Text("⚙️", fontSize = 18.sp)
                    }

                    IconButton(onClick = {
                        messages.clear()
                        messages.add(
                            ChatMessage(
                                sender = MessageSender.ASSISTANT,
                                text = "Conversation cleared. How can I assist you now?",
                            ),
                        )
                    }) {
                        Text("🗑️", fontSize = 18.sp)
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            // Chat message list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { Spacer(Modifier.height(8.dp)) }

                items(messages, key = { it.id }) { msg ->
                    ChatBubble(
                        message = msg,
                        onReplayVoice = { speak(msg.text) },
                        onCopy = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Gemini", msg.text))
                            Toast.makeText(context, "Copied to clipboard 📋", Toast.LENGTH_SHORT).show()
                        },
                    )
                }

                if (isThinking) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Gemini is thinking...",
                                fontSize = 13.sp,
                                color = Color(0xFF94A3B8),
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(8.dp)) }
            }

            // Quick suggestion chips
            AnimatedVisibility(
                visible = messages.size <= 2 && !isThinking,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        "Try asking:",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B),
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        suggestions.forEach { (icon, prompt) ->
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.clickable { handleSend(prompt) },
                            ) {
                                Text(
                                    "$icon $prompt",
                                    fontSize = 12.sp,
                                    color = Color(0xFFCBD5E1),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Input Bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                "Ask anything or say 'Open YouTube'...",
                                fontSize = 14.sp,
                                color = Color(0xFF64748B),
                            )
                        },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                    )

                    // Mic Button
                    IconButton(
                        onClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                )
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Gemini...")
                            }
                            isListening = true
                            speechLauncher.launch(intent)
                        },
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(if (isListening) Color(0xFFEF4444) else MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Text(if (isListening) "🎙️" else "🎤", fontSize = 20.sp)
                    }

                    // Send Button
                    IconButton(
                        onClick = { handleSend(inputText) },
                        enabled = inputText.isNotBlank() && !isThinking,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(
                                if (inputText.isNotBlank() && !isThinking) MaterialTheme.colorScheme.primary
                                else Color(0xFF334155),
                            ),
                    ) {
                        Text("🚀", fontSize = 20.sp)
                    }
                }
            }
        }
    }

    if (showSettingsDialog) {
        var tempApiKey by remember {
            mutableStateOf(prefs.getString("gemini_api_key", "") ?: "")
        }
        var tempNewsKey by remember {
            mutableStateOf(prefs.getString("news_api_key", "") ?: "")
        }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Gemini Assistant Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Google Gemini API Key:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = tempApiKey,
                        onValueChange = { tempApiKey = it },
                        placeholder = { Text("Default: Cloud Gemini API") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Text(
                        "NewsAPI Key (for live headlines):",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = tempNewsKey,
                        onValueChange = { tempNewsKey = it },
                        placeholder = { Text("4fbf1007129e49e1b0eab0895d8c8ed7") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Text(
                        "Active Model: Google Gemini 3.5 Flash Lite (Cloud) + NewsAPI Live Headlines",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Auto Voice Output (TTS)", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = voiceOutputEnabled,
                            onCheckedChange = {
                                voiceOutputEnabled = it
                                prefs.edit().putBoolean("voice_output", it).apply()
                            },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Background Wake-Word 🎙️", style = MaterialTheme.typography.bodyMedium)
                            Text("Listens for 'Campus Gemini' in background", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                        }
                        Switch(
                            checked = wakeWordEnabled,
                            onCheckedChange = {
                                wakeWordEnabled = it
                                prefs.edit().putBoolean("wake_word_enabled", it).apply()
                                if (it) {
                                    CampusWakeWordService.start(context)
                                    Toast.makeText(context, "Background Wake-Word Enabled 🎙️", Toast.LENGTH_SHORT).show()
                                } else {
                                    CampusWakeWordService.stop(context)
                                    Toast.makeText(context, "Background Wake-Word Disabled 🔇", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }

                    Button(
                        onClick = {
                            try {
                                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "Could not open Accessibility Settings", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Enable Auto-Follow / Auto-Action ⚙️")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    prefs.edit()
                        .putString("gemini_api_key", tempApiKey.trim())
                        .putString("news_api_key", tempNewsKey.trim())
                        .apply()
                    showSettingsDialog = false
                    Toast.makeText(context, "Settings saved!", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    onReplayVoice: () -> Unit,
    onCopy: () -> Unit,
) {
    val isUser = message.sender == MessageSender.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3B82F6).copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("✨", fontSize = 16.sp)
            }
            Spacer(Modifier.width(8.dp))
        }

        Card(
            shape = if (isUser) {
                RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
            } else {
                RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
            },
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) Color(0xFF2563EB) else MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.fillMaxWidth(if (isUser) 0.82f else 0.88f),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                if (!isUser && message.actionBadge != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF3B82F6).copy(alpha = 0.2f),
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        Text(
                            message.actionBadge,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF93C5FD),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }

                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        lineHeight = 21.sp,
                        fontSize = 14.sp,
                    ),
                    color = if (isUser) Color.White else Color(0xFFF1F5F9),
                )

                if (!isUser) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onReplayVoice, modifier = Modifier.size(28.dp)) {
                            Text("🔊", fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                            Text("📋", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
