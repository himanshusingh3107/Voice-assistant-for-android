package com.example.campusagent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings

/**
 * Standard Model Context Protocol (MCP) data structures for on-device tool discovery and execution.
 * Enables this Android application to act as an on-device MCP Server via Android AppFunctions.
 */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>,
)

data class McpContent(
    val type: String = "text",
    val text: String,
)

data class McpToolCallResult(
    val content: List<McpContent>,
    val isError: Boolean = false,
) {
    val primaryText: String
        get() = content.firstOrNull()?.text ?: ""
}

/**
 * Interface defining an on-device MCP Server.
 */
interface OnDeviceMcpServer {
    fun listTools(): List<McpTool>
    suspend fun callTool(name: String, arguments: Map<String, Any?> = emptyMap()): McpToolCallResult
}

/**
 * Helper object providing robust Android system interactions:
 * dynamic app launching across all installed packages, alarm configuration, and web fallbacks.
 */
object DeviceActionHelper {
    private val KNOWN_APP_PACKAGES = mapOf(
        "youtube" to listOf("com.google.android.youtube"),
        "yt" to listOf("com.google.android.youtube"),
        "instagram" to listOf("com.instagram.android"),
        "insta" to listOf("com.instagram.android"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "chrome" to listOf("com.android.chrome"),
        "browser" to listOf("com.android.chrome"),
        "spotify" to listOf("com.spotify.music"),
        "music" to listOf("com.spotify.music", "com.google.android.apps.youtube.music"),
        "camera" to listOf("com.google.android.GoogleCamera", "com.oplus.camera", "com.coloros.camera"),
        "calculator" to listOf("com.google.android.calculator", "com.coloros.calculator", "com.android.calculator2", "com.sec.android.app.popupcalculator"),
        "maps" to listOf("com.google.android.apps.maps"),
        "google maps" to listOf("com.google.android.apps.maps"),
        "gmail" to listOf("com.google.android.gm"),
        "mail" to listOf("com.google.android.gm"),
        "play store" to listOf("com.android.vending"),
        "playstore" to listOf("com.android.vending"),
        "photos" to listOf("com.google.android.apps.photos", "com.coloros.gallery3d"),
        "gallery" to listOf("com.coloros.gallery3d", "com.google.android.apps.photos", "com.android.gallery3d"),
        "settings" to listOf("com.android.settings"),
        "telegram" to listOf("org.telegram.messenger"),
        "snapchat" to listOf("com.snapchat.android"),
        "twitter" to listOf("com.twitter.android"),
        "x" to listOf("com.twitter.android"),
        "facebook" to listOf("com.facebook.katana"),
        "linkedin" to listOf("com.linkedin.android"),
        "netflix" to listOf("com.netflix.mediaclient"),
        "amazon" to listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping"),
        "flipkart" to listOf("com.flipkart.android"),
        "uber" to listOf("com.ubercab"),
        "swiggy" to listOf("in.swiggy.android"),
        "zomato" to listOf("com.application.zomato"),
    )

    private val WEB_FALLBACKS = mapOf(
        "youtube" to "https://www.youtube.com",
        "yt" to "https://www.youtube.com",
        "instagram" to "https://www.instagram.com",
        "insta" to "https://www.instagram.com",
        "whatsapp" to "https://web.whatsapp.com",
        "spotify" to "https://open.spotify.com",
        "twitter" to "https://twitter.com",
        "x" to "https://x.com",
        "facebook" to "https://www.facebook.com",
        "netflix" to "https://www.netflix.com",
        "amazon" to "https://www.amazon.in",
        "flipkart" to "https://www.flipkart.com",
    )

    fun sanitize(query: String): String {
        return query.replace(Regex("[\\p{So}\\p{Sk}\\p{Cn}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun isAppLaunchIntent(query: String): Boolean {
        val lower = sanitize(query).lowercase().trim()
        val openTriggers = listOf("open ", "launch ", "start ", "run ", "kholo ", "chalao ")
        if (openTriggers.any { lower.startsWith(it) }) return true
        if (KNOWN_APP_PACKAGES.keys.any { lower == "open $it" || lower == it }) return true
        for (app in KNOWN_APP_PACKAGES.keys) {
            if (lower.contains("open $app") || lower.contains("launch $app") || lower.contains("kholo $app")) {
                return true
            }
        }
        return false
    }

    fun isAlarmIntent(query: String): Boolean {
        val lower = sanitize(query).lowercase().trim()
        return lower.contains("alarm") || lower.contains("wake me") || lower.contains("timer")
    }

    fun extractAppName(query: String): String {
        var text = sanitize(query).lowercase().trim()
        val removePrefixes = listOf(
            "please open ", "can you open ", "could you open ",
            "open the ", "open ", "launch the ", "launch ",
            "start the ", "start ", "run the ", "run ",
            "kholo ", "chalao ",
        )
        for (prefix in removePrefixes) {
            val idx = text.indexOf(prefix)
            if (idx != -1) {
                text = text.substring(idx + prefix.length).trim()
                break
            }
        }
        text = text.replace(" application", "")
            .replace(" app", "")
            .replace(" please", "")
            .trim()
        return text
    }

    fun openApp(context: Context, rawTarget: String): Pair<Boolean, String> {
        val target = extractAppName(rawTarget).lowercase().trim()
        val pm = context.packageManager

        // Settings Shortcut
        if (target == "settings") {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return Pair(true, "Opening Settings ⚙️")
            } catch (_: Exception) {}
        }

        // Camera Shortcut
        if (target == "camera") {
            try {
                val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return Pair(true, "Opening Camera 📸")
            } catch (_: Exception) {}
        }

        // 1. Check known packages
        val knownPkgs = KNOWN_APP_PACKAGES[target]
        if (knownPkgs != null) {
            for (pkg in knownPkgs) {
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return Pair(true, "Opening ${target.replaceFirstChar { it.uppercase() }} 🚀")
                }
            }
        }

        // 2. Dynamic lookup: Query all launcher activities
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolvedList = pm.queryIntentActivities(mainIntent, 0)
            val match = resolvedList.firstOrNull { ri ->
                val label = ri.loadLabel(pm).toString().lowercase()
                label == target || label.contains(target) || target.contains(label)
            }
            if (match != null) {
                val pkgName = match.activityInfo.packageName
                val launchIntent = pm.getLaunchIntentForPackage(pkgName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    val label = match.loadLabel(pm).toString()
                    return Pair(true, "Opening $label 🚀")
                }
            }
        } catch (_: Exception) {}

        // 3. Fallback: Web URL
        val webFallback = WEB_FALLBACKS[target]
        if (webFallback != null) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webFallback)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                return Pair(true, "Opening ${target.replaceFirstChar { it.uppercase() }} on Web 🌐")
            } catch (_: Exception) {}
        }

        // 4. Fallback: Search on Google Play Store
        return try {
            val playIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$target")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(playIntent)
            Pair(true, "Searching for '$target' in Play Store 🛒")
        } catch (_: Exception) {
            Pair(false, "Could not open '$target' on this device.")
        }
    }

    fun parseAndSetAlarm(context: Context, query: String): String {
        var hour = 7
        var minute = 0
        val isPm = query.contains("pm", ignoreCase = true) ||
            query.contains("night", ignoreCase = true) ||
            query.contains("evening", ignoreCase = true)

        val timeRegex = Regex("(\\d{1,2})[:.]?(\\d{2})?\\s*(am|pm)?", RegexOption.IGNORE_CASE)
        val match = timeRegex.find(query)
        if (match != null) {
            hour = match.groupValues[1].toIntOrNull() ?: 7
            minute = match.groupValues[2].toIntOrNull() ?: 0
            val ampm = match.groupValues[3].lowercase()
            if (ampm == "pm" && hour < 12) hour += 12
            else if (ampm == "am" && hour == 12) hour = 0
            else if (isPm && hour < 12) hour += 12
        }

        val message = "Campus Assistant Alarm"
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return "Alarm scheduled for %02d:%02d ⏰".format(hour, minute)
    }
}

/**
 * On-device MCP Server implementation backed by Android AppFunctions and StudentToolRegistry.
 */
class CampusAppFunctionMcpServer(
    private val context: Context? = null,
) : OnDeviceMcpServer {

    private val tools = listOf(
        McpTool(
            name = "getTimetable",
            description = "Fetch student class timetable and schedule for a specific day or entire week.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "day" to mapOf(
                        "type" to "string",
                        "description" to "Day of the week (e.g. monday, tuesday)",
                    ),
                ),
            ),
        ),
        McpTool(
            name = "searchStudentNotes",
            description = "Search student personal course notes by subject or keyword.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf(
                        "type" to "string",
                        "description" to "Subject or topic keyword (e.g. biology, calculus)",
                    ),
                ),
                "required" to listOf("query"),
            ),
        ),
        McpTool(
            name = "getCampusServices",
            description = "Get campus facilities and operating hours (Library, Student Services, Health Center).",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any?>(),
            ),
        ),
        McpTool(
            name = "setDeviceAlarm",
            description = "Set a local clock alarm on the Android device via native Intent.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "hour" to mapOf("type" to "integer", "description" to "Hour (0-23)"),
                    "minute" to mapOf("type" to "integer", "description" to "Minute (0-59)"),
                    "message" to mapOf("type" to "string", "description" to "Alarm label or query"),
                ),
            ),
        ),
        McpTool(
            name = "openDeviceApp",
            description = "Launch an installed application on the Android device by name or package.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "target" to mapOf("type" to "string", "description" to "App name or package to launch"),
                ),
                "required" to listOf("target"),
            ),
        ),
    )

    override fun listTools(): List<McpTool> = tools

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): McpToolCallResult {
        return try {
            when (name) {
                "getTimetable" -> {
                    val day = arguments["day"] as? String ?: ""
                    val output = StudentToolRegistry.run(StudentTool.TIMETABLE, day)
                    McpToolCallResult(listOf(McpContent(text = output)))
                }

                "searchStudentNotes" -> {
                    val query = arguments["query"] as? String ?: ""
                    val output = StudentToolRegistry.run(StudentTool.NOTES, query)
                    McpToolCallResult(listOf(McpContent(text = output)))
                }

                "getCampusServices" -> {
                    val output = StudentToolRegistry.run(StudentTool.CAMPUS_SERVICES, "")
                    McpToolCallResult(listOf(McpContent(text = output)))
                }

                "setDeviceAlarm" -> {
                    val message = arguments["message"] as? String ?: ""
                    if (context != null) {
                        val output = DeviceActionHelper.parseAndSetAlarm(context, message)
                        McpToolCallResult(listOf(McpContent(text = output)))
                    } else {
                        McpToolCallResult(listOf(McpContent(text = "Alarm set [Simulated]")))
                    }
                }

                "openDeviceApp" -> {
                    val target = arguments["target"] as? String
                        ?: arguments["packageName"] as? String
                        ?: ""
                    if (context != null && target.isNotBlank()) {
                        val (success, message) = DeviceActionHelper.openApp(context, target)
                        McpToolCallResult(listOf(McpContent(text = message)), isError = !success)
                    } else {
                        McpToolCallResult(listOf(McpContent(text = "Open app request validated for: $target")))
                    }
                }

                else -> McpToolCallResult(
                    content = listOf(McpContent(text = "Unknown MCP tool: $name")),
                    isError = true,
                )
            }
        } catch (e: Exception) {
            McpToolCallResult(
                content = listOf(McpContent(text = "Error executing MCP tool $name: ${e.message}")),
                isError = true,
            )
        }
    }
}
