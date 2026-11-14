"""Routing and optional local/cloud inference for the Campus Agent demo."""

from __future__ import annotations

import json
import os
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import TypedDict


def _load_local_environment() -> None:
    env_file = Path(__file__).resolve().parent / ".env"
    if not env_file.is_file():
        return

    for raw_line in env_file.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", maxsplit=1)
        name = name.strip()
        value = value.strip()
        if not name or not name.replace("_", "").isalnum():
            continue
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        if not os.environ.get(name):
            os.environ[name] = value


_load_local_environment()


class Route(StrEnum):
    CLOUD = "cloud"
    LOCAL_TOOL = "local_tool"
    LOCAL_MODEL = "local_model"
    BLOCKED_PRIVATE = "blocked_private"


class HybridInferenceMode(StrEnum):
    PREFER_ON_DEVICE = "prefer_on_device"
    PREFER_CLOUD = "prefer_cloud"
    ONLY_ON_DEVICE = "only_on_device"
    ONLY_CLOUD = "only_cloud"


class Tool(StrEnum):
    TIMETABLE = "timetable"
    NOTES = "notes"
    CAMPUS_SERVICES = "campus_services"


@dataclass(frozen=True)
class Signals:
    private: bool = False
    latency_sensitive: bool = False
    cost_sensitive: bool = False
    battery_saver: bool = False
    local_model_enabled: bool = True
    hybrid_mode: HybridInferenceMode = HybridInferenceMode.PREFER_CLOUD


@dataclass(frozen=True)
class Decision:
    route: Route
    reason: str
    tool: Tool | None = None


class RequestResult(TypedDict):
    ok: bool
    answer: str
    route: str
    route_label: str
    reason: str
    provider: str
    elapsed_ms: int
    private_data_routed_to_cloud: bool
    private_data_sent_to_cloud: bool
    tool: str | None


PRIVATE_PHRASES = (
    "my grade",
    "my grades",
    "my account",
    "my password",
    "my health",
    "my advisor",
    "my notes",
    "my timetable",
    "my schedule",
    "student id",
    "student-id",
    "social security",
    "medical record",
    "phone number",
    "home address",
    "financial aid",
    "my diagnosis",
)

TIMETABLE = {
    "monday": "Monday · 9:00 AM Calculus (Hall A) · 11:00 AM Computer Science (Lab 2)",
    "tuesday": "Tuesday · 10:00 AM Physics (Room 104) · 1:00 PM Writing Seminar (Room 8)",
    "wednesday": "Wednesday · 9:00 AM Calculus (Hall A) · 2:00 PM Biology (Science Center)",
    "thursday": "Thursday · 10:00 AM Physics (Room 104) · 1:00 PM Computer Science (Lab 2)",
    "friday": "Friday · 11:00 AM Computer Science discussion (Lab 2)",
}

NOTES = (
    "Biology: Review cell structure before the next lab.",
    "Calculus: Problem set 4 is due Friday.",
    "History: Bring primary source notes to seminar.",
)


def detect_tool(query: str) -> Tool | None:
    text = query.casefold()
    if any(word in text for word in ("timetable", "schedule", "class time", "lecture")):
        return Tool.TIMETABLE
    if any(word in text for word in ("my notes", "note", "notes", "notebook")):
        return Tool.NOTES
    if any(
        word in text
        for word in ("campus", "library", "cafeteria", "health center", "student services")
    ):
        return Tool.CAMPUS_SERVICES
    return None


def is_sensitive(query: str) -> bool:
    text = query.casefold()
    return any(phrase in text for phrase in PRIVATE_PHRASES)


def choose_route(query: str, signals: Signals, *, baseline: bool = False) -> Decision:
    if baseline or signals.hybrid_mode == HybridInferenceMode.ONLY_CLOUD:
        reason = (
            "Firebase AI Logic ONLY_CLOUD mode: routed directly to cloud."
            if signals.hybrid_mode == HybridInferenceMode.ONLY_CLOUD
            else "Baseline A always sends the request to cloud."
        )
        return Decision(Route.CLOUD, reason)

    tool = detect_tool(query)
    if tool is not None:
        return Decision(Route.LOCAL_TOOL, "Answered by an on-device MCP AppFunction tool.", tool)

    if signals.private or is_sensitive(query):
        if signals.local_model_enabled:
            return Decision(Route.LOCAL_MODEL, "Private request stays on the local model.")
        return Decision(Route.BLOCKED_PRIVATE, "Private request blocked; it was not sent to cloud.")

    if signals.hybrid_mode == HybridInferenceMode.ONLY_ON_DEVICE:
        if signals.local_model_enabled and ollama_available():
            return Decision(Route.LOCAL_MODEL, "Firebase AI Logic ONLY_ON_DEVICE: strictly routed to local model.")
        return Decision(Route.BLOCKED_PRIVATE, "ONLY_ON_DEVICE mode active but local model is unavailable.")

    if signals.battery_saver:
        return Decision(Route.CLOUD, "Battery saver favors cloud for this general request.")

    if signals.latency_sensitive or signals.cost_sensitive:
        if signals.local_model_enabled and ollama_available():
            reason = (
                "Latency-sensitive request routed to the local model."
                if signals.latency_sensitive
                else "Cost-sensitive request routed to the local model."
            )
            return Decision(Route.LOCAL_MODEL, reason)

    if signals.hybrid_mode == HybridInferenceMode.PREFER_ON_DEVICE:
        if signals.local_model_enabled and ollama_available():
            return Decision(Route.LOCAL_MODEL, "Firebase AI Logic PREFER_ON_DEVICE: routed to local model.")
        return Decision(Route.CLOUD, "PREFER_ON_DEVICE: local model unavailable, falling back to cloud.")

    return Decision(Route.CLOUD, "General request routed to cloud.")


def answer_tool(tool: Tool, query: str) -> str:
    if tool is Tool.TIMETABLE:
        text = query.casefold()
        for day, details in TIMETABLE.items():
            if re.search(rf"\b{day}\b", text):
                return details
        return "\n".join(TIMETABLE.values())

    if tool is Tool.NOTES:
        keyword = query.casefold().split("about", maxsplit=1)[-1].strip()
        matches = [note for note in NOTES if not keyword or keyword in note.casefold()]
        return "\n".join(matches) or "No matching notes in the local demo notebook."

    return (
        "Campus directory (sample data)\n"
        "Library · 8:00 AM–10:00 PM\n"
        "Student Services · 9:00 AM–5:00 PM\n"
        "Health Center · 8:00 AM–4:00 PM\n"
        "Please check your campus directory for current hours."
    )


def _gemini_model() -> str:
    return os.environ.get("GEMINI_MODEL", "gemini-3.5-flash-lite")


def gemini_available() -> bool:
    return bool(os.environ.get("GEMINI_API_KEY"))


def ollama_available() -> bool:
    request = urllib.request.Request("http://127.0.0.1:11434/api/tags")
    try:
        with urllib.request.urlopen(request, timeout=0.25):
            return True
    except (OSError, urllib.error.URLError):
        return False


def app_status() -> dict[str, object]:
    local_available = ollama_available()
    live_cloud = gemini_available()
    try:
        from voice import GTTS_AVAILABLE
        voice_ready = GTTS_AVAILABLE
    except ImportError:
        voice_ready = False

    return {
        "cloud": "Live Gemini" if live_cloud else "Simulated cloud",
        "cloud_live": live_cloud,
        "local_model": "Ollama available" if local_available else "Ollama not running",
        "local_model_available": local_available,
        "voice": "gTTS ready" if voice_ready else "gTTS not installed",
        "voice_available": voice_ready,
        "model": _gemini_model(),
    }


def _post_json(
    url: str,
    payload: dict[str, object],
    timeout: float = 30,
    headers: dict[str, str] | None = None,
) -> dict[str, object]:
    data = json.dumps(payload).encode("utf-8")
    request_headers = {"Content-Type": "application/json"}
    if headers:
        request_headers.update(headers)
    request = urllib.request.Request(
        url,
        data=data,
        headers=request_headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Inference provider returned HTTP {error.code}: {detail[:300]}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"Inference provider is unreachable: {error.reason}") from error


def _live_cloud_answer(query: str) -> str:
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        raise RuntimeError("Live Gemini is not configured. Set GEMINI_API_KEY or use demo mode.")
    model = _gemini_model()
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
    payload = {
        "contents": [{"parts": [{"text": query}]}],
        "generationConfig": {"maxOutputTokens": 512, "temperature": 0.4},
    }
    response = _post_json(
        url,
        payload,
        headers={"x-goog-api-key": api_key},
    )
    candidates = response.get("candidates", [])
    if not isinstance(candidates, list):
        raise RuntimeError("Gemini returned an invalid candidates response.")
    first_candidate = candidates[0] if candidates else {}
    if not isinstance(first_candidate, dict):
        raise RuntimeError("Gemini returned an invalid candidate.")
    content = first_candidate.get("content", {})
    if not isinstance(content, dict):
        raise RuntimeError("Gemini returned an invalid content response.")
    parts = content.get("parts", [])
    if not isinstance(parts, list):
        raise RuntimeError("Gemini returned an invalid parts response.")
    answer = "\n".join(
        part["text"]
        for part in parts
        if isinstance(part, dict) and isinstance(part.get("text"), str)
    )
    if not answer:
        raise RuntimeError("Gemini returned no text response.")
    return answer


def _simulated_cloud_answer(query: str, delay_ms: int) -> str:
    time.sleep(delay_ms / 1000)
    return (
        f"Simulated cloud response for: “{query}”\n\n"
        "This is a demo placeholder, not a live LLM answer. Set GEMINI_API_KEY to use Gemini."
    )


def _local_model_answer(query: str) -> str:
    if not ollama_available():
        raise RuntimeError(
            "Private request blocked: no local Ollama model is running, so nothing was sent to cloud. "
            "Install Ollama, pull a model, and retry."
        )
    model = os.environ.get("OLLAMA_MODEL", "llama3.2")
    response = _post_json(
        "http://127.0.0.1:11434/api/generate",
        {
            "model": model,
            "prompt": (
                "You are a concise campus assistant. Do not invent campus-specific facts. "
                "If you do not know, say so.\n\n" + query
            ),
            "stream": False,
        },
    )
    answer = response.get("response")
    if not isinstance(answer, str) or not answer.strip():
        raise RuntimeError("The local Ollama model returned no response.")
    return answer.strip()


def run_request(
    query: str,
    signals: Signals,
    *,
    baseline: bool = False,
    simulated_cloud_delay_ms: int = 550,
) -> RequestResult:
    decision = choose_route(query, signals, baseline=baseline)
    started = time.perf_counter()
    provider = ""
    private_cloud_route = decision.route is Route.CLOUD and (
        signals.private or is_sensitive(query)
    )
    private_cloud_send = private_cloud_route and gemini_available()

    try:
        if decision.route is Route.LOCAL_TOOL:
            if decision.tool is None:
                raise RuntimeError("The local campus tool route has no selected tool.")
            answer = answer_tool(decision.tool, query)
            provider = "local tool · sample data"
        elif decision.route is Route.LOCAL_MODEL:
            answer = _local_model_answer(query)
            provider = f"local Ollama · {os.environ.get('OLLAMA_MODEL', 'llama3.2')}"
        elif decision.route is Route.BLOCKED_PRIVATE:
            answer = decision.reason
        elif gemini_available():
            answer = _live_cloud_answer(query)
            provider = f"Gemini · {_gemini_model()}"
        else:
            answer = _simulated_cloud_answer(query, simulated_cloud_delay_ms)
            provider = "cloud simulation"
    except (RuntimeError, OSError, ValueError) as error:
        answer = str(error)
        provider = provider or ("cloud" if decision.route is Route.CLOUD else "local")
        return {
            "ok": False,
            "answer": answer,
            "route": decision.route.value,
            "route_label": decision.route.value.replace("_", " "),
            "reason": decision.reason,
            "provider": provider,
            "elapsed_ms": round((time.perf_counter() - started) * 1000),
            "private_data_routed_to_cloud": private_cloud_route,
            "private_data_sent_to_cloud": private_cloud_send,
            "tool": decision.tool.value if decision.tool else None,
        }

    return {
        "ok": True,
        "answer": answer,
        "route": decision.route.value,
        "route_label": decision.route.value.replace("_", " "),
        "reason": decision.reason,
        "provider": provider,
        "elapsed_ms": round((time.perf_counter() - started) * 1000),
        "private_data_routed_to_cloud": private_cloud_route,
        "private_data_sent_to_cloud": private_cloud_send,
        "tool": decision.tool.value if decision.tool else None,
    }


# On-Device MCP Tool Definitions (AppFunctions Simulation)
MCP_TOOLS = [
    {
        "name": "getTimetable",
        "description": "Fetch student class timetable and schedule for a specific day or entire week.",
        "inputSchema": {
            "type": "object",
            "properties": {"day": {"type": "string", "description": "Day of the week (e.g. monday)"}},
        },
    },
    {
        "name": "searchStudentNotes",
        "description": "Search personal student notes by subject or keyword.",
        "inputSchema": {
            "type": "object",
            "properties": {"query": {"type": "string", "description": "Subject or topic keyword"}},
            "required": ["query"],
        },
    },
    {
        "name": "getCampusServices",
        "description": "Get campus facilities and operating hours (Library, Student Services, Health Center).",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "setDeviceAlarm",
        "description": "Set a local clock alarm on the Android device via native Intent.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "hour": {"type": "integer", "description": "Hour (0-23)"},
                "minute": {"type": "integer", "description": "Minute (0-59)"},
                "message": {"type": "string", "description": "Alarm label"},
            },
            "required": ["hour", "minute"],
        },
    },
    {
        "name": "openDeviceApp",
        "description": "Launch an installed application on the Android device.",
        "inputSchema": {
            "type": "object",
            "properties": {"packageName": {"type": "string", "description": "Package name to launch"}},
            "required": ["packageName"],
        },
    },
]


def call_mcp_tool(name: str, arguments: dict[str, object] | None = None) -> dict[str, object]:
    args = arguments or {}
    if name == "getTimetable":
        day = str(args.get("day", ""))
        return {"content": [{"type": "text", "text": answer_tool(Tool.TIMETABLE, day)}], "isError": False}
    if name == "searchStudentNotes":
        q = str(args.get("query", ""))
        return {"content": [{"type": "text", "text": answer_tool(Tool.NOTES, q)}], "isError": False}
    if name == "getCampusServices":
        return {"content": [{"type": "text", "text": answer_tool(Tool.CAMPUS_SERVICES, "")}], "isError": False}
    if name == "setDeviceAlarm":
        hour = int(args.get("hour", 7))
        minute = int(args.get("minute", 0))
        msg = str(args.get("message", "Campus Alarm"))
        return {"content": [{"type": "text", "text": f"Alarm set for {hour:02d}:{minute:02d}: {msg}"}], "isError": False}
    if name == "openDeviceApp":
        pkg = str(args.get("packageName", ""))
        return {"content": [{"type": "text", "text": f"Launched application: {pkg}"}], "isError": False}
    return {"content": [{"type": "text", "text": f"Unknown MCP tool: {name}"}], "isError": True}


def run_adk_workflow(query: str, signals: Signals) -> dict[str, object]:
    """Execute query using the ADK multi-agent workflow."""
    started = time.perf_counter()
    steps = []

    plan_start = time.perf_counter()
    decision = choose_route(query, signals)

    lower = query.casefold()
    is_device = any(w in lower for w in ("alarm", "wake me", "launch", "open app"))

    if is_device:
        target_agent = "DeviceActionAgent"
        target_role = "Device Action Specialist"
    elif decision.tool is not None:
        target_agent = "CampusMcpAgent"
        target_role = "On-Device Tool Specialist (AppFunctions MCP)"
    elif decision.route is Route.LOCAL_MODEL:
        target_agent = "OnDeviceModelAgent"
        target_role = "On-Device LLM Specialist"
    elif decision.route is Route.BLOCKED_PRIVATE:
        target_agent = "CoordinatorAgent"
        target_role = "Privacy Boundary Guard"
    else:
        target_agent = "CloudReasoningAgent"
        target_role = "Firebase AI Logic Specialist"

    steps.append({
        "agent": "CoordinatorAgent",
        "role": "Workflow Supervisor",
        "action": "Plan & Delegate",
        "output": f"Delegated request to {target_agent} ({target_role}) under policy {signals.hybrid_mode}.",
        "elapsed_ms": round((time.perf_counter() - plan_start) * 1000),
    })

    exec_start = time.perf_counter()
    if is_device:
        if "alarm" in lower or "wake me" in lower:
            res = call_mcp_tool("setDeviceAlarm", {"hour": 7, "minute": 0, "message": query})
        else:
            pkg = query.split("open")[-1].split("launch")[-1].strip()
            res = call_mcp_tool("openDeviceApp", {"packageName": pkg})
        answer = res["content"][0]["text"]
    else:
        run_res = run_request(query, signals)
        answer = run_res["answer"]

    steps.append({
        "agent": target_agent,
        "role": target_role,
        "action": "Execute Task",
        "output": answer,
        "elapsed_ms": round((time.perf_counter() - exec_start) * 1000),
    })

    return {
        "ok": True,
        "answer": answer,
        "steps": steps,
        "elapsed_ms": round((time.perf_counter() - started) * 1000),
        "target_agent": target_agent,
        "hybrid_mode": signals.hybrid_mode,
    }


def main() -> None:
    try:
        from voice import GTTS_AVAILABLE, speak
    except ImportError:
        GTTS_AVAILABLE = False
        speak = lambda text, wait=True: False

    voice_enabled = False
    status = app_status()
    print("=" * 64)
    print(f" Campus AI Agent (Hybrid Voice Assistant)")
    print(f" Cloud Provider: {status['cloud']} ({status['model']})")
    print(f" Local Model:    {status['local_model']}")
    print(f" Voice Engine:   gTTS ({'Ready' if GTTS_AVAILABLE else 'Not available'})")
    print("=" * 64)
    print("Commands:")
    print("  exit / quit       - Exit the chat")
    print("  !voice            - Toggle gTTS voice output ON/OFF")
    print("  !p <query>        - Mark request as private")
    print("  !b <query>        - Baseline A (send directly to cloud)")
    print("=" * 64)

    while True:
        try:
            status_indicator = "voice:ON" if voice_enabled else "voice:OFF"
            prompt = input(f"\nYou [{status_indicator}] > ").strip()
        except (KeyboardInterrupt, EOFError):
            print("\nExiting Campus Agent.")
            break

        if not prompt:
            continue
        if prompt.lower() in ("exit", "quit", "q"):
            print("Exiting Campus Agent.")
            break
        if prompt.lower() in ("!voice", "!v"):
            voice_enabled = not voice_enabled
            state_str = "ENABLED (gTTS will speak responses)" if voice_enabled else "DISABLED"
            print(f"[Voice assistant is now {state_str}]")
            continue

        private = False
        baseline = False
        if prompt.startswith("!p "):
            private = True
            prompt = prompt[3:].strip()
        elif prompt.startswith("!b "):
            baseline = True
            prompt = prompt[3:].strip()

        signals = Signals(private=private)
        result = run_request(prompt, signals, baseline=baseline)

        print(f"\n[Route: {result['route_label']} | Provider: {result['provider']} | Latency: {result['elapsed_ms']}ms]")
        print(f"Agent > {result['answer']}")

        if voice_enabled and result.get("answer"):
            speak(result["answer"], wait=False)


if __name__ == "__main__":
    main()
