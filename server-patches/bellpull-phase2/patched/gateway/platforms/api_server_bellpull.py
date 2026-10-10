"""Authenticated ntfy wake hints. Canonical run status/history remain authoritative.

Only opaque run/session identity and state traverse loopback ntfy. No API credentials,
message text, tool arguments or approval commands are published there.
"""
import asyncio
import base64
import hashlib
import json
import logging
from functools import partial
from aiohttp import ClientSession, ClientTimeout, web

logger = logging.getLogger(__name__)
NTFY = "http://127.0.0.1:2586"


def topic(scope):
    # ntfy allows at most 64 characters. Hex plus our prefix exceeded that limit.
    digest = hashlib.sha256(str(scope).encode()).digest()
    return "bellpull-" + base64.urlsafe_b64encode(digest).decode().rstrip("=")


def routes(adapter):
    return [("GET", "/api/bellpull/events", partial(events, adapter))]


async def events(adapter, request):
    error = adapter._check_auth(request)
    if error is not None:
        return error
    scope = adapter._run_idempotency_scope(request)
    url = f"{NTFY}/{topic(scope)}/json?since=10m&poll=0"
    async with ClientSession(timeout=ClientTimeout(total=None, connect=10, sock_read=90)) as client:
        async with client.get(url) as upstream:
            if upstream.status != 200:
                return web.json_response({"error": {"code": "notifications_unavailable", "message": "Notification relay unavailable"}}, status=503)
            response = web.StreamResponse(headers={"Content-Type": "text/event-stream", "Cache-Control": "no-cache", "X-Accel-Buffering": "no"})
            await response.prepare(request)
            try:
                async for line in upstream.content:
                    event = json.loads(line)
                    if event.get("event") != "message":
                        await response.write(b": keepalive\n\n")
                        continue
                    # Treat the broker as a hint, never as an execution/approval authority.
                    hint = json.loads(event.get("message", "{}"))
                    safe = {key: hint[key] for key in ("run_id", "session_id", "status") if isinstance(hint.get(key), str)}
                    await response.write(("event: bellpull.wake\ndata: " + json.dumps(safe) + "\n\n").encode())
            except (ConnectionError, asyncio.CancelledError):
                pass
            return response


def notify(adapter, run_id, status):
    if status.get("status") not in {"completed", "failed", "cancelled", "interrupted", "waiting_for_approval"}:
        return
    scope = getattr(adapter, "_run_owners", {}).get(run_id)
    loop = getattr(adapter, "_bellpull_loop", None)
    if not scope or loop is None or not loop.is_running():
        return
    identity = (status.get("status"), (status.get("approval") or {}).get("request_id"))
    seen = getattr(adapter, "_bellpull_notified", None)
    if seen is None:
        seen = adapter._bellpull_notified = {}
    if seen.get(run_id) == identity:
        return
    seen[run_id] = identity
    payload = {"run_id": run_id, "session_id": status.get("session_id", ""), "status": status["status"]}

    async def publish():
        for attempt in range(3):
            try:
                async with ClientSession(timeout=ClientTimeout(total=5)) as client:
                    async with client.post(f"{NTFY}/{topic(scope)}", data=json.dumps(payload).encode(), allow_redirects=False) as response:
                        if 200 <= response.status < 300:
                            return
            except Exception:
                pass
            await asyncio.sleep(attempt + 1)
        logger.warning("Bellpull wake hint could not be published; clients recover from run status/history")

    loop.call_soon_threadsafe(lambda: asyncio.create_task(publish()))
