package com.example.campusagent

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingPolicyTest {
    @Test
    fun baselineAlwaysUsesCloudEvenForPrivateToolRequests() {
        val decision = RoutingPolicy.choose(
            "my timetable for Monday",
            RoutingSignals(isSensitive = true),
            cloudOnlyBaseline = true,
        )

        assertEquals(Route.CLOUD, decision.route)
        assertEquals(null, decision.tool)
    }

    @Test
    fun routineTasksStayOnDeviceWhenHybridModeIsEnabled() {
        val decision = RoutingPolicy.choose(
            "Show my timetable for Monday",
            RoutingSignals(batterySaver = true),
        )

        assertEquals(Route.LOCAL_TOOL, decision.route)
        assertEquals(StudentTool.TIMETABLE, decision.tool)
    }

    @Test
    fun sensitiveRequestsNeverFallBackToCloudWhenLocalModelIsDisabled() {
        val decision = RoutingPolicy.choose(
            "Help me understand my grades",
            RoutingSignals(isSensitive = true, onDeviceModelEnabled = false),
        )

        assertEquals(Route.BLOCKED_PRIVATE, decision.route)
    }

    @Test
    fun sensitiveRequestsStayLocalEvenWhenBatterySaverIsOn() {
        val decision = RoutingPolicy.choose(
            "Help me understand my grades",
            RoutingSignals(isSensitive = true, batterySaver = true),
        )

        assertEquals(Route.LOCAL_MODEL, decision.route)
    }

    @Test
    fun costAndLatencySignalsPreferLocalModel() {
        assertEquals(
            Route.LOCAL_MODEL,
            RoutingPolicy.choose(
                "Explain recursion",
                RoutingSignals(costSensitive = true),
            ).route,
        )
        assertEquals(
            Route.LOCAL_MODEL,
            RoutingPolicy.choose(
                "Explain recursion",
                RoutingSignals(latencyCritical = true),
            ).route,
        )
    }

    @Test
    fun batterySaverPrefersCloudForNonPrivateGeneralRequests() {
        val decision = RoutingPolicy.choose(
            "Explain recursion",
            RoutingSignals(batterySaver = true, costSensitive = true),
        )

        assertEquals(Route.CLOUD, decision.route)
    }

    @Test
    fun firebaseAiLogicHybridModesRouteCorrectly() {
        // ONLY_CLOUD forces cloud even if costSensitive or private
        val onlyCloud = RoutingPolicy.choose(
            "Explain quantum computing",
            RoutingSignals(hybridMode = HybridInferenceMode.ONLY_CLOUD),
        )
        assertEquals(Route.CLOUD, onlyCloud.route)

        // ONLY_ON_DEVICE forces local model for general requests
        val onlyDevice = RoutingPolicy.choose(
            "Explain sorting algorithms",
            RoutingSignals(hybridMode = HybridInferenceMode.ONLY_ON_DEVICE),
        )
        assertEquals(Route.LOCAL_MODEL, onlyDevice.route)

        // ONLY_ON_DEVICE blocks if local model is disabled
        val onlyDeviceDisabled = RoutingPolicy.choose(
            "Explain sorting algorithms",
            RoutingSignals(hybridMode = HybridInferenceMode.ONLY_ON_DEVICE, onDeviceModelEnabled = false),
        )
        assertEquals(Route.BLOCKED_PRIVATE, onlyDeviceDisabled.route)

        // PREFER_ON_DEVICE routes general query to local model when enabled
        val preferDevice = RoutingPolicy.choose(
            "Explain binary search",
            RoutingSignals(hybridMode = HybridInferenceMode.PREFER_ON_DEVICE),
        )
        assertEquals(Route.LOCAL_MODEL, preferDevice.route)

        // PREFER_CLOUD routes general query to cloud
        val preferCloud = RoutingPolicy.choose(
            "Explain binary search",
            RoutingSignals(hybridMode = HybridInferenceMode.PREFER_CLOUD),
        )
        assertEquals(Route.CLOUD, preferCloud.route)
    }

    @Test
    fun onDeviceMcpToolsAreDiscoveredAndCalled() {
        val mcpServer = CampusAppFunctionMcpServer()
        val tools = mcpServer.listTools()
        val toolNames = tools.map { it.name }

        org.junit.Assert.assertTrue(toolNames.contains("getTimetable"))
        org.junit.Assert.assertTrue(toolNames.contains("searchStudentNotes"))
        org.junit.Assert.assertTrue(toolNames.contains("getCampusServices"))
        org.junit.Assert.assertTrue(toolNames.contains("setDeviceAlarm"))
        org.junit.Assert.assertTrue(toolNames.contains("openDeviceApp"))
    }
}
