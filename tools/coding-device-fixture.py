#!/usr/bin/env python3
"""Loopback-only real Hermes/plugin fixture with deterministic child executables.

No inference or personal profiles. Controls below belong only to this test process.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import threading


async def serve(source, home, port):
    sys.path.insert(0, str(source))
    os.environ.update(HERMES_HOME=str(home), BELLPULL_NTFY_URL="")
    package = Path(__file__).resolve().parents[1] / "plugins/bellpull"
    shutil.copytree(package, home / "plugins/bellpull", ignore=shutil.ignore_patterns("__pycache__"))
    (home / "config.yaml").write_text("plugins:\n  enabled:\n    - bellpull\n")
    from aiohttp import web
    from gateway.config import PlatformConfig
    from gateway.platforms.api_server import APIServerAdapter
    from hermes_cli.plugins import get_plugin_manager
    from hermes_cli.lifecycle import invoke_hook
    from tools.process_registry import process_registry
    from hermes_state import SessionDB
    manager = get_plugin_manager()
    manager.discover_and_load()
    adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={
        "key": "bellpull-coding-device-fixture", "host": "127.0.0.1", "port": port}))
    workspace = home / "workspace"
    workspace.mkdir()
    for args in (["init", "-q"], ["config", "user.email", "fixture@invalid"], ["config", "user.name", "Fixture"]):
        subprocess.run(["git", "-C", str(workspace), *args], check=True)
    (workspace / "example.txt").write_text("Before fixture edit\n")
    subprocess.run(["git", "-C", str(workspace), "add", "example.txt"], check=True)
    subprocess.run(["git", "-C", str(workspace), "commit", "-qm", "Fixture baseline"], check=True)
    executable = workspace / "opencode"
    executable.write_text('''#!/usr/bin/env python3
import json, pathlib, sys, time
root = pathlib.Path(__file__).parent
structured = "--format" in sys.argv
def output(part, text, kind="text"):
    if structured:
        print(json.dumps(dict(type=kind, timestamp=123, sessionID="ses_device_fixture",
            part=dict(id=part, messageID="msg_device_fixture", sessionID="ses_device_fixture", type=kind, text=text))), flush=True)
    else:
        print("\\x1b[32m" + text + "\\x1b[0m", flush=True)
output("reasoning", "Checking the deterministic fixture workspace.", "reasoning")
(root / "example.txt").write_text("After fixture edit\\n")
(root / "new.txt").write_text("New fixture file\\n")
for step in (1, 2):
    deadline = time.monotonic() + 600
    while not (root / ("release-" + str(step))).exists():
        if time.monotonic() > deadline: raise RuntimeError("Fixture timed out")
        time.sleep(.05)
    output("answer-" + str(step), "Device output step " + str(step))
''')
    executable.chmod(0o700)
    state = dict(admissions=0, offline=False, coding_requests=0, failures=0, released=0, workspace_requests=[])
    release = threading.Event()
    processes = []
    def create_agent(**kwargs):
        class Agent:
            session_id = kwargs.get("session_id")
            _current_turn_id = "coding-device-turn"
            def run_conversation(self, **values):
                state["admissions"] += 1
                if state["admissions"] != 1:
                    raise RuntimeError("Device fixture permits exactly one admission")
                database = SessionDB(home / "state.db")
                try:
                    database.append_message(self.session_id, "user", values["user_message"])
                    for index, suffix in enumerate((" run --format json fixture", " run fixture")):
                        command = shlex.quote(str(executable)) + suffix
                        process = process_registry.spawn_local(command, cwd=str(workspace),
                            task_id=values["task_id"], owner_task_id=values["task_id"])
                        processes.append(process)
                        invoke_hook("post_tool_call", tool_name="terminal", args={"command": command, "background": True},
                            result=json.dumps({"session_id": process.id, "output": "", "exit_code": None}),
                            task_id=values["task_id"], session_id=self.session_id, turn_id=self._current_turn_id,
                            tool_call_id=f"coding-device-call-{index}")
                    if not release.wait(600): raise RuntimeError("Device fixture timed out")
                    database.append_message(self.session_id, "assistant", "Device coordinator finished.")
                    return {"final_response": "Device coordinator finished.", "completed": True}
                finally:
                    database.close()
        return Agent()
    adapter._create_agent = create_agent
    native_routes = adapter._http_route_table
    async def control(request):
        error = adapter._check_auth(request)
        if error is not None: return error
        action = request.match_info.get("action")
        if action == "advance":
            state["released"] += 1
            (workspace / f"release-{state['released']}").touch()
        elif action == "finish":
            release.set()
        elif action in ("offline", "online"):
            state["offline"] = action == "offline"
        elif action == "compact":
            database = await adapter._ensure_session_db_async()
            database.archive_and_compact("phone_coding", [{"role": "assistant", "content": "Device fixture summary"}])
            database.end_session("phone_coding", "compression")
            database.create_session("phone_coding_tip", "api_server", parent_session_id="phone_coding")
            database.create_session("phone_coding_branch", "api_server", parent_session_id="phone_coding",
                model_config={"_branched_from": "phone_coding"})
        elif action is not None:
            raise web.HTTPNotFound()
        return web.json_response(state)
    def routes():
        return native_routes() + [("GET", "/fixture/coding", control), ("POST", "/fixture/coding/{action}", control)]
    @web.middleware
    async def faults(request, handler):
        if request.path.endswith("/workspace"):
            state["workspace_requests"].append(request.query.get("file", "list"))
        if request.path.endswith("/coding") and "/api/bellpull/" in request.path:
            state["coding_requests"] += 1
            if state["offline"]:
                state["failures"] += 1
                return web.json_response({"error": {"message": "Fixture disconnected"}}, status=503)
        return await handler(request)
    # Fixture-only factory adds transport faults after the real plugin routes register.
    def fixture_factory(app, _adapter):
        app.middlewares.append(faults)
    manager._platform_handler_factories.setdefault("api_server", []).append((fixture_factory, "device-fixture"))
    adapter._http_route_table = routes
    try:
        if not await adapter.connect(): raise RuntimeError("Fixture did not start")
        database = await adapter._ensure_session_db_async()
        for session in ("phone_coding", "phone_coding_other"):
            database.create_session(session, "api_server", model="fixture")
            database.set_session_title(session, "OpenCode device check" if session == "phone_coding" else "Separate conversation")
            database.append_message(session, "assistant", "Ready for the device check.")
        print(f"READY http://127.0.0.1:{port}", flush=True)
        stopped = asyncio.Event()
        for sig in (signal.SIGINT, signal.SIGTERM):
            asyncio.get_running_loop().add_signal_handler(sig, stopped.set)
        await stopped.wait()
    finally:
        release.set()
        for process in processes:
            if not process.exited: process_registry.kill_process(process.id)
        await adapter.disconnect()
        manager.unload()
        from gateway import status
        await asyncio.to_thread(status.flush_runtime_status)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hermes-source", type=Path, required=True)
    parser.add_argument("--port", type=int, default=18648)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="bellpull-coding-device-") as directory:
        asyncio.run(serve(args.hermes_source.resolve(), Path(directory), args.port))
