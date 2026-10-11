"""Scoped ntfy wake hints. Status/history remain the only authoritative records."""
import asyncio
import base64
from collections import OrderedDict
import hashlib
import json
import logging
from urllib.parse import urlparse

from aiohttp import ClientError, ClientSession, ClientTimeout, web

logger = logging.getLogger(__name__)


def topic(scope):
    return "bellpull-" + base64.urlsafe_b64encode(hashlib.sha256(str(scope).encode()).digest()).decode().rstrip("=")


class Relay:
    def __init__(self, url):
        self.url = url.rstrip("/")
        parsed = urlparse(self.url)
        if self.url and (parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "::1") or parsed.username or parsed.password):
            raise ValueError("Bellpull ntfy must use an unauthenticated loopback HTTP URL")
        self.loop = asyncio.get_running_loop()
        self.tasks = set()
        self.subscriptions = set()
        self.seen = OrderedDict()
        self.closed = False

    def status(self, event):
        if not self.url or self.closed or not event.get("scope") or event.get("status") not in {
                "completed", "failed", "cancelled", "interrupted", "waiting_for_approval"}:
            return
        self.loop.call_soon_threadsafe(self._enqueue, dict(event))

    def _enqueue(self, event):
        if self.closed:
            return
        key = (event["run_id"], event["status"], event.get("approval_request_id"))
        if key in self.seen:
            return
        if len(self.tasks) >= 64:
            logger.warning("Bellpull wake relay is busy; clients must reconcile canonical status")
            return
        self.seen[key] = True
        while len(self.seen) > 4096:
            self.seen.popitem(last=False)
        task = self.loop.create_task(self._publish(event))
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)

    async def _publish(self, event):
        payload = {key: event.get(key, "") for key in ("run_id", "session_id", "status")}
        for attempt in range(3):
            try:
                async with ClientSession(timeout=ClientTimeout(total=5)) as client:
                    async with client.post(f"{self.url}/{topic(event['scope'])}", data=json.dumps(payload).encode(), allow_redirects=False) as response:
                        if 200 <= response.status < 300:
                            return
            except (ClientError, ConnectionError, asyncio.TimeoutError):
                pass
            await asyncio.sleep(attempt + 1)
        logger.warning("Bellpull wake hint failed; clients must reconcile canonical status")

    async def events(self, scope, request):
        if not self.url:
            return web.json_response({"error": {"message": "Notification relay is not configured"}}, status=503)
        if self.closed:
            return web.json_response({"error": {"message": "Bellpull was unloaded; restart the gateway"}}, status=503)
        task = asyncio.current_task()
        self.subscriptions.add(task)
        try:
            return await self._events(scope, request)
        finally:
            self.subscriptions.discard(task)

    async def _events(self, scope, request):
        async with ClientSession(timeout=ClientTimeout(total=None, connect=10, sock_read=90)) as client:
            async with client.get(f"{self.url}/{topic(scope)}/json?since=10m&poll=0", allow_redirects=False) as upstream:
                if upstream.status != 200:
                    return web.json_response({"error": {"message": "Notification relay unavailable"}}, status=503)
                response = web.StreamResponse(headers={"Content-Type": "text/event-stream", "Cache-Control": "no-cache", "X-Accel-Buffering": "no"})
                await response.prepare(request)
                async for line in upstream.content:
                    event = json.loads(line)
                    if event.get("event") != "message":
                        await response.write(b": keepalive\n\n")
                        continue
                    hint = json.loads(event.get("message", "{}"))
                    safe = {key: hint[key] for key in ("run_id", "session_id", "status") if isinstance(hint.get(key), str)}
                    await response.write(("event: bellpull.wake\ndata: " + json.dumps(safe) + "\n\n").encode())
                return response

    def stop(self):
        self.closed = True
        for task in tuple(self.tasks | self.subscriptions):
            self.loop.call_soon_threadsafe(task.cancel)

    async def close(self):
        self.stop()
        await asyncio.gather(*(self.tasks | self.subscriptions), return_exceptions=True)
