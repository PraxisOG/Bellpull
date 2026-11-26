"""Publish scoped ntfy hints for stored native run-status receipts."""

import asyncio
import base64
import hashlib
import json
import logging
import math
import re
import sqlite3
from urllib.parse import urlparse

from aiohttp import ClientError, ClientSession, ClientTimeout, web

from .notification_journal import NotificationJournal

logger = logging.getLogger(__name__)
_DEFAULT_JOURNAL = object()


def topic(scope):
    return "bellpull-" + base64.urlsafe_b64encode(
        hashlib.sha256(str(scope).encode()).digest()
    ).decode().rstrip("=")


class Relay:
    def __init__(self, url, journal=_DEFAULT_JOURNAL):
        self.url = url.rstrip("/")
        parsed = urlparse(self.url)
        if self.url and (
            parsed.scheme != "http"
            or parsed.hostname not in ("127.0.0.1", "::1")
            or parsed.username
            or parsed.password
        ):
            raise ValueError("Bellpull ntfy must use an unauthenticated loopback HTTP URL")
        self.loop = asyncio.get_running_loop()
        automatic = journal is _DEFAULT_JOURNAL
        if automatic:
            from hermes_constants import get_hermes_home

            journal = NotificationJournal(get_hermes_home())
        self.journal = journal
        self.worker = None
        self.wake = asyncio.Event()
        self.subscriptions = set()
        self.closed = False
        if automatic:
            self.start()

    def start(self):
        if (
            self.journal is not None
            and self.url
            and not self.closed
            and (self.worker is None or self.worker.done())
        ):
            self.worker = self.loop.create_task(self._dispatch())

    def status(self, event):
        status = event.get("status")
        if (
            self.closed
            or self.journal is None
            or status not in ("completed", "failed", "cancelled", "interrupted", "waiting_for_approval")
        ):
            return
        run, session, scope = (event.get(key) for key in ("run_id", "session_id", "scope"))
        approval = (event.get("approval_request_id") or "") if status == "waiting_for_approval" else ""
        if not all(isinstance(x, str) and 0 < len(x) <= 512 for x in (run, session, scope)):
            return
        if not isinstance(approval, str) or len(approval) > 512:
            return
        occurred = event.get("updated_at")
        if type(occurred) not in (int, float) or not math.isfinite(occurred):
            occurred = None
        try:
            self.journal.record(topic(scope), run, session, status, approval, occurred)
        except (sqlite3.Error, OSError):
            logger.exception("Bellpull could not store a run notification receipt")
            return
        if not self.loop.is_closed():
            self.loop.call_soon_threadsafe(self.wake.set)

    async def _dispatch(self):
        async with ClientSession(timeout=ClientTimeout(total=5)) as client:
            while not self.closed:
                self.wake.clear()
                try:
                    claim = await asyncio.to_thread(self.journal.claim)
                    if claim:
                        status = None
                        try:
                            async with client.post(
                                f"{self.url}/{claim['topic']}",
                                json={"event_id": claim["event_id"]},
                                allow_redirects=False,
                            ) as response:
                                status = response.status
                        except (ClientError, asyncio.TimeoutError):
                            pass
                        await asyncio.to_thread(self.journal.finish, claim, status)
                        continue
                    delay = await asyncio.to_thread(self.journal.next_delay)
                except (OSError, sqlite3.Error):
                    logger.exception("Bellpull notification journal unavailable")
                    delay = 5
                try:
                    await asyncio.wait_for(self.wake.wait(), delay)
                except asyncio.TimeoutError:
                    pass

    async def history(self, scope, request):
        after = request.query.get("after", "0")
        limit = request.query.get("limit", "50")
        journal_id = request.query.get("journal_id")
        if (
            not re.fullmatch(r"[0-9]{1,18}", after)
            or not re.fullmatch(r"[0-9]{1,3}", limit)
            or not 1 <= int(limit) <= 100
            or (journal_id is not None and not re.fullmatch(r"[0-9a-f]{64}", journal_id))
        ):
            return web.json_response(
                {"error": {"message": "Invalid notification cursor or limit"}}, status=400
            )
        try:
            result = await asyncio.to_thread(self.journal.history, topic(scope), int(after), int(limit), journal_id)
        except (OSError, sqlite3.Error):
            return web.json_response(
                {"error": {"message": "Notification history is unavailable"}}, status=503
            )
        return web.json_response({"version": 1, **result}, headers={"Cache-Control": "no-store"})

    async def events(self, scope, request):
        if not self.url:
            return web.json_response(
                {"error": {"message": "Notification relay is not configured"}}, status=503
            )
        if self.closed:
            return web.json_response(
                {"error": {"message": "Bellpull was unloaded; restart the gateway"}}, status=503
            )
        task = asyncio.current_task()
        self.subscriptions.add(task)
        try:
            return await self._events(scope, request)
        finally:
            self.subscriptions.discard(task)

    async def _events(self, scope, request):
        async with ClientSession(timeout=ClientTimeout(total=None, connect=10, sock_read=90)) as client:
            async with client.get(
                f"{self.url}/{topic(scope)}/json?since=10m&poll=0", allow_redirects=False
            ) as upstream:
                if upstream.status != 200:
                    return web.json_response(
                        {"error": {"message": "Notification relay unavailable"}}, status=503
                    )
                response = web.StreamResponse(
                    headers={
                        "Content-Type": "text/event-stream",
                        "Cache-Control": "no-cache",
                        "X-Accel-Buffering": "no",
                    }
                )
                await response.prepare(request)
                async for line in upstream.content:
                    try:
                        event = json.loads(line)
                    except (ValueError, RecursionError):
                        continue
                    if not isinstance(event, dict):
                        continue
                    if event.get("event") != "message":
                        await response.write(b": keepalive\n\n")
                        continue
                    try:
                        hint = json.loads(event.get("message", "{}"))
                    except (ValueError, TypeError, RecursionError):
                        continue
                    if not isinstance(hint, dict):
                        continue
                    if "event_id" in hint:
                        event_id = hint["event_id"]
                        if (
                            self.journal is None
                            or not isinstance(event_id, str)
                            or not re.fullmatch(r"[a-f0-9]{64}", event_id)
                        ):
                            continue
                        hint = await asyncio.to_thread(self.journal.lookup, topic(scope), event_id)
                        if hint is None:
                            continue
                    safe = {
                        key: hint[key]
                        for key in ("event_id", "run_id", "session_id", "status")
                        if isinstance(hint.get(key), str)
                    }
                    await response.write(
                        ("event: bellpull.wake\ndata: " + json.dumps(safe) + "\n\n").encode()
                    )
                return response

    def stop(self):
        self.closed = True
        for task in tuple(self.subscriptions) + ((self.worker,) if self.worker else ()):
            self.loop.call_soon_threadsafe(task.cancel)

    async def close(self):
        self.stop()
        await asyncio.gather(
            *self.subscriptions, *((self.worker,) if self.worker else ()), return_exceptions=True
        )
