package com.example.campusagent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val DEFAULT_GEMINI_API_KEY = "AQ.Ab8RN6KzyfgVHAn6dJ39ITn3X7k2AM9X6_5FX0BPyxuWKihN4g"
private const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite"
private const val DEFAULT_NEWS_API_KEY = "4fbf1007129e49e1b0eab0895d8c8ed7"

/**
 * Checks active network state and internet reachability.
 */
object NetworkUtils {
    fun isConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val actNw = cm.getNetworkCapabilities(network) ?: return false
            actNw.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }
}

/**
 * Live News Headlines fetcher using NewsAPI.
 */
object NewsApiClient {
    fun isNewsQuery(query: String): Boolean {
        val lower = query.lowercase().trim()
        val triggers = listOf(
            "news", "headline", "headlines", "samachar", "khabar",
            "top stories", "breaking news", "todays news", "today news", "latest news",
        )
        return triggers.any { lower.contains(it) }
    }

    suspend fun fetchHeadlines(
        apiKey: String = DEFAULT_NEWS_API_KEY,
        query: String = "",
    ): String = withContext(Dispatchers.IO) {
        val lower = query.lowercase()
        val category = when {
            lower.contains("tech") || lower.contains("technology") -> "technology"
            lower.contains("business") || lower.contains("finance") || lower.contains("market") -> "business"
            lower.contains("sport") || lower.contains("cricket") || lower.contains("football") -> "sports"
            lower.contains("entertainment") || lower.contains("bollywood") || lower.contains("movie") -> "entertainment"
            lower.contains("science") -> "science"
            lower.contains("health") -> "health"
            else -> null
        }

        val urlString = if (category != null) {
            "https://newsapi.org/v2/top-headlines?category=$category&language=en&pageSize=5&apiKey=$apiKey"
        } else {
            "https://newsapi.org/v2/top-headlines?language=en&pageSize=5&apiKey=$apiKey"
        }

        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "CampusGeminiAssistant/1.0")
                connectTimeout = 10000
                readTimeout = 15000
            }

            val code = conn.responseCode
            if (code == 200) {
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(body)
                val articles = json.optJSONArray("articles")
                if (articles != null && articles.length() > 0) {
                    val sb = StringBuilder("📰 **Top News Headlines:**\n\n")
                    val count = minOf(articles.length(), 5)
                    for (i in 0 until count) {
                        val article = articles.getJSONObject(i)
                        val title = article.optString("title", "No Title")
                        val source = article.optJSONObject("source")?.optString("name", "News") ?: "News"
                        sb.append("${i + 1}. **$title**\n   _Source: ${source}_\n\n")
                    }
                    return@withContext sb.toString().trim()
                } else {
                    return@withContext "No news headlines found at the moment."
                }
            } else {
                throw RuntimeException("News API returned HTTP $code")
            }
        } catch (e: Exception) {
            throw e
        } finally {
            conn?.disconnect()
        }
    }
}

/**
 * Direct Google Gemini API Client using standard HTTPS with gemini-3.5-flash-lite.
 * Allows fast, responsive, and full LLM conversational capabilities like Gemini.
 */
object GeminiApiClient {
    suspend fun generateContent(
        apiKey: String,
        query: String,
        preferredModel: String = DEFAULT_GEMINI_MODEL,
    ): String = withContext(Dispatchers.IO) {
        val models = listOf(preferredModel, "gemini-2.0-flash", "gemini-1.5-flash")
        var lastException: Exception? = null

        for (model in models) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connectTimeout = 15000
                    readTimeout = 25000
                    doOutput = true
                }

                val systemPrompt = "You are an intelligent, friendly AI assistant running on Google Gemini 3.5 Flash Lite. " +
                    "You can answer all general knowledge questions (like 'what is python', science, history, coding), " +
                    "engage in normal friendly conversations, explain concepts, provide recommendations, " +
                    "and assist with campus information. Answer naturally in the user's preferred language (English or Hinglish). " +
                    "Keep answers concise, helpful, and formatted clearly."

                val rootObj = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().put("text", query))
                            })
                        })
                    })
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", systemPrompt))
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.7)
                        put("maxOutputTokens", 1024)
                    })
                }

                conn.outputStream.use { os ->
                    os.write(rootObj.toString().toByteArray(Charsets.UTF_8))
                }

                val code = conn.responseCode
                if (code == 200) {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val json = JSONObject(body)
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val firstCandidate = candidates.getJSONObject(0)
                        val content = firstCandidate.optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            val text = parts.getJSONObject(0).optString("text")
                            if (text.isNotBlank()) return@withContext text.trim()
                        }
                    }
                } else {
                    val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    lastException = RuntimeException("Gemini HTTP $code: $err")
                }
            } catch (e: Exception) {
                lastException = e
            } finally {
                conn?.disconnect()
            }
        }
        throw lastException ?: RuntimeException("Could not connect to Gemini API.")
    }
}

class CampusAgentRuntime(context: Context) {
    private val appContext = context.applicationContext
    val mcpServer: OnDeviceMcpServer = CampusAppFunctionMcpServer(appContext)

    private val localModel by lazy {
        Generation.getClient(
            generationConfig {
                modelConfig = modelConfig {
                    releaseStage = ModelReleaseStage.PREVIEW
                    preference = ModelPreference.FAST
                }
            },
        )
    }

    private val coordinator by lazy {
        CoordinatorAgent(
            campusAgent = CampusToolMcpAgent(mcpServer),
            deviceAgent = DeviceActionAgent(mcpServer),
            onDeviceAgent = OnDeviceModelAgent(),
            cloudAgent = CloudReasoningAgent(),
        )
    }

    suspend fun ask(
        query: String,
        signals: RoutingSignals = RoutingSignals(),
        cloudOnlyBaseline: Boolean = false,
    ): AgentResult {
        val startedAt = System.nanoTime()

        // 1. Direct device action checks (Open YouTube, Instagram, WhatsApp, Camera, Alarm, etc.)
        // These ALWAYS execute locally, even when internet is disconnected!
        if (DeviceActionHelper.isAlarmIntent(query)) {
            val alarmMsg = DeviceActionHelper.parseAndSetAlarm(appContext, query)
            return AgentResult.Success(
                answer = alarmMsg,
                route = Route.LOCAL_TOOL,
                reason = "Alarm set locally via Android system.",
                elapsedMs = elapsedMs(startedAt),
                privateDataSentToCloud = false,
            )
        }

        if (DeviceActionHelper.isAppLaunchIntent(query)) {
            val (_, launchMsg) = DeviceActionHelper.openApp(appContext, query)
            return AgentResult.Success(
                answer = launchMsg,
                route = Route.LOCAL_TOOL,
                reason = "Launched application on device.",
                elapsedMs = elapsedMs(startedAt),
                privateDataSentToCloud = false,
            )
        }

        // 2. Offline check: If internet is NOT connected, inform user while reminding of offline capabilities
        val online = NetworkUtils.isConnected(appContext)
        if (!online) {
            val offlineMsg = "Please connect to the internet to get answers to questions and news headlines.\n\n" +
                "I can still open your apps (like YouTube, Instagram, WhatsApp, Camera, Settings) or set alarms offline! 📱"
            return AgentResult.Failure(
                route = Route.BLOCKED_PRIVATE,
                reason = "Internet disconnected",
                message = offlineMsg,
                elapsedMs = elapsedMs(startedAt),
                privateDataSentToCloud = false,
            )
        }

        // 3. Live News Headlines API
        if (NewsApiClient.isNewsQuery(query)) {
            return try {
                val prefs = appContext.getSharedPreferences("campus_settings", Context.MODE_PRIVATE)
                val newsKey = prefs.getString("news_api_key", null)?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_NEWS_API_KEY
                val newsText = NewsApiClient.fetchHeadlines(newsKey, query)
                AgentResult.Success(
                    answer = newsText,
                    route = Route.CLOUD,
                    reason = "Live News Headlines via NewsAPI.",
                    elapsedMs = elapsedMs(startedAt),
                    privateDataSentToCloud = false,
                )
            } catch (newsErr: Exception) {
                // If NewsAPI call fails, pass to Gemini as fallback
                val geminiAnswer = requestCloud(query)
                AgentResult.Success(
                    answer = geminiAnswer,
                    route = Route.CLOUD,
                    reason = "Answered by Gemini 3.5 Flash Lite.",
                    elapsedMs = elapsedMs(startedAt),
                    privateDataSentToCloud = true,
                )
            }
        }

        // 4. Policy-based routing for campus tools vs LLM inference
        val decision = RoutingPolicy.choose(query, signals, cloudOnlyBaseline)

        return try {
            val answer = when (decision.route) {
                Route.CLOUD -> {
                    try {
                        requestCloud(query)
                    } catch (cloudErr: Exception) {
                        if (signals.hybridMode == HybridInferenceMode.PREFER_CLOUD && signals.onDeviceModelEnabled) {
                            requestLocal(query)
                        } else {
                            throw cloudErr
                        }
                    }
                }

                Route.LOCAL_TOOL -> {
                    val studentTool = decision.tool ?: StudentToolRegistry.match(query)
                    if (studentTool != null) {
                        StudentToolRegistry.run(studentTool, query)
                    } else {
                        requestCloud(query)
                    }
                }

                Route.LOCAL_MODEL -> {
                    try {
                        requestLocal(query)
                    } catch (localErr: Exception) {
                        if (signals.hybridMode == HybridInferenceMode.PREFER_ON_DEVICE && !signals.isSensitive) {
                            requestCloud(query)
                        } else {
                            throw localErr
                        }
                    }
                }

                Route.BLOCKED_PRIVATE -> error(decision.reason)
            }

            AgentResult.Success(
                answer = answer,
                route = decision.route,
                reason = decision.reason,
                elapsedMs = elapsedMs(startedAt),
                privateDataSentToCloud = decision.route == Route.CLOUD &&
                    (signals.isSensitive || StudentToolRegistry.isSensitive(query)),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AgentResult.Failure(
                route = decision.route,
                reason = decision.reason,
                message = failure.message ?: "The selected inference route failed.",
                elapsedMs = elapsedMs(startedAt),
                privateDataSentToCloud = decision.route == Route.CLOUD &&
                    (signals.isSensitive || StudentToolRegistry.isSensitive(query)),
            )
        }
    }

    /**
     * Executes the query through the Android ADK multi-agent workflow.
     */
    suspend fun askWorkflow(query: String, signals: RoutingSignals): MultiAgentResponse {
        val workflowContext = AgentWorkflowContext(
            signals = signals,
            mcpServer = mcpServer,
            cloudInference = { prompt -> requestCloud(prompt) },
            localInference = { prompt -> requestLocal(prompt) },
        )
        return coordinator.coordinate(query, workflowContext)
    }

    private suspend fun requestLocal(query: String): String = withContext(Dispatchers.IO) {
        try {
            val response = localModel.generateContent(
                "You are a concise, helpful assistant. Answer briefly:\n\n$query",
            )
            response.candidates.firstOrNull()?.text
                ?: error("On-device model returned no response.")
        } catch (_: Exception) {
            "I'm currently running in offline mode. I can still open your apps (YouTube, Instagram, Camera), set alarms, and give you your class timetable and notes!"
        }
    }

    private suspend fun requestCloud(query: String): String = withContext(Dispatchers.IO) {
        val prefs = appContext.getSharedPreferences("campus_settings", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_GEMINI_API_KEY

        val modelName = prefs.getString("gemini_model", null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_GEMINI_MODEL

        try {
            GeminiApiClient.generateContent(apiKey, query, modelName)
        } catch (geminiErr: Exception) {
            // Secondary fallback to Firebase AI Logic if configured
            if (FirebaseApp.getApps(appContext).isNotEmpty()) {
                val model = Firebase.ai(backend = GenerativeBackend.googleAI())
                    .generativeModel(modelName = modelName)
                model.generateContent(query).text
                    ?: error("Firebase AI Logic returned no response.")
            } else {
                throw geminiErr
            }
        }
    }

    private fun elapsedMs(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000
}

sealed interface AgentResult {
    val route: Route
    val reason: String
    val elapsedMs: Long
    val privateDataSentToCloud: Boolean

    data class Success(
        val answer: String,
        override val route: Route,
        override val reason: String,
        override val elapsedMs: Long,
        override val privateDataSentToCloud: Boolean,
    ) : AgentResult

    data class Failure(
        override val route: Route,
        override val reason: String,
        val message: String,
        override val elapsedMs: Long,
        override val privateDataSentToCloud: Boolean,
    ) : AgentResult
}

@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "CampusAppFunctionService",
    appFunctionXmlFileName = "campus_app_functions",
)
abstract class BaseCampusAppFunctionService : AppFunctionService() {
    @AppFunctionDeclaration(isDescribedByKDoc = false)
    suspend fun getTimetable(day: String): String =
        StudentToolRegistry.run(StudentTool.TIMETABLE, day)

    @AppFunctionDeclaration(isDescribedByKDoc = false)
    suspend fun searchStudentNotes(query: String): String =
        StudentToolRegistry.run(StudentTool.NOTES, query)

    @AppFunctionDeclaration(isDescribedByKDoc = false)
    suspend fun getCampusServices(): String =
        StudentToolRegistry.run(StudentTool.CAMPUS_SERVICES, "")
}
