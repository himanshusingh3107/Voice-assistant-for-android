package com.example.campusagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Background Foreground Service enabling continuous voice wake-word detection ("Campus Gemini").
 * Runs in the background with a persistent notification and listens for voice commands.
 */
class CampusWakeWordService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private lateinit var runtime: CampusAgentRuntime
    private var isListening = false
    private var isProcessing = false

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val CHANNEL_ID = "campus_wake_word_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START_WAKE_WORD"
        const val ACTION_STOP = "ACTION_STOP_WAKE_WORD"

        fun start(context: Context) {
            val intent = Intent(context, CampusWakeWordService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CampusWakeWordService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        runtime = CampusAgentRuntime(this)
        createNotificationChannel()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en", "IN")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListening()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification("Listening for 'Campus Gemini' 🎙️")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startListeningLoop()
        return START_STICKY
    }

    private fun startListeningLoop() {
        if (isListening || isProcessing) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        isListening = false
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        scheduleRestart()
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spokenText = matches?.firstOrNull()?.trim() ?: ""
                        handleSpokenText(spokenText)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partialText = matches?.firstOrNull()?.trim() ?: ""
                        if (containsWakeWord(partialText)) {
                            speechRecognizer?.stopListening()
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
        } catch (_: Exception) {
            scheduleRestart()
        }
    }

    private fun containsWakeWord(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("campus gemini") ||
            lower.contains("hey gemini") ||
            lower.contains("ok gemini") ||
            lower.contains("campus") ||
            lower.contains("gemini")
    }

    private fun handleSpokenText(spokenText: String) {
        val lower = spokenText.lowercase()
        if (containsWakeWord(lower)) {
            isProcessing = true
            updateNotification("Wake-word detected! Processing request...")

            var command = lower
            val triggers = listOf("campus gemini", "hey gemini", "ok gemini", "campus", "gemini")
            for (trigger in triggers) {
                if (command.contains(trigger)) {
                    command = command.substringAfter(trigger).trim()
                    break
                }
            }

            if (command.isBlank()) {
                tts?.speak("Yes! How can I help you?", TextToSpeech.QUEUE_FLUSH, null, "WakeAck")
                scheduleRestart(2000)
                isProcessing = false
            } else {
                serviceScope.launch {
                    val result = runtime.ask(command)
                    val answer = when (result) {
                        is AgentResult.Success -> result.answer
                        is AgentResult.Failure -> result.message
                    }
                    val cleanSpeech = answer.replace(Regex("[*#_`~]"), "")
                    tts?.speak(cleanSpeech, TextToSpeech.QUEUE_FLUSH, null, "AssistantResponse")
                    updateNotification("Listening for 'Campus Gemini' 🎙️")
                    isProcessing = false
                    scheduleRestart(3000)
                }
            }
        } else {
            scheduleRestart(800)
        }
    }

    private fun scheduleRestart(delayMs: Long = 800) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            startListeningLoop()
        }, delayMs)
    }

    private fun stopListening() {
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        isListening = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Campus Wake-Word Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Runs background wake-word listening for Campus Gemini"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Campus Gemini Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        serviceScope.cancel()
        tts?.shutdown()
    }
}
