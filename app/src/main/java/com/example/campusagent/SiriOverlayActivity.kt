package com.example.campusagent

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sound Manager producing Siri/Google Assistant style wake and completion audio chimes ("Tun Tun").
 */
object SoundManager {
    private var toneGen: ToneGenerator? = null

    init {
        try {
            toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        } catch (_: Exception) {}
    }

    fun playWakeChime() {
        try {
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 110)
            Handler(Looper.getMainLooper()).postDelayed({
                toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 130)
            }, 120)
        } catch (_: Exception) {}
    }

    fun playDoneChime() {
        try {
            toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 140)
        } catch (_: Exception) {}
    }
}

/**
 * Siri-like floating overlay Activity that slides up over any active app
 * when the wake-word ("Campus Gemini") is spoken.
 */
class SiriOverlayActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INITIAL_QUERY = "extra_initial_query"

        fun start(context: Context, initialQuery: String = "") {
            val intent = Intent(context, SiriOverlayActivity::class.java).apply {
                putExtra(EXTRA_INITIAL_QUERY, initialQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialQuery = intent?.getStringExtra(EXTRA_INITIAL_QUERY) ?: ""

        setContent {
            MaterialTheme {
                SiriFloatingOverlayContent(
                    initialQuery = initialQuery,
                    onClose = { finish() },
                )
            }
        }
    }
}

@Composable
fun SiriFloatingOverlayContent(
    initialQuery: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtime = remember(context) { CampusAgentRuntime(context) }

    var queryText by remember { mutableStateOf(initialQuery) }
    var answerText by remember { mutableStateOf("") }
    var actionBadgeText by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf("Listening...") }
    var isListening by remember { mutableStateOf(initialQuery.isBlank()) }
    var isThinking by remember { mutableStateOf(false) }

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

    fun speakAndDismiss(text: String, delayMs: Long = 3000) {
        SoundManager.playDoneChime()
        val clean = text.replace(Regex("[*#_`~]"), "")
        ttsInstance?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "SiriAnswer")
        scope.launch {
            delay(delayMs)
            onClose()
        }
    }

    fun processQuery(q: String) {
        if (q.isBlank()) return
        isListening = false
        isThinking = true
        statusText = "Thinking..."
        SoundManager.playDoneChime()

        scope.launch {
            val result = runtime.ask(q)
            isThinking = false
            when (result) {
                is AgentResult.Success -> {
                    answerText = result.answer
                    actionBadgeText = if (result.route == Route.LOCAL_TOOL) "⚡ Device Action" else "✨ Gemini AI"
                    statusText = "Done"
                }
                is AgentResult.Failure -> {
                    answerText = result.message
                    actionBadgeText = "⚠️ Status"
                    statusText = "Error"
                }
            }
            speakAndDismiss(answerText)
        }
    }

    LaunchedEffect(Unit) {
        SoundManager.playWakeChime()
        if (initialQuery.isNotBlank()) {
            processQuery(initialQuery)
        } else {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        statusText = "Listening to you..."
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        statusText = "Processing..."
                    }

                    override fun onError(error: Int) {
                        statusText = "Voice input error: $error"
                        speakAndDismiss("Could not hear you. Please try again.")
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spoken = matches?.firstOrNull()?.trim() ?: ""
                        if (spoken.isNotBlank()) {
                            queryText = spoken
                            processQuery(spoken)
                        } else {
                            speakAndDismiss("No command detected.")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spoken = matches?.firstOrNull()?.trim() ?: ""
                        if (spoken.isNotBlank()) {
                            queryText = spoken
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                recognizer.startListening(intent)
            } else {
                statusText = "Speech recognition unavailable"
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable { onClose() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Card(
            shape = RoundedCornerShape(28.dp, 28.dp, 0.dp, 0.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF0F172A),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false) {},
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Siri Orb Glowing Ring
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .scale(if (isListening) pulseScale else 1.0f)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF38BDF8),
                                    Color(0xFF818CF8),
                                    Color(0xFFC084FC),
                                ),
                            ),
                        )
                        .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✨", fontSize = 28.sp)
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF38BDF8),
                )

                if (queryText.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "\"$queryText\"",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }

                if (isThinking) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color(0xFF38BDF8),
                        strokeWidth = 2.dp,
                    )
                }

                if (answerText.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF1E293B),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            if (actionBadgeText != null) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF3B82F6).copy(alpha = 0.2f),
                                    modifier = Modifier.padding(bottom = 6.dp),
                                ) {
                                    Text(
                                        actionBadgeText!!,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF93C5FD),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                            Text(
                                text = answerText,
                                fontSize = 14.sp,
                                color = Color(0xFFF1F5F9),
                                lineHeight = 20.sp,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
