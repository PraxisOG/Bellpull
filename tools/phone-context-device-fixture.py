#!/usr/bin/env python3
"""Disposable loopback host: real plugin/API, synthetic inference, no scheduler."""
import asyncio
import os
from pathlib import Path
import signal
import tempfile


async def serve(home):
    os.environ.update(HERMES_HOME=str(home), BELLPULL_NTFY_URL="")
    from aiohttp import web
    from gateway.config import PlatformConfig
    from gateway.platforms.api_server import APIServerAdapter, body_limit_middleware
    from plugins.bellpull.http import wire
    from plugins.bellpull.phone_context import ContextStore
    from unittest.mock import AsyncMock
    adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={"key": "bellpull-context-device-fixture"}))
    adapter._conversation_history_for_session = AsyncMock(return_value=[])
    adapter._request_route_conflict_error = lambda **kwargs: None
    state = {"runs": 0, "prompt": ""}
    def create(session_id=None, ephemeral_system_prompt=None, **kwargs):
        state.update(runs=state["runs"]+1, prompt=ephemeral_system_prompt or "")
        class Agent:
            def run_conversation(self, user_message, **kwargs):
                database = adapter._ensure_session_db()
                database.append_message(session_id, "user", user_message)
                database.append_message(session_id, "assistant", "Synthetic context fixture completed.")
                return {"final_response": "Synthetic context fixture completed."}
        agent = Agent(); agent.session_id = session_id
        return agent
    adapter._create_agent = create
    app = web.Application(middlewares=[body_limit_middleware])
    for method, path, handler in adapter._http_route_table():
        app.router.add_route(method, path, handler)
    wire(app, adapter)
    async def inspect(request):
        error = adapter._check_auth(request)
        if error is not None: return error
        return web.json_response(dict(state, context=ContextStore(home).search("")))
    app.router.add_get("/fixture/context", inspect)
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, "127.0.0.1", 18655).start()
    print("READY http://127.0.0.1:18655", flush=True)
    stopped = asyncio.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        asyncio.get_running_loop().add_signal_handler(sig, stopped.set)
    try: await stopped.wait()
    finally:
        await runner.cleanup()
        adapter._run_idempotency_store.close()
        for database in adapter._session_dbs.values(): database.close()


if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix="bellpull-context-device-") as directory:
        asyncio.run(serve(Path(directory)))
