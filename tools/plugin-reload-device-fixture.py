#!/usr/bin/env python3
"""Loopback-only native Hermes reload fixture; disposable data and fake inference."""
import asyncio
from functools import wraps
import inspect
import os
from pathlib import Path
import signal
import tempfile
import threading


async def serve(home):
    os.environ.update(HERMES_HOME=str(home), HERMES_KANBAN_HOME=str(home), HERMES_KANBAN_DB="",
                      BELLPULL_NTFY_URL="", BELLPULL_DEVICE_NAME="Reload fixture")
    config = home / "config.yaml"
    config.write_text("plugins:\n  enabled:\n    - bellpull\n")
    from aiohttp import web
    from gateway.config import PlatformConfig
    from gateway.platforms.api_server import APIServerAdapter
    from hermes_cli.plugins import get_plugin_manager
    from hermes_cli import kanban_db as kb
    from hermes_cli.kanban_db_connect import connect
    connection = connect(db_path=kb.kanban_db_path(board="default"))
    try:
        kb.create_task(connection, title="Reload keeps this task", body="Wireless ADB regression fixture",
                       triage=True, board="default")
    finally:
        connection.close()
    manager = get_plugin_manager()
    manager.discover_and_load()
    adapter = APIServerAdapter(PlatformConfig(enabled=True,
        extra={"key": "bellpull-reload-device-fixture", "host": "127.0.0.1", "port": 18659}))
    state = {"runs": 0, "reloads": 0}
    release = threading.Event()
    signature = inspect.signature(adapter._create_agent)
    @wraps(adapter._create_agent)
    def create_agent(*args, **kwargs):
        kwargs = signature.bind_partial(*args, **kwargs).arguments
        class Agent:
            session_id = kwargs.get("session_id")
            reasoning_callback = kwargs.get("reasoning_callback")
            def run_conversation(self, **arguments):
                state["runs"] += 1
                if not release.wait(180):
                    raise RuntimeError("Device fixture did not release this run")
                return {"final_response": "Wireless reload retained this run", "completed": True}
        return Agent()
    adapter._create_agent = create_agent

    async def control(request):
        error = adapter._check_auth(request)
        if error is not None:
            return error
        action = (await request.json()).get("action")
        if action in {"reload", "enable", "disable"}:
            config.write_text("plugins:\n  " + ("disabled" if action == "disable" else "enabled") + ":\n    - bellpull\n")
            await asyncio.to_thread(manager.discover_and_load, force=True)
            rewire = getattr(adapter, "rewire_plugin_handlers", None)
            if callable(rewire):
                rewire()
            state["reloads"] += 1
        elif action == "release":
            release.set()
        return web.json_response(state)
    native_routes = adapter._http_route_table
    adapter._http_route_table = lambda: native_routes() + [("POST", "/fixture/reload", control)]
    stopped = asyncio.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        asyncio.get_running_loop().add_signal_handler(sig, stopped.set)
    try:
        if not await adapter.connect():
            raise RuntimeError("Fixture failed to connect")
        print("READY http://127.0.0.1:18659", flush=True)
        await stopped.wait()
    finally:
        release.set()
        await adapter.disconnect()
        manager.unload()
        from gateway import status
        flush = getattr(status, "flush_runtime_status", None)
        if callable(flush):
            await asyncio.to_thread(flush)


if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix="bellpull-reload-device-") as temporary:
        asyncio.run(serve(Path(temporary)))
