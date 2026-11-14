package com.example.campusagent

enum class Route {
    CLOUD,
    LOCAL_TOOL,
    LOCAL_MODEL,
    BLOCKED_PRIVATE,
}

/**
 * Firebase AI Logic hybrid inference modes governing on-device vs. cloud placement.
 */
enum class HybridInferenceMode {
    /** Tries on-device model first, falls back to cloud if unavailable or insufficient. */
    PREFER_ON_DEVICE,

    /** Tries cloud generative model first, falls back to local on-device model if offline. */
    PREFER_CLOUD,

    /** Strictly executes on-device only; fails rather than sending prompts to the cloud. */
    ONLY_ON_DEVICE,

    /** Always routes to the cloud backend (Baseline A). */
    ONLY_CLOUD,
}

enum class StudentTool {
    TIMETABLE,
    NOTES,
    CAMPUS_SERVICES,
}

data class RoutingSignals(
    val isSensitive: Boolean = false,
    val latencyCritical: Boolean = false,
    val costSensitive: Boolean = false,
    val batterySaver: Boolean = false,
    val onDeviceModelEnabled: Boolean = true,
    val hybridMode: HybridInferenceMode = HybridInferenceMode.PREFER_CLOUD,
)

data class RoutingDecision(
    val route: Route,
    val tool: StudentTool? = null,
    val reason: String,
)

object StudentToolRegistry {
    fun match(query: String): StudentTool? {
        val text = query.lowercase()
        return when {
            listOf("timetable", "schedule", "class time", "lecture").any(text::contains) ->
                StudentTool.TIMETABLE
            listOf("my notes", "note", "notes", "notebook").any(text::contains) ->
                StudentTool.NOTES
            listOf("campus", "library", "cafeteria", "health center", "student services")
                .any(text::contains) -> StudentTool.CAMPUS_SERVICES
            else -> null
        }
    }

    fun isSensitive(query: String): Boolean {
        val text = query.lowercase()
        return listOf(
            "my grade", "my grades", "my account", "my password", "my health",
            "my advisor", "my notes", "my timetable", "my schedule", "student id",
            "student-id", "social security", "medical record", "phone number",
            "home address", "financial aid", "my diagnosis",
        ).any(text::contains)
    }

    fun run(tool: StudentTool, query: String): String = when (tool) {
        StudentTool.TIMETABLE -> timetable(query)
        StudentTool.NOTES -> notes(query)
        StudentTool.CAMPUS_SERVICES -> campusServices()
    }

    private fun timetable(query: String): String {
        val days = mapOf(
            "monday" to "Monday: 9:00 AM Calculus (Hall A); 11:00 AM Computer Science (Lab 2).",
            "tuesday" to "Tuesday: 10:00 AM Physics (Room 104); 1:00 PM Writing Seminar (Room 8).",
            "wednesday" to "Wednesday: 9:00 AM Calculus (Hall A); 2:00 PM Biology (Science Center).",
            "thursday" to "Thursday: 10:00 AM Physics (Room 104); 1:00 PM Computer Science (Lab 2).",
            "friday" to "Friday: 11:00 AM Computer Science discussion (Lab 2).",
        )
        val day = days.entries.firstOrNull { query.contains(it.key, ignoreCase = true) }
        return day?.value ?: days.values.joinToString(separator = "\n")
    }

    private fun notes(query: String): String {
        val notes = listOf(
            "Biology: Review cell structure before the next lab.",
            "Calculus: Problem set 4 is due Friday.",
        )
        val keyword = query.substringAfter("about", "").trim()
        val matching = notes.filter { keyword.isBlank() || it.contains(keyword, ignoreCase = true) }
        return matching.ifEmpty { listOf("No matching notes in the local demo notebook.") }
            .joinToString(separator = "\n")
    }

    private fun campusServices(): String =
        "Campus services (demo directory): Library 8 AM-10 PM; Student Services 9 AM-5 PM; " +
            "Health Center 8 AM-4 PM. Check your campus directory for current hours."
}

object RoutingPolicy {
    fun choose(
        query: String,
        signals: RoutingSignals,
        cloudOnlyBaseline: Boolean = false,
    ): RoutingDecision {
        if (cloudOnlyBaseline || signals.hybridMode == HybridInferenceMode.ONLY_CLOUD) {
            val reason = if (signals.hybridMode == HybridInferenceMode.ONLY_CLOUD) {
                "Firebase AI Logic ONLY_CLOUD mode: request routed directly to cloud."
            } else {
                "Baseline A always routes every query to cloud."
            }
            return RoutingDecision(Route.CLOUD, reason = reason)
        }

        val tool = StudentToolRegistry.match(query)
        if (tool != null) {
            return RoutingDecision(Route.LOCAL_TOOL, tool, "Handled by a local student tool via on-device MCP.")
        }

        if (DeviceActionHelper.isAppLaunchIntent(query) || DeviceActionHelper.isAlarmIntent(query)) {
            return RoutingDecision(Route.LOCAL_TOOL, reason = "Handled by on-device system launcher.")
        }

        if (signals.isSensitive) {
            return if (signals.onDeviceModelEnabled) {
                RoutingDecision(Route.LOCAL_MODEL, reason = "Private request stays on this device.")
            } else {
                RoutingDecision(Route.BLOCKED_PRIVATE, reason = "Private request was not sent to cloud.")
            }
        }

        if (signals.hybridMode == HybridInferenceMode.ONLY_ON_DEVICE) {
            return if (signals.onDeviceModelEnabled) {
                RoutingDecision(Route.LOCAL_MODEL, reason = "Firebase AI Logic ONLY_ON_DEVICE mode: strictly routed to local model.")
            } else {
                RoutingDecision(Route.BLOCKED_PRIVATE, reason = "ONLY_ON_DEVICE mode active but on-device model is unavailable.")
            }
        }

        if (signals.batterySaver) {
            return RoutingDecision(Route.CLOUD, reason = "Battery saver favors cloud inference.")
        }

        if (signals.latencyCritical || signals.costSensitive) {
            return if (signals.onDeviceModelEnabled) {
                val reason = if (signals.latencyCritical) {
                    "Latency-sensitive request routed to the on-device model."
                } else {
                    "Cost-sensitive request routed to the on-device model."
                }
                RoutingDecision(Route.LOCAL_MODEL, reason = reason)
            } else {
                RoutingDecision(Route.CLOUD, reason = "On-device model is disabled; using cloud.")
            }
        }

        return when (signals.hybridMode) {
            HybridInferenceMode.PREFER_ON_DEVICE -> {
                if (signals.onDeviceModelEnabled) {
                    RoutingDecision(Route.LOCAL_MODEL, reason = "Firebase AI Logic PREFER_ON_DEVICE mode: routed to local model.")
                } else {
                    RoutingDecision(Route.CLOUD, reason = "PREFER_ON_DEVICE: local model disabled; falling back to cloud.")
                }
            }
            HybridInferenceMode.PREFER_CLOUD -> {
                RoutingDecision(Route.CLOUD, reason = "Firebase AI Logic PREFER_CLOUD mode: routed to cloud with local fallback.")
            }
            HybridInferenceMode.ONLY_ON_DEVICE -> {
                if (signals.onDeviceModelEnabled) {
                    RoutingDecision(Route.LOCAL_MODEL, reason = "ONLY_ON_DEVICE mode: strictly routed to local model.")
                } else {
                    RoutingDecision(Route.BLOCKED_PRIVATE, reason = "ONLY_ON_DEVICE mode active but on-device model is unavailable.")
                }
            }
            HybridInferenceMode.ONLY_CLOUD -> {
                RoutingDecision(Route.CLOUD, reason = "ONLY_CLOUD mode: routed to cloud.")
            }
        }
    }
}
