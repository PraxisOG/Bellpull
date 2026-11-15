#!/usr/bin/env python3
"""Disposable stock Hermes + SQLite behind a fault proxy. No real model or profiles.

Loopback 18664 is intended for adb reverse; control routes exist only here.
"""
import asyncio
from functools import wraps
import inspect
import signal
import threading
import time
from aiohttp import web, ClientSession, ClientTimeout
from plugins.bellpull.tests.test_native_attachments import NativeAttachmentsTest


async def main():
    fixture = NativeAttachmentsTest()
    fixture.port = 18665
    await fixture.asyncSetUp()
    state = {"outage_until": 0.0, "runs": 0, "submissions": 0, "list_failures": 0}
    release = threading.Event()
    signature = inspect.signature(fixture.adapter._create_agent)

    @wraps(fixture.adapter._create_agent)
    def create(*args, **kwargs):
        arguments = signature.bind_partial(*args, **kwargs).arguments
        class Agent:
            session_id = arguments.get("session_id")
            model = arguments.get("requested_model")
            provider = arguments.get("requested_provider")
            def run_conversation(self, **values):
                state["runs"] += 1
                db = fixture.adapter._ensure_session_db()
                db.append_message(self.session_id, "user", values.get("persist_user_message", values["user_message"]))
                if not release.wait(120):
                    raise RuntimeError("Fixture run was not released")
                text = "The original remote run survived the connection outage."
                db.append_message(self.session_id, "assistant", text)
                return {"final_response": text, "completed": True}
        return Agent()
    fixture.adapter._create_agent = create
    streams = set()
    http = ClientSession(timeout=ClientTimeout(total=None, connect=5))

    async def proxy(request):
        if request.headers.get("Authorization") != "Bearer native-attachment-fixture-key":
            return web.json_response({"error": {"message": "Unauthorized"}}, status=401)
        if request.path == "/fixture/connection":
            action = (await request.json()).get("action")
            if action == "outage":
                state["outage_until"] = time.monotonic() + 30
                for transport in list(streams):
                    transport.abort()
            elif action == "fail-list":
                state["list_failures"] = 1
            elif action == "release":
                release.set()
            return web.json_response(state)
        if time.monotonic() < state["outage_until"]:
            return web.json_response({"error": {"message": "Synthetic network outage"}}, status=503)
        if request.path in {"/api/sessions", "/api/bellpull/sessions"} and request.method == "GET" and state["list_failures"]:
            state["list_failures"] -= 1
            return web.json_response({"error": {"message": "Synthetic list failure"}}, status=503)
        if request.path == "/v1/runs" and request.method == "POST":
            state["submissions"] += 1
        transport = request.transport
        try:
            async with http.request(request.method, "http://127.0.0.1:18665" + request.raw_path,
                                    headers={"Authorization": request.headers["Authorization"],
                                             "Content-Type": request.headers.get("Content-Type", "application/json"),
                                             **({"Last-Event-ID": request.headers["Last-Event-ID"]}
                                                if "Last-Event-ID" in request.headers else {})},
                                    data=await request.read()) as upstream:
                response = web.StreamResponse(status=upstream.status,
                    headers={"Content-Type": upstream.headers.get("Content-Type", "application/json")})
                await response.prepare(request)
                if "text/event-stream" in upstream.headers.get("Content-Type", ""):
                    streams.add(transport)
                async for chunk in upstream.content.iter_any():
                    await response.write(chunk)
                await response.write_eof()
                return response
        except (ConnectionError, asyncio.CancelledError):
            raise
        finally:
            streams.discard(transport)

    app = web.Application()
    app.router.add_route("*", "/{tail:.*}", proxy)
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, "127.0.0.1", 18664).start()
    stop = asyncio.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        asyncio.get_running_loop().add_signal_handler(sig, stop.set)
    print("READY connection fixture 18664", flush=True)
    try:
        await stop.wait()
    finally:
        release.set()
        await runner.cleanup()
        await http.close()
        await fixture.asyncTearDown()


if __name__ == "__main__":
    asyncio.run(main())
