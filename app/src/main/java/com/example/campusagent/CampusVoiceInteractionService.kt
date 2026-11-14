package com.example.campusagent

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Android system-level Digital Assistant Service.
 * Allows this app to be selected as the default assistant in Android settings,
 * enabling activation via power button, home button long-press, or lock-screen.
 */
class CampusVoiceInteractionService : VoiceInteractionService()

class CampusVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return CampusVoiceSession(this)
    }
}

class CampusRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // Recognition callback placeholder
    }

    override fun onCancel(listener: Callback?) {
    }

    override fun onStopListening(listener: Callback?) {
    }
}

/**
 * The Siri-like floating overlay session that appears over any screen
 * when the user holds the power button or home button.
 */
class CampusVoiceSession(context: Context) : VoiceInteractionSession(context) {

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val runtime = CampusAgentRuntime(context)
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private lateinit var statusTextView: TextView
    private lateinit var queryTextView: TextView
    private lateinit var responseTextView: TextView

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en", "IN")
            }
        }
    }

    override fun onCreateContentView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 64)
            setBackgroundColor(Color.parseColor("#101824"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            )
        }

        statusTextView = TextView(context).apply {
            text = "🎙️ Listening... (Speak in English or Hinglish)"
            textSize = 14f
            setTextColor(Color.parseColor("#55e0b0"))
            setPadding(0, 0, 0, 16)
        }

        queryTextView = TextView(context).apply {
            text = ""
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }

        responseTextView = TextView(context).apply {
            text = ""
            textSize = 15f
            setTextColor(Color.parseColor("#bdcadd"))
        }

        root.addView(statusTextView)
        root.addView(queryTextView)
        root.addView(responseTextView)
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        queryTextView.text = ""
        responseTextView.text = ""
        statusTextView.text = "🎙️ Listening... Speak to Campus Assistant"

        startListening()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            statusTextView.text = "Speech recognition unavailable on this device."
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {
                    statusTextView.text = "🎙️ Listening to you..."
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    statusTextView.text = "⚡ Thinking..."
                }
                override fun onError(error: Int) {
                    statusTextView.text = "Voice error: $error"
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()
                    if (!text.isNullOrBlank()) {
                        queryTextView.text = "You: $text"
                        processQuery(text)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()
                    if (!text.isNullOrBlank()) {
                        queryTextView.text = "You: $text..."
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun processQuery(query: String) {
        sessionScope.launch {
            statusTextView.text = "✨ Thinking with Gemini..."
            val result = runtime.ask(query)
            val answer = when (result) {
                is AgentResult.Success -> result.answer
                is AgentResult.Failure -> result.message
            }
            statusTextView.text = "✨ Done"
            responseTextView.text = answer

            // Speak response aloud
            val cleanSpeech = answer.replace(Regex("[*#_`~]"), "")
            tts?.speak(cleanSpeech, TextToSpeech.QUEUE_FLUSH, null, "AssistantAnswer")
        }
    }

    override fun onHide() {
        super.onHide()
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionScope.cancel()
        tts?.shutdown()
    }
}
