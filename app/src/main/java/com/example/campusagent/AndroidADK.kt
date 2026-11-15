package com.example.campusagent

/**
 * Agent Development Kit (ADK) for Android.
 * Enables building autonomous, multi-agent workflows directly on Android devices.
 */

data class AgentStep(
    val agentName: String,
    val role: String,
    val action: String,
    val output: String,
    val elapsedMs: Long,
)

data class MultiAgentResponse(
    val finalAnswer: String,
    val steps: List<AgentStep>,
    val totalElapsedMs: Long,
)

data class AgentWorkflowContext(
    val signals: RoutingSignals,
    val mcpServer: OnDeviceMcpServer,
    val cloudInference: (suspend (String) -> String)? = null,
    val localInference: (suspend (String) -> String)? = null,
)

interface AdkAgent {
    val name: String
    val role: String
    val description: String
    suspend fun canHandle(query: String): Boolean
    suspend fun execute(query: String, context: AgentWorkflowContext): String
}

/**
 * Specialist agent for local student schedule, notes, and campus services via MCP.
 */
class CampusToolMcpAgent(
    private val mcpServer: OnDeviceMcpServer,
) : AdkAgent {
    override val name: String = "CampusMcpAgent"
    override val role: String = "On-Device Tool Specialist"
    override val description: String = "Executes campus queries using on-device MCP AppFunctions."

    override suspend fun canHandle(query: String): Boolean {
        return StudentToolRegistry.match(query) != null
    }

    override suspend fun execute(query: String, context: AgentWorkflowContext): String {
        val tool = StudentToolRegistry.match(query) ?: return "No matching campus tool found."
        val mcpToolName = when (tool) {
            StudentTool.TIMETABLE -> "getTimetable"
            StudentTool.NOTES -> "searchStudentNotes"
            StudentTool.CAMPUS_SERVICES -> "getCampusServices"
        }
        val args = when (tool) {
            StudentTool.TIMETABLE -> mapOf("day" to query)
            StudentTool.NOTES -> mapOf("query" to query)
            StudentTool.CAMPUS_SERVICES -> emptyMap()
        }
        val result = mcpServer.callTool(mcpToolName, args)
        return result.primaryText
    }
}

/**
 * Specialist agent for device-level actions (alarms, opening apps).
 */
class DeviceActionAgent(
    private val mcpServer: OnDeviceMcpServer,
) : AdkAgent {
    override val name: String = "DeviceActionAgent"
    override val role: String = "Device Action Specialist"
    override val description: String = "Performs on-device system actions like setting alarms, launching apps, web search, or deep app search actions."

    override suspend fun canHandle(query: String): Boolean {
        return DeviceActionHelper.isAppLaunchIntent(query) ||
            DeviceActionHelper.isAlarmIntent(query) ||
            DeviceActionHelper.isWebSearchIntent(query) ||
            DeviceActionHelper.isComplexActionIntent(query)
    }

    override suspend fun execute(query: String, context: AgentWorkflowContext): String {
        return when {
            DeviceActionHelper.isAlarmIntent(query) -> {
                val result = mcpServer.callTool("setDeviceAlarm", mapOf("message" to query))
                result.primaryText
            }
            DeviceActionHelper.isWebSearchIntent(query) -> {
                val result = mcpServer.callTool("webSearch", mapOf("query" to query))
                result.primaryText
            }
            DeviceActionHelper.isComplexActionIntent(query) -> {
                val result = mcpServer.callTool("performAppSearchAction", mapOf("query" to query))
                result.primaryText
            }
            else -> {
                val appTarget = DeviceActionHelper.extractAppName(query)
                val result = mcpServer.callTool("openDeviceApp", mapOf("target" to appTarget))
                result.primaryText
            }
        }
    }
}

/**
 * Specialist agent for deep reasoning and cloud generation via Firebase AI Logic.
 */
class CloudReasoningAgent : AdkAgent {
    override val name: String = "CloudReasoningAgent"
    override val role: String = "Cloud Reasoning Specialist"
    override val description: String = "Performs deep academic reasoning and synthesis using Firebase AI Logic."

    override suspend fun canHandle(query: String): Boolean = true

    override suspend fun execute(query: String, context: AgentWorkflowContext): String {
        return context.cloudInference?.invoke(query)
            ?: "Cloud reasoning is unavailable: Firebase AI Logic not configured."
    }
}

/**
 * Specialist agent for on-device reasoning and privacy preservation via ML Kit.
 */
class OnDeviceModelAgent : AdkAgent {
    override val name: String = "OnDeviceModelAgent"
    override val role: String = "On-Device LLM Specialist"
    override val description: String = "Performs local inference for private requests using on-device ML Kit GenAI."

    override suspend fun canHandle(query: String): Boolean = true

    override suspend fun execute(query: String, context: AgentWorkflowContext): String {
        return context.localInference?.invoke(query)
            ?: "On-device model inference is currently disabled or unavailable."
    }
}

/**
 * Coordinator Agent: The Supervisor in the multi-agent workflow.
 * Analyzes the request, respects the Firebase AI Logic hybrid inference policy,
 * and orchestrates specialist agents into a coherent workflow.
 */
class CoordinatorAgent(
    private val campusAgent: CampusToolMcpAgent,
    private val deviceAgent: DeviceActionAgent,
    private val onDeviceAgent: OnDeviceModelAgent,
    private val cloudAgent: CloudReasoningAgent,
) {
    val name: String = "CoordinatorAgent"
    val role: String = "Workflow Supervisor"

    suspend fun coordinate(query: String, context: AgentWorkflowContext): MultiAgentResponse {
        val startedTotal = System.nanoTime()
        val steps = mutableListOf<AgentStep>()

        // Step 1: Routing & Plan Evaluation
        val planStart = System.nanoTime()
        val decision = RoutingPolicy.choose(query, context.signals)
        val selectedAgent: AdkAgent = when {
            campusAgent.canHandle(query) -> campusAgent
            deviceAgent.canHandle(query) -> deviceAgent
            decision.route == Route.LOCAL_MODEL -> onDeviceAgent
            decision.route == Route.BLOCKED_PRIVATE -> {
                steps.add(
                    AgentStep(
                        agentName = name,
                        role = role,
                        action = "Evaluate Privacy Boundaries",
                        output = "Blocked: Private request cannot be transmitted off-device.",
                        elapsedMs = (System.nanoTime() - planStart) / 1_000_000,
                    ),
                )
                return MultiAgentResponse(
                    finalAnswer = decision.reason,
                    steps = steps,
                    totalElapsedMs = (System.nanoTime() - startedTotal) / 1_000_000,
                )
            }
            else -> cloudAgent
        }

        steps.add(
            AgentStep(
                agentName = name,
                role = role,
                action = "Workflow Planning & Delegation",
                output = "Delegated to ${selectedAgent.name} (${selectedAgent.role}) based on policy ${context.signals.hybridMode}.",
                elapsedMs = (System.nanoTime() - planStart) / 1_000_000,
            ),
        )

        // Step 2: Execution by Specialist Agent
        val execStart = System.nanoTime()
        val specialistAnswer = try {
            selectedAgent.execute(query, context)
        } catch (e: Exception) {
            // Handle fallback based on hybrid policy
            if (context.signals.hybridMode == HybridInferenceMode.PREFER_ON_DEVICE && selectedAgent == onDeviceAgent) {
                steps.add(
                    AgentStep(
                        agentName = selectedAgent.name,
                        role = selectedAgent.role,
                        action = "On-Device Execution Failed",
                        output = "Fallback to CloudReasoningAgent: ${e.message}",
                        elapsedMs = (System.nanoTime() - execStart) / 1_000_000,
                    ),
                )
                cloudAgent.execute(query, context)
            } else if (context.signals.hybridMode == HybridInferenceMode.PREFER_CLOUD && selectedAgent == cloudAgent) {
                steps.add(
                    AgentStep(
                        agentName = selectedAgent.name,
                        role = selectedAgent.role,
                        action = "Cloud Execution Failed",
                        output = "Fallback to OnDeviceModelAgent: ${e.message}",
                        elapsedMs = (System.nanoTime() - execStart) / 1_000_000,
                    ),
                )
                onDeviceAgent.execute(query, context)
            } else {
                "Error during ${selectedAgent.name} execution: ${e.message}"
            }
        }

        steps.add(
            AgentStep(
                agentName = selectedAgent.name,
                role = selectedAgent.role,
                action = "Specialist Execution",
                output = specialistAnswer,
                elapsedMs = (System.nanoTime() - execStart) / 1_000_000,
            ),
        )

        return MultiAgentResponse(
            finalAnswer = specialistAnswer,
            steps = steps,
            totalElapsedMs = (System.nanoTime() - startedTotal) / 1_000_000,
        )
    }
}
