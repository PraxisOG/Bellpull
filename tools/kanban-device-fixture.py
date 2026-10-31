#!/usr/bin/env python3
"""Disposable native Kanban/API host. No scheduler, agent or personal data."""
import asyncio
import os
from pathlib import Path
import signal
import tempfile


async def serve(home):
    os.environ.update(HERMES_HOME=str(home), HERMES_KANBAN_HOME=str(home), HERMES_KANBAN_DB="",
                      BELLPULL_DEVICE_NAME="Kanban fixture desktop", BELLPULL_NTFY_URL="")
    from aiohttp import web
    from gateway.config import PlatformConfig
    from gateway.platforms.api_server import APIServerAdapter, body_limit_middleware
    from hermes_cli import kanban_db as kb
    from hermes_cli.kanban_db_connect import connect
    from plugins.bellpull.http import wire
    from plugins.bellpull.kanban import Boards
    conn = connect(db_path=kb.kanban_db_path(board="default"))
    try:
        task = kb.create_task(conn, title="Ship the mobile board", body="Review Kanban on the connected phone.", triage=True, board="default")
        kb.add_comment(conn, task, "Desktop", "Keep one board owner and one executor.")
    finally:
        conn.close()
    kb.create_board("garden", name="Garden projects")
    kb.create_board("mobile-editing", name="Mobile editing")
    state = dict(posts=0, dropped=0, drop_next=False, task_id=task, runs=0)

    @web.middleware
    async def transport(request, handler):
        if request.method == "POST" and request.path.startswith("/api/bellpull/kanban/"):
            state["posts"] += 1
            result = await handler(request)
            if state["drop_next"]:
                state["drop_next"] = False
                state["dropped"] += 1
                request.transport.close()
            return result
        return await handler(request)

    adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={"key": "bellpull-kanban-device-fixture"}))
    def no_inference(**kwargs):
        state["runs"] += 1
        raise AssertionError("Kanban must not invoke inference")
    adapter._create_agent = no_inference
    app = web.Application(middlewares=[body_limit_middleware, transport])
    for method, path, handler in adapter._http_route_table():
        app.router.add_route(method, path, handler)
    wire(app, adapter)

    async def control(request):
        error = adapter._check_auth(request)
        if error is not None:
            return error
        body = await request.json()
        command = body.get("action")
        if command == "drop":
            state["drop_next"] = True
        elif command == "edit":
            conn = connect(db_path=kb.kanban_db_path(board="default"))
            try:
                kb.add_comment(conn, task, "Desktop", "Concurrent desktop update")
            finally:
                conn.close()
        return web.json_response(dict(state, task=Boards().detail("default", task)["task"], snapshot=Boards().snapshot(body.get("board", "default"))))
    app.router.add_post("/fixture/kanban", control)
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, "127.0.0.1", 18658).start()
    print("READY http://127.0.0.1:18658", flush=True)
    stopped = asyncio.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        asyncio.get_running_loop().add_signal_handler(sig, stopped.set)
    try:
        await stopped.wait()
    finally:
        await runner.cleanup()
        adapter._run_idempotency_store.close()
        for database in adapter._session_dbs.values():
            database.close()


if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix="bellpull-kanban-device-") as directory:
        asyncio.run(serve(Path(directory)))
