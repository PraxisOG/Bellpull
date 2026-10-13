#!/usr/bin/env python3
"""Disposable loopback Hermes planning host. No inference, scheduler or real profiles."""
import argparse
import asyncio
import json
import os
from pathlib import Path
import shutil
import signal
import sys
import tempfile


async def serve(source, home, port):
    sys.path.insert(0, str(source))
    os.environ.update(HERMES_HOME=str(home), BELLPULL_NTFY_URL="")
    shutil.copytree(Path(__file__).resolve().parents[1] / "plugins/bellpull", home / "plugins/bellpull",
        ignore=shutil.ignore_patterns("__pycache__"))
    (home / "config.yaml").write_text("plugins:\n  enabled:\n    - bellpull\n")
    from aiohttp import web
    from gateway.config import PlatformConfig
    from gateway.platforms.api_server import APIServerAdapter
    from hermes_cli.plugins import get_plugin_manager
    from tools.registry import registry
    from plugins.bellpull.planning import PlanningStore, routines
    manager = get_plugin_manager()
    manager.discover_and_load()
    adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={
        "key": "bellpull-planning-device-fixture", "host": "127.0.0.1", "port": port}))
    state = dict(admissions=0, offline=False, drop_report=False, dropped_reports=0)
    def no_inference(**_kwargs):
        state["admissions"] += 1
        raise RuntimeError("Inference is disabled in this device fixture")
    adapter._create_agent = no_inference
    async def control(request):
        error = adapter._check_auth(request)
        if error is not None: return error
        action = request.match_info.get("action")
        if action in ("offline", "online"):
            state["offline"] = action == "offline"
        elif action == "drop-report": state["drop_report"] = True
        elif action == "propose":
            args = await request.json()
            value = json.loads(registry.dispatch("bellpull_planning", args, scope=manager.scope_key))
            if "proposal" not in value: raise RuntimeError(value)
            return web.json_response(value["proposal"])
        elif action is not None: raise web.HTTPNotFound()
        store = PlanningStore(home)
        device = request.query.get("device")
        return web.json_response(dict(state, reminders=store.snapshot()["reminders"],
            routines=routines(), calendar=store.calendar(device) if device else None))
    native_routes = adapter._http_route_table
    adapter._http_route_table = lambda: native_routes() + [
        ("GET", "/fixture/planning", control), ("POST", "/fixture/planning/{action}", control)]
    @web.middleware
    async def faults(request, handler):
        if request.path.startswith(("/api/bellpull/planning", "/api/bellpull/calendar/")):
            if state["offline"]: return web.json_response({"error": {"message": "Fixture disconnected"}}, status=503)
            response = await handler(request)
            if request.path.endswith("/planning/proposals") and request.method == "POST" and state["drop_report"]:
                state["drop_report"] = False; state["dropped_reports"] += 1
                return web.json_response({"error": {"message": "Fixture dropped result after commit"}}, status=503)
            return response
        return await handler(request)
    def fixture_factory(app, _adapter): app.middlewares.append(faults)
    manager._platform_handler_factories.setdefault("api_server", []).append((fixture_factory, "device-fixture"))
    try:
        if not await adapter.connect(): raise RuntimeError("Fixture did not start")
        database = await adapter._ensure_session_db_async()
        database.create_session("phone_planning", "api_server", model="fixture")
        database.set_session_title("phone_planning", "Planning device fixture")
        database.append_message("phone_planning", "assistant", "Keep this conversation while visiting Today.")
        print(f"READY http://127.0.0.1:{port}", flush=True)
        stopped = asyncio.Event()
        for sig in (signal.SIGINT, signal.SIGTERM): asyncio.get_running_loop().add_signal_handler(sig, stopped.set)
        await stopped.wait()
    finally:
        await adapter.disconnect(); manager.unload()
        from gateway import status
        await asyncio.to_thread(status.flush_runtime_status)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hermes-source", type=Path, required=True)
    parser.add_argument("--port", type=int, default=18649)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="bellpull-planning-device-") as directory:
        asyncio.run(serve(args.hermes_source.resolve(), Path(directory), args.port))
