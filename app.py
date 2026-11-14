"""Start the local Campus Hybrid Agent web demo."""

from __future__ import annotations

import json
import mimetypes
import os
import threading
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse


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
        os.environ.setdefault(name, value)


_load_local_environment()

from campus_agent import (
    HybridInferenceMode,
    MCP_TOOLS,
    RequestResult,
    Signals,
    app_status,
    call_mcp_tool,
    run_adk_workflow,
    run_request,
)
from voice import GTTS_AVAILABLE, generate_speech_bytes

ROOT = Path(__file__).resolve().parent
WEB_ROOT = ROOT / "web"
MAX_BODY_BYTES = 16_384


class SessionMetrics:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._runs: dict[str, list[RequestResult]] = defaultdict(list)

    def record(self, mode: str, result: RequestResult) -> dict[str, object]:
        with self._lock:
            self._runs[mode].append(result)
            return self.snapshot_locked()

    def reset(self) -> dict[str, object]:
        with self._lock:
            self._runs.clear()
            return self.snapshot_locked()

    def snapshot(self) -> dict[str, object]:
        with self._lock:
            return self.snapshot_locked()

    def snapshot_locked(self) -> dict[str, object]:
        modes: dict[str, object] = {}
        for mode in ("hybrid", "baseline"):
            runs = self._runs[mode]
            cloud = [run for run in runs if run["route"] == "cloud"]
            local = [
                run for run in runs
                if run["route"] in ("local_tool", "local_model")
            ]
            blocked = [run for run in runs if run["route"] == "blocked_private"]
            modes[mode] = {
                "total": len(runs),
                "cloud": len(cloud),
                "local": len(local),
                "blocked": len(blocked),
                "cloud_pct": round(len(cloud) * 100 / len(runs)) if runs else 0,
                "private_cloud": sum(bool(run["private_data_routed_to_cloud"]) for run in runs),
                "private_sent": sum(bool(run["private_data_sent_to_cloud"]) for run in runs),
                "avg_cloud_ms": round(
                    sum(int(run["elapsed_ms"]) for run in cloud) / len(cloud)
                ) if cloud else None,
                "avg_local_ms": round(
                    sum(int(run["elapsed_ms"]) for run in local) / len(local)
                ) if local else None,
                "avg_ms": round(
                    sum(int(run["elapsed_ms"]) for run in runs) / len(runs)
                ) if runs else None,
                "simulated_cloud": sum(run["provider"] == "cloud simulation" for run in cloud),
            }
        return {"modes": modes}


METRICS = SessionMetrics()


class CampusHandler(BaseHTTPRequestHandler):
    server_version = "CampusAgentDemo/1.0"

    def log_message(self, format: str, *args: object) -> None:
        print(f"[campus-agent] {self.address_string()} - {format % args}")

    def do_GET(self) -> None:
        url_parts = urlparse(self.path)
        path = url_parts.path
        if path == "/api/status":
            self._json_response(200, {"status": app_status(), "metrics": METRICS.snapshot()})
            return

        if path == "/api/mcp/tools":
            self._json_response(200, {"tools": MCP_TOOLS})
            return

        if path == "/api/tts":
            query_params = parse_qs(url_parts.query)
            text = query_params.get("text", [""])[0].strip()
            lang = query_params.get("lang", ["auto"])[0]
            tld = query_params.get("tld", [None])[0]
            if not text:
                self._json_response(400, {"error": "Missing 'text' query parameter."})
                return
            audio_bytes = generate_speech_bytes(text, lang=lang, tld=tld)
            if not audio_bytes:
                self._json_response(500, {"error": "Failed to synthesize speech via gTTS."})
                return
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("Content-Length", str(len(audio_bytes)))
            self.send_header("Cache-Control", "public, max-age=86400")
            self.end_headers()
            self.wfile.write(audio_bytes)
            return

        requested = unquote(path.lstrip("/")) or "index.html"
        target = (WEB_ROOT / requested).resolve()
        if WEB_ROOT.resolve() not in target.parents or not target.is_file():
            self.send_error(404)
            return

        content = target.read_bytes()
        content_type = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        self.send_response(200)
        self.send_header("Content-Type", f"{content_type}; charset=utf-8")
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(content)

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        if path == "/api/reset":
            self._json_response(200, {"metrics": METRICS.reset()})
            return

        if path == "/api/tts":
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > MAX_BODY_BYTES:
                    raise ValueError("Body must be between 1 and 16 KB.")
                body = json.loads(self.rfile.read(length))
                text = str(body.get("text", "")).strip()
                lang = str(body.get("lang", "auto")).strip()
                tld = body.get("tld")
                if not text:
                    self._json_response(400, {"error": "Missing 'text' in JSON body."})
                    return
                audio_bytes = generate_speech_bytes(text, lang=lang, tld=tld)
                if not audio_bytes:
                    self._json_response(500, {"error": "Failed to synthesize speech via gTTS."})
                    return
                self.send_response(200)
                self.send_header("Content-Type", "audio/mpeg")
                self.send_header("Content-Length", str(len(audio_bytes)))
                self.send_header("Cache-Control", "public, max-age=86400")
                self.end_headers()
                self.wfile.write(audio_bytes)
            except Exception as error:
                self._json_response(400, {"error": str(error)})
            return

        if path == "/api/mcp/call":
            try:
                length = int(self.headers.get("Content-Length", "0"))
                body = json.loads(self.rfile.read(length))
                name = str(body.get("name", ""))
                args = body.get("arguments", {})
                mcp_res = call_mcp_tool(name, args if isinstance(args, dict) else {})
                self._json_response(200, {"result": mcp_res})
            except Exception as error:
                self._json_response(400, {"error": str(error)})
            return

        if path != "/api/chat":
            self.send_error(404)
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAX_BODY_BYTES:
                raise ValueError("Request body must be between 1 and 16 KB.")
            body = json.loads(self.rfile.read(length))
            query = body.get("query")
            if not isinstance(query, str) or not query.strip() or len(query) > 4000:
                raise ValueError("Enter a request between 1 and 4,000 characters.")
            mode = "baseline" if body.get("baseline") is True else "hybrid"
            hybrid_mode_raw = body.get("hybrid_mode", "prefer_cloud")
            try:
                h_mode = HybridInferenceMode(hybrid_mode_raw)
            except ValueError:
                h_mode = HybridInferenceMode.PREFER_CLOUD

            use_adk = body.get("use_adk_workflow") is True
            signals = Signals(
                private=body.get("private") is True,
                latency_sensitive=body.get("latency_sensitive") is True,
                cost_sensitive=body.get("cost_sensitive") is True,
                battery_saver=body.get("battery_saver") is True,
                local_model_enabled=body.get("local_model_enabled") is not False,
                hybrid_mode=h_mode,
            )
            delay = body.get("simulated_cloud_delay_ms", 550)
            if not isinstance(delay, int) or isinstance(delay, bool) or not 0 <= delay <= 3000:
                raise ValueError("Cloud demo delay must be between 0 and 3,000 ms.")

            if use_adk:
                wf_result = run_adk_workflow(query.strip(), signals)
                result = {
                    "ok": wf_result["ok"],
                    "answer": wf_result["answer"],
                    "route": "adk_workflow",
                    "route_label": f"ADK · {wf_result['target_agent']}",
                    "reason": f"ADK Multi-Agent Workflow: coordinated by CoordinatorAgent.",
                    "provider": f"ADK · {wf_result['target_agent']}",
                    "elapsed_ms": wf_result["elapsed_ms"],
                    "private_data_routed_to_cloud": False,
                    "private_data_sent_to_cloud": False,
                    "tool": None,
                    "steps": wf_result["steps"],
                }
            else:
                result = run_request(
                    query.strip(),
                    signals,
                    baseline=mode == "baseline" or h_mode == HybridInferenceMode.ONLY_CLOUD,
                    simulated_cloud_delay_ms=delay,
                )
            metrics = METRICS.record(mode, result)
            self._json_response(200, {"result": result, "metrics": metrics})
        except (json.JSONDecodeError, UnicodeDecodeError, ValueError) as error:
            self._json_response(400, {"error": str(error)})

    def _json_response(self, status: int, payload: dict[str, object]) -> None:
        encoded = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(encoded)


def main() -> None:
    host = "0.0.0.0"
    port = 8000
    server = ThreadingHTTPServer((host, port), CampusHandler)
    print(f"Campus Hybrid Agent is ready locally at http://127.0.0.1:{port}")
    print(f"Network access for your Android phone at http://172.26.161.101:{port}")
    print("Press Ctrl+C to stop.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping Campus Hybrid Agent.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
