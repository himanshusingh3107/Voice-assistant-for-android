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

    fun isWebSearchIntent(query: String): Boolean {
        val lower = sanitize(query).lowercase().trim()
        val triggers = listOf(
            "google search", "web search", "search on google",
            "search google", "search the web", "open google and search",
            "search on web", "google for",
        )
        if (triggers.any { lower.startsWith(it) }) return true
        if (lower.endsWith(" on google") || lower.endsWith(" on web")) return true
        if (lower.contains("search ") && (lower.contains("google") || lower.contains("web browser"))) return true
        return false
    }

    fun isComplexActionIntent(query: String): Boolean {
        val lower = sanitize(query).lowercase().trim()
        if (isWebSearchIntent(lower)) return true

        val apps = listOf("youtube", "yt", "instagram", "insta", "maps", "google maps", "spotify", "whatsapp")
        val actions = listOf("search", "play", "find", "follow", "listen", "chalao", "dhundo", "baja")

        val hasApp = apps.any { lower.contains(it) }
        val hasAction = actions.any { lower.contains(it) }

        if (hasApp && hasAction) return true
        if (lower.contains(" and search ") || lower.contains(" and play ") || lower.contains(" and follow ")) return true
        return false
    }

    fun isAppLaunchIntent(query: String): Boolean {
        val lower = sanitize(query).lowercase().trim()
        if (isComplexActionIntent(lower) || isWebSearchIntent(lower)) return true
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

    fun executeWebSearch(context: Context, query: String): Pair<Boolean, String> {
        val lower = sanitize(query).lowercase().trim()
        var searchTerm = lower
        val removePrefixes = listOf(
            "open google and search for ", "open google and search ",
            "search on google for ", "search on google ", "search google for ",
            "search google ", "google search for ", "google search ",
            "web search for ", "web search ", "search for ", "search ",
        )
        for (prefix in removePrefixes) {
            if (searchTerm.startsWith(prefix)) {
                searchTerm = searchTerm.substring(prefix.length).trim()
                break
            }
        }
        searchTerm = searchTerm.replace(" on google", "")
            .replace(" on web", "")
            .trim()
            .ifBlank { query.trim() }

        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, searchTerm)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Pair(true, "Searching Google for '$searchTerm' 🌐🔍")
        } catch (_: Exception) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(searchTerm)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                Pair(true, "Searching Google for '$searchTerm' 🌐🔍")
            } catch (_: Exception) {
                Pair(false, "Could not perform web search.")
            }
        }
    }

    fun executeComplexAction(context: Context, query: String): Pair<Boolean, String> {
        val raw = sanitize(query)
        val lower = raw.lowercase().trim()

        // 1. Web Search
        if (isWebSearchIntent(lower)) {
            return executeWebSearch(context, query)
        }

        // 2. YouTube Search or Play
        if (lower.contains("youtube") || lower.contains("yt")) {
            val isPlay = lower.contains("play") || lower.contains("chalao") || lower.contains("baja")
            var target = lower
            val youtubeCleanups = listOf(
                "open youtube and play ", "open youtube and search ",
                "open youtube to play ", "open youtube to search ",
                "open youtube and ", "open youtube ", "play ", "search for ",
                "search ", "chalao ", "baja ", "on youtube", "on yt", "in youtube",
            )
            for (clean in youtubeCleanups) {
                target = target.replace(clean, "")
            }
            target = target.trim().ifBlank { "music" }

            return try {
                val searchUrl = "https://www.youtube.com/results?search_query=${Uri.encode(target)}"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val pm = context.packageManager
                if (pm.getLaunchIntentForPackage("com.google.android.youtube") != null) {
                    intent.setPackage("com.google.android.youtube")
                }
                context.startActivity(intent)
                val actionLabel = if (isPlay) "to play '$target' 🎬▶️" else "& searching for '$target' 🎬🔍"
                Pair(true, "Opening YouTube $actionLabel")
            } catch (_: Exception) {
                Pair(false, "Could not open YouTube for '$target'.")
            }
        }

        // 3. Instagram Profile / Search / Follow
        if (lower.contains("instagram") || lower.contains("insta")) {
            val isFollow = lower.contains("follow")
            var username = lower
            val instaCleanups = listOf(
                "open instagram and search ", "open instagram and follow ",
                "open instagram and find ", "open instagram and ",
                "open instagram ", "search witness.31 and follow that account",
                "search for ", "search ", "follow that account", "and follow that account",
                "and follow ", "follow ", "account ", "profile ", "on instagram",
                "on insta", "in instagram", "account", "profile",
            )
            for (clean in instaCleanups) {
                username = username.replace(clean, "")
            }
            username = username.replace("@", "").trim()
            if (username.contains(" ")) {
                username = username.split("\\s+".toRegex()).firstOrNull { it.isNotBlank() } ?: username
            }

            if (username.isBlank()) username = "instagram"

            return try {
                val appUri = Uri.parse("instagram://user?username=$username")
                val nativeIntent = Intent(Intent.ACTION_VIEW, appUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage("com.instagram.android")
                }
                val pm = context.packageManager
                if (pm.getLaunchIntentForPackage("com.instagram.android") != null) {
                    context.startActivity(nativeIntent)
                } else {
                    val webUri = Uri.parse("https://www.instagram.com/$username/")
                    val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(webIntent)
                }
                val text = if (isFollow) {
                    "Opening Instagram profile '@$username' 📸\nTap 'Follow' on their profile to follow them!"
                } else {
                    "Opening Instagram profile '@$username' 📸"
                }
                Pair(true, text)
            } catch (_: Exception) {
                try {
                    val webUri = Uri.parse("https://www.instagram.com/$username/")
                    val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(webIntent)
                    Pair(true, "Opening Instagram profile '@$username' 📸")
                } catch (_: Exception) {
                    Pair(false, "Could not open Instagram profile for '@$username'.")
                }
            }
        }

        // 4. Google Maps Search
        if (lower.contains("maps") || lower.contains("google maps")) {
            var target = lower
            val mapCleanups = listOf(
                "open google maps and search ", "open maps and search ",
                "open maps and ", "open maps ", "search for ", "search ",
                "on google maps", "on maps", "maps",
            )
            for (clean in mapCleanups) {
                target = target.replace(clean, "")
            }
            target = target.trim().ifBlank { "nearby places" }

            return try {
                val mapsUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(target)}")
                val intent = Intent(Intent.ACTION_VIEW, mapsUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Pair(true, "Opening Google Maps & searching for '$target' 🗺️")
            } catch (_: Exception) {
                Pair(false, "Could not open Google Maps.")
            }
        }

        // 5. Spotify Search or Play
        if (lower.contains("spotify")) {
            var target = lower
            val spotifyCleanups = listOf(
                "open spotify and play ", "open spotify and search ",
                "open spotify ", "play ", "search for ", "search ",
                "on spotify", "in spotify", "spotify",
            )
            for (clean in spotifyCleanups) {
                target = target.replace(clean, "")
            }
            target = target.trim().ifBlank { "music" }

            return try {
                val spotifyUri = Uri.parse("https://open.spotify.com/search/${Uri.encode(target)}")
                val intent = Intent(Intent.ACTION_VIEW, spotifyUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Pair(true, "Opening Spotify & searching for '$target' 🎵")
            } catch (_: Exception) {
                Pair(false, "Could not open Spotify.")
            }
        }

        // Fallback to basic openApp
        return openApp(context, raw)
    }

    fun openApp(context: Context, rawTarget: String): Pair<Boolean, String> {
        if (isComplexActionIntent(rawTarget) || isWebSearchIntent(rawTarget)) {
            return executeComplexAction(context, rawTarget)
        }
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
        McpTool(
            name = "webSearch",
            description = "Perform a Google web search on device for a specific query.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf("type" to "string", "description" to "Web search query"),
                ),
                "required" to listOf("query"),
            ),
        ),
        McpTool(
            name = "performAppSearchAction",
            description = "Perform complex deep app actions like opening YouTube to search/play songs or Instagram to search/follow accounts.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf("type" to "string", "description" to "Deep action query"),
                ),
                "required" to listOf("query"),
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

                "webSearch" -> {
                    val query = arguments["query"] as? String ?: ""
                    if (context != null && query.isNotBlank()) {
                        val (_, message) = DeviceActionHelper.executeWebSearch(context, query)
                        McpToolCallResult(listOf(McpContent(text = message)))
                    } else {
                        McpToolCallResult(listOf(McpContent(text = "Web search query validated for: $query")))
                    }
                }

                "performAppSearchAction" -> {
                    val query = arguments["query"] as? String ?: ""
                    if (context != null && query.isNotBlank()) {
                        val (_, message) = DeviceActionHelper.executeComplexAction(context, query)
                        McpToolCallResult(listOf(McpContent(text = message)))
                    } else {
                        McpToolCallResult(listOf(McpContent(text = "Deep app search action validated for: $query")))
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
