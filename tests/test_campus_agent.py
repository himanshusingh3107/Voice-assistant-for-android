import unittest
import os
from unittest.mock import patch

from campus_agent import (
    Route,
    Signals,
    Tool,
    answer_tool,
    _gemini_model,
    _live_cloud_answer,
    choose_route,
    detect_tool,
    is_sensitive,
    run_request,
)


class CampusAgentRoutingTests(unittest.TestCase):
    def test_baseline_sends_even_local_tool_queries_to_cloud(self):
        decision = choose_route("Show my timetable for Monday", Signals(), baseline=True)
        self.assertEqual(Route.CLOUD, decision.route)
        self.assertIsNone(decision.tool)

    def test_hybrid_routes_campus_tasks_to_local_tools(self):
        self.assertEqual(Tool.TIMETABLE, detect_tool("Show my timetable"))
        self.assertEqual(Tool.NOTES, detect_tool("Find my notes about Biology"))
        self.assertEqual(Tool.CAMPUS_SERVICES, detect_tool("What are the library hours?"))
        self.assertEqual(
            Route.LOCAL_TOOL,
            choose_route("Show my timetable", Signals(battery_saver=True)).route,
        )

    def test_sensitive_query_uses_local_model_or_is_blocked(self):
        decision = choose_route("Help me understand my grades", Signals())
        self.assertEqual(Route.LOCAL_MODEL, decision.route)
        blocked = choose_route(
            "Help me understand my grades",
            Signals(local_model_enabled=False),
        )
        self.assertEqual(Route.BLOCKED_PRIVATE, blocked.route)

    def test_battery_saver_routes_general_queries_to_cloud(self):
        with patch("campus_agent.ollama_available", return_value=True):
            decision = choose_route(
                "Explain recursion",
                Signals(battery_saver=True, cost_sensitive=True),
            )
        self.assertEqual(Route.CLOUD, decision.route)

    def test_cost_signal_prefers_a_available_local_model(self):
        with patch("campus_agent.ollama_available", return_value=True):
            decision = choose_route("Explain recursion", Signals(cost_sensitive=True))
        self.assertEqual(Route.LOCAL_MODEL, decision.route)

    def test_private_request_never_falls_back_to_cloud_when_local_model_is_unavailable(self):
        with (
            patch("campus_agent.ollama_available", return_value=False),
            patch("campus_agent.gemini_available", return_value=True),
            patch("campus_agent._live_cloud_answer") as cloud,
        ):
            result = run_request("Help me understand my grades", Signals())
        self.assertFalse(result["ok"])
        self.assertEqual(Route.LOCAL_MODEL.value, result["route"])
        self.assertFalse(result["private_data_sent_to_cloud"])
        cloud.assert_not_called()

    def test_simulated_baseline_routes_private_request_but_does_not_send_it(self):
        with (
            patch("campus_agent.gemini_available", return_value=False),
            patch("campus_agent._simulated_cloud_answer", return_value="demo"),
        ):
            result = run_request(
                "Help me understand my grades",
                Signals(),
                baseline=True,
                simulated_cloud_delay_ms=0,
            )
        self.assertTrue(result["private_data_routed_to_cloud"])
        self.assertFalse(result["private_data_sent_to_cloud"])
        self.assertEqual(Route.CLOUD.value, result["route"])

    def test_live_baseline_marks_private_request_as_sent_to_cloud(self):
        with (
            patch("campus_agent.gemini_available", return_value=True),
            patch("campus_agent._live_cloud_answer", return_value="live response"),
        ):
            result = run_request(
                "Help me understand my grades",
                Signals(),
                baseline=True,
            )
        self.assertTrue(result["private_data_routed_to_cloud"])
        self.assertTrue(result["private_data_sent_to_cloud"])

    def test_gemini_live_request_uses_requested_model_and_header_key(self):
        captured = {}

        def fake_post(url, payload, timeout=30, headers=None):
            captured["url"] = url
            captured["payload"] = payload
            captured["timeout"] = timeout
            captured["headers"] = headers
            return {
                "candidates": [
                    {"content": {"parts": [{"text": "Gemini response"}]}}
                ]
            }

        with (
            patch.dict(
                os.environ,
                {
                    "GEMINI_API_KEY": "test-key",
                    "GEMINI_MODEL": "gemini-3.5-flash-lite",
                },
            ),
            patch("campus_agent._post_json", side_effect=fake_post),
        ):
            answer = _live_cloud_answer("Hello")

        self.assertEqual("Gemini response", answer)
        self.assertIn("/models/gemini-3.5-flash-lite:generateContent", captured["url"])
        self.assertNotIn("key=", captured["url"])
        self.assertEqual({"x-goog-api-key": "test-key"}, captured["headers"])
        self.assertIn("Hello", captured["payload"]["contents"][0]["parts"][0]["text"])
        self.assertGreater(captured["timeout"], 0)

    def test_requested_gemini_model_is_default(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual("gemini-3.5-flash-lite", _gemini_model())

    def test_timetable_tool_returns_local_sample_data(self):
        self.assertIn("Monday", answer_tool(Tool.TIMETABLE, "my timetable Monday"))

    def test_sensitive_detector_matches_private_data_phrases(self):
        self.assertTrue(is_sensitive("What is my student ID?"))
        self.assertFalse(is_sensitive("How does recursion work?"))

    def test_hybrid_inference_modes(self):
        from campus_agent import HybridInferenceMode
        # ONLY_CLOUD
        decision_cloud = choose_route("Explain recursion", Signals(hybrid_mode=HybridInferenceMode.ONLY_CLOUD))
        self.assertEqual(Route.CLOUD, decision_cloud.route)

        # ONLY_ON_DEVICE with local model available
        with patch("campus_agent.ollama_available", return_value=True):
            decision_local = choose_route("Explain recursion", Signals(hybrid_mode=HybridInferenceMode.ONLY_ON_DEVICE))
            self.assertEqual(Route.LOCAL_MODEL, decision_local.route)

        # ONLY_ON_DEVICE blocked when unavailable
        with patch("campus_agent.ollama_available", return_value=False):
            decision_blocked = choose_route("Explain recursion", Signals(hybrid_mode=HybridInferenceMode.ONLY_ON_DEVICE))
            self.assertEqual(Route.BLOCKED_PRIVATE, decision_blocked.route)

        # PREFER_ON_DEVICE with local model available
        with patch("campus_agent.ollama_available", return_value=True):
            decision_pref_dev = choose_route("Explain recursion", Signals(hybrid_mode=HybridInferenceMode.PREFER_ON_DEVICE))
            self.assertEqual(Route.LOCAL_MODEL, decision_pref_dev.route)

        # PREFER_CLOUD
        decision_pref_cloud = choose_route("Explain recursion", Signals(hybrid_mode=HybridInferenceMode.PREFER_CLOUD))
        self.assertEqual(Route.CLOUD, decision_pref_cloud.route)

    def test_on_device_mcp_tools(self):
        from campus_agent import MCP_TOOLS, call_mcp_tool
        self.assertEqual(5, len(MCP_TOOLS))
        tool_names = [t["name"] for t in MCP_TOOLS]
        self.assertIn("getTimetable", tool_names)
        self.assertIn("searchStudentNotes", tool_names)
        self.assertIn("getCampusServices", tool_names)
        self.assertIn("setDeviceAlarm", tool_names)
        self.assertIn("openDeviceApp", tool_names)

        # Test calling getTimetable
        res_tt = call_mcp_tool("getTimetable", {"day": "Monday"})
        self.assertFalse(res_tt["isError"])
        self.assertIn("Monday", res_tt["content"][0]["text"])

        # Test calling alarm
        res_alarm = call_mcp_tool("setDeviceAlarm", {"hour": 8, "minute": 30, "message": "Class"})
        self.assertFalse(res_alarm["isError"])
        self.assertIn("08:30", res_alarm["content"][0]["text"])

    def test_adk_multi_agent_workflow(self):
        from campus_agent import run_adk_workflow
        wf_res = run_adk_workflow("Show my timetable for Monday", Signals())
        self.assertTrue(wf_res["ok"])
        self.assertEqual("CampusMcpAgent", wf_res["target_agent"])
        self.assertGreaterEqual(len(wf_res["steps"]), 2)
        self.assertEqual("CoordinatorAgent", wf_res["steps"][0]["agent"])
        self.assertIn("Monday", wf_res["answer"])


if __name__ == "__main__":
    unittest.main()
