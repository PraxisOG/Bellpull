"""Bounded, authenticated snapshots keep activity from shifting page boundaries."""
import asyncio
from collections import OrderedDict
import json
import time
import uuid

from aiohttp import web
from .artifacts import ArtifactError


class Snapshots:
    TTL = 600
    MAX_BYTES = 32 * 1024 * 1024

    def __init__(self, adapter):
        self.adapter = adapter
        self.values = OrderedDict()
        self.bytes = 0

    def close(self):
        self.values.clear()
        self.bytes = 0

    def _expire(self):
        now = time.monotonic()
        for key, value in list(self.values.items()):
            if now - value[0] > self.TTL:
                self.bytes -= value[3]
                del self.values[key]

    async def page(self, request, kind):
        self._expire()
        scope = self.adapter._run_idempotency_scope(request)
        source = request.query.get("source") or None
        session = request.match_info.get("session_id")
        identity = (scope, kind, source, session)
        maximum = 200 if kind == "sessions" else 500
        try:
            limit = int(request.query.get("limit", maximum))
            if not 1 <= limit <= maximum:
                raise ValueError()
        except ValueError:
            raise ArtifactError("Invalid page size.")
        cursor = request.query.get("cursor")
        if cursor:
            try:
                token, position = cursor.split(":")
                offset = int(position)
            except ValueError:
                raise ArtifactError("Invalid page cursor.")
            value = self.values.get(token)
            if value is None:
                raise ArtifactError("History snapshot expired. Refresh to load it again.", 410)
            if value[1] != identity:
                raise ArtifactError("History snapshot is unavailable.", 404)
            _, _, payload, _, resolved = value
            if offset < 0 or offset > len(payload):
                raise ArtifactError("Invalid page cursor.")
        else:
            database = await self.adapter._ensure_session_db_async()
            if database is None:
                raise ArtifactError("Session database is unavailable.", 503)
            if kind == "sessions":
                rows = await asyncio.to_thread(database.list_sessions_rich, source=source, limit=100001,
                    include_children=False, order_by_last_active=True, include_pinned=False)
                payload = [self.adapter._session_response(row) for row in rows]
                resolved = None
            else:
                _, rejection = await self.adapter._get_existing_session_or_404(session)
                if rejection is not None:
                    return rejection
                def transcript():
                    for _ in range(3):
                        tip = database.resolve_resume_session_id(session)
                        records = database.get_messages(tip, limit=100001)
                        if database.resolve_resume_session_id(session) == tip:
                            return tip, records
                    raise ArtifactError("Conversation was compressed while loading. Refresh to retry.", 409)
                resolved, rows = await asyncio.to_thread(transcript)
                payload = [self.adapter._message_response(row) for row in rows]
            size = len(json.dumps(payload).encode())
            if size > self.MAX_BYTES or len(payload) > 100000:
                raise ArtifactError("This history exceeds the bounded snapshot limit.", 413)
            while self.values and (len(self.values) >= 8 or self.bytes + size > self.MAX_BYTES):
                _, old = self.values.popitem(last=False)
                self.bytes -= old[3]
            token, offset = uuid.uuid4().hex, 0
            self.values[token] = (time.monotonic(), identity, payload, size, resolved)
            self.bytes += size
        data = payload[offset:offset + limit]
        following = offset + len(data)
        next_cursor = f"{token}:{following}" if following < len(payload) else None
        if kind == "sessions":
            body = {"object": "list", "data": data, "limit": limit, "offset": offset,
                "has_more": next_cursor is not None, "next_cursor": next_cursor}
        else:
            body = {"object": "list", "session_id": resolved, "data": data,
                "pagination": {"limit": limit, "offset": offset, "returned": len(data), "order": "oldest",
                    "next_cursor": next_cursor, "total": len(payload)}}
        return web.json_response(body, headers={"Cache-Control": "no-store"})
