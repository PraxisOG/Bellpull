#!/usr/bin/env python3
"""Disposable loopback stock-Hermes fixture; inference and real profiles are blocked.

With --streaming, a deterministic agent pauses after each live update. Authenticated
POST /fixture/advance releases one step; GET /fixture/state reports progress. These
control routes exist only in this loopback test process, never in the plugin.
"""
import argparse
import asyncio
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import sys
import tempfile
import threading


class StreamSteps:
    def __init__(self):
        self.condition = threading.Condition()
        self.released = 0
        self.phase = "idle"
        self.runs = 0
        self.closed = False
        self.event_requests = 0
        self.event_failures = 0
        self.pending_reply = None

    def wait(self, step):
        with self.condition:
            self.phase = f"step-{step}"
            if not self.condition.wait_for(lambda: self.closed or self.released >= step, timeout=900):
                raise RuntimeError("Device fixture was not advanced within 15 minutes")
            if self.closed:
                raise RuntimeError("Device fixture shut down")

    def close(self):
        with self.condition:
            self.closed = True
            self.condition.notify_all()

    def install(self, adapter, home, audit_repair=False):
        from aiohttp import web
        from hermes_state import SessionDB

        async def control(request):
            error = adapter._check_auth(request)
            if error is not None:
                return error
            with self.condition:
                if request.method == "POST":
                    self.released += 1
                    self.condition.notify_all()
                pending = self.pending_reply if self.released >= 5 else None
                if pending:
                    self.pending_reply = None
            if pending:
                def persist():
                    database = SessionDB(home / "state.db")
                    try:
                        database.append_message(pending[0], "assistant", pending[1])
                    finally:
                        database.close()
                await asyncio.to_thread(persist)
                self.phase = "completed"
            return web.json_response({"phase": self.phase, "released": self.released, "runs": self.runs,
                "event_requests": self.event_requests, "event_failures": self.event_failures})

        routes = adapter._http_route_table
        def route_table():
            result = []
            for method, path, handler in routes():
                if audit_repair and method == "GET" and path == "/v1/runs/{run_id}/events":
                    native_events = handler
                    async def events(request):
                        self.event_requests += 1
                        error = adapter._check_auth(request)
                        if error is not None:
                            return error
                        if not self.event_failures:
                            self.event_failures += 1
                            return web.json_response({"error": {"message": "Fixture pre-frame failure"}}, status=503)
                        return await native_events(request)
                    handler = events
                result.append((method, path, handler))
            return result + [("GET", "/fixture/state", control), ("POST", "/fixture/advance", control)]
        adapter._http_route_table = route_table

        def create_agent(reasoning_callback=None, tool_progress_callback=None,
                         stream_delta_callback=None, interim_assistant_callback=None, **kwargs):
            with self.condition:
                if self.runs:
                    raise RuntimeError("Restart the fixture before another test run")
                self.runs += 1

            steps = self

            class Agent:
                session_id = kwargs.get("session_id")
                model = "fixture"
                provider = "fixture"

                def run_conversation(self, user_message, task_id, **_kwargs):
                    self.session_id = self.session_id or task_id
                    database = SessionDB(home / "state.db")
                    first = "First live update: I am checking the conversation sources and the open chat. This reasoning arrives while the run is still active."
                    second = (" Second live update: the same thinking block is growing without selecting the conversation again. "
                              "CLI, TUI, Telegram and API conversations all belong in the default list. "
                              "The stock gateway retains control of run admission, authentication, sequencing and replay. "
                              "This longer reasoning must remain complete beyond the old five-hundred-character preview, "
                              "while the working indicator continues to animate inside the response boundary.")
                    commentary = "I will read the fixture file next."
                    answer = "Live streaming passed: thinking, tool activity and this saved answer appeared in the already-open chat."
                    call = {"id": "fixture-read", "type": "function", "function": {
                        "name": "read_file", "arguments": json.dumps({"path": "fixture.txt"})}}
                    try:
                        database.append_message(self.session_id, "user", user_message)
                        reasoning_callback(first)
                        steps.wait(1)
                        reasoning_callback(second)
                        tool_progress_callback("reasoning.available", "_thinking", (first + second)[:500])
                        steps.wait(2)
                        interim_assistant_callback(commentary)
                        tool_progress_callback("tool.started", "read_file", "fixture.txt")
                        steps.wait(3)
                        tool_progress_callback("tool.completed", "read_file", result="Fixture contents verified.")
                        stream_delta_callback(answer)
                        steps.wait(4)
                        database.append_message(self.session_id, "assistant", commentary,
                                                reasoning=first + second, tool_calls=[call])
                        database.append_message(self.session_id, "tool", "Fixture contents verified.",
                                                tool_name="read_file", tool_call_id="fixture-read")
                        if audit_repair:
                            database.archive_and_compact(self.session_id, [{"role": "assistant",
                                "content": "Fixture compacted context; original prompt archived."}])
                            with steps.condition:
                                steps.pending_reply = (self.session_id, answer)
                                steps.phase = "awaiting-persistence"
                            return {"final_response": answer, "completed": True}
                        database.append_message(self.session_id, "assistant", answer)
                        with steps.condition:
                            steps.phase = "completed"
                        return {"final_response": answer, "completed": True}
                    finally:
                        database.close()

            return Agent()

        adapter._create_agent = create_agent


async def serve(source, port, home, streaming=False, audit_repair=False, projects=False):
    sys.path.insert(0, str(source))
    os.environ["HERMES_HOME"] = str(home)
    os.environ["BELLPULL_NTFY_URL"] = ""
    package = Path(__file__).resolve().parents[1] / "plugins/bellpull"
    shutil.copytree(package, home / "plugins/bellpull", ignore=shutil.ignore_patterns("__pycache__"))
    (home / "config.yaml").write_text("plugins:\n  enabled:\n    - bellpull\n")
    from gateway.config import PlatformConfig
    from gateway.platforms.api_server import APIServerAdapter
    from hermes_cli.plugins import get_plugin_manager
    from tools.registry import registry
    manager = get_plugin_manager()
    manager.discover_and_load()
    adapter = APIServerAdapter(PlatformConfig(enabled=True,
        extra={"key": "bellpull-stock-device-fixture", "host": "127.0.0.1", "port": port}))
    def no_inference(**kwargs):
        raise RuntimeError("Inference is disabled in this device fixture")
    adapter._create_agent = no_inference
    steps = StreamSteps()
    if streaming:
        steps.install(adapter, home, audit_repair)
    if projects:
        from aiohttp import web
        routes = adapter._http_route_table
        async def compact_project_chat(request):
            error = adapter._check_auth(request)
            if error is not None:
                return error
            database = await adapter._ensure_session_db_async()
            session = request.match_info["session_id"]
            if not database.get_session(session):
                return web.json_response({"error": {"message": "Fixture session missing"}}, status=404)
            continuation = session + "_continued"
            if not database.get_session(continuation):
                database.archive_and_compact(session, [{"role": "assistant", "content": "Project fixture summary"}])
                database.end_session(session, "compression")
                database.create_session(continuation, "api_server", parent_session_id=session)
                database.append_message(continuation, "assistant", "Project chat survived compression.")
            return web.json_response({"session_id": continuation})
        def project_routes():
            return routes() + [
                ("POST", "/fixture/projects/{session_id}/compact", compact_project_chat),
                ("POST", "/fixture/projects/{session_id}/fork", adapter._handle_fork_session)]
        adapter._http_route_table = project_routes
    try:
        if not await adapter.connect():
            raise RuntimeError("Fixture adapter did not start")
        database = await adapter._ensure_session_db_async()
        database.create_session(session_id="phone_fixture", source="api_server", model="fixture")
        database.set_session_title("phone_fixture", "Stock file sharing fixture")
        if streaming or projects:
            for source, title in (("cli", "CLI conversation"), ("tui", "TUI conversation"),
                                  ("telegram", "Telegram conversation"), ("api_server", "Live streaming check")):
                session_id = "phone_stream" if source == "api_server" else "phone_" + source
                database.create_session(session_id=session_id, source=source, model="fixture")
                database.set_session_title(session_id, title)
                database.append_message(session_id, "user", "This conversation already exists on Hermes.")
                database.append_message(session_id, "assistant", "Ready for the device check.")
        result = json.loads(registry.dispatch("bellpull_share_files", {"action": "prepare"},
            scope=manager.scope_key, session_id="phone_fixture"))
        outbox = Path(result["outbox"])
        png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
        for index in range(4):
            (outbox / f"verified-{index}.png").write_bytes(png)
        result = json.loads(registry.dispatch("bellpull_share_files", {"action": "publish"},
            scope=manager.scope_key, session_id="phone_fixture"))
        if len(result.get("artifacts", [])) != 4:
            raise RuntimeError(result)
        print(f"READY http://127.0.0.1:{port} PNG_SHA256={hashlib.sha256(png).hexdigest()}", flush=True)
        stopped = asyncio.Event()
        for sig in (signal.SIGINT, signal.SIGTERM):
            asyncio.get_running_loop().add_signal_handler(sig, stopped.set)
        await stopped.wait()
    finally:
        steps.close()
        await adapter.disconnect()
        manager.unload()
        from gateway import status
        flush = getattr(status, "flush_runtime_status", None)
        if callable(flush) and not await asyncio.to_thread(flush):
            raise RuntimeError("Fixture status writer did not finish")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hermes-source", type=Path, required=True)
    parser.add_argument("--port", type=int, default=18646)
    parser.add_argument("--streaming", action="store_true")
    parser.add_argument("--audit-repair", action="store_true",
                        help="Fail first SSE request, compact the prompt, and delay saved completion until step five")
    parser.add_argument("--projects", action="store_true", help="Seed conversation sources and add authenticated disposable project compaction/fork controls")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="bellpull-stock-device-") as directory:
        asyncio.run(serve(args.hermes_source.resolve(), args.port, Path(directory), args.streaming or args.audit_repair, args.audit_repair, args.projects))
