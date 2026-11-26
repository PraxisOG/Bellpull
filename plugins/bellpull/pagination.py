"""Bounded, authenticated snapshots keep activity from shifting page boundaries."""
import asyncio
from collections import OrderedDict
import json
import time
import uuid

from aiohttp import web
from .artifacts import ArtifactError


def message_response(adapter, row):
    """Retain the host's safe projection and expose only delivery identity metadata.

    Native message responses omit display_metadata. Clients need the stable unit
    identity to reconcile background dispatches without submitting another turn.
    """
    result = adapter._message_response(row)
    if result.get("display_kind") == "async_delegation_complete":
        metadata = row.get("display_metadata") or {}
        if isinstance(metadata, str):
            try:
                metadata = json.loads(metadata)
            except (ValueError, TypeError):
                metadata = {}
        if isinstance(metadata, dict):
            for source, target in (("delegation_id", "delegation_id"), ("delivery_notice", "delegation_notice")):
                value = metadata.get(source)
                if isinstance(value, str) and 0 < len(value) <= 200:
                    result[target] = value
    return result


def persistence_history_supported(adapter):
    """Check the read contract independently of the project's session-write APIs."""
    if not all(callable(getattr(adapter, name, None)) for name in
               ("_ensure_store_async", "_store_beside", "_room_grant_token")):
        return False
    try:
        from gateway.platforms.api_server import _session_row
        from persistence.conversation.messages import TranscriptRead, TranscriptView
        from persistence.conversation.sessions import SessionOrder, SessionQuery, SessionWindow
    except ImportError:
        return False
    return all(callable(item) for item in (_session_row, TranscriptRead, SessionQuery, SessionWindow)) and (
        hasattr(TranscriptView, "ACTIVE") and hasattr(SessionOrder, "LAST_ACTIVE"))


async def legacy_snapshot(adapter, kind, session, source):
    database = await adapter._ensure_session_db_async()
    if database is None:
        raise ArtifactError("Session database is unavailable.", 503)
    if kind == "sessions":
        rows = await asyncio.to_thread(database.list_sessions_rich, source=source, limit=100001,
            include_children=False, order_by_last_active=True, include_pinned=False)
        return None, [adapter._session_response(row) for row in rows]

    def transcript():
        for _ in range(3):
            tip = database.resolve_resume_session_id(session)
            records = database.get_messages(tip, limit=100001)
            if database.resolve_resume_session_id(session) == tip:
                return tip, records
        raise ArtifactError("Conversation was compressed while loading. Refresh to retry.", 409)
    tip, rows = await asyncio.to_thread(transcript)
    return tip, [message_response(adapter, row) for row in rows]


async def persistence_snapshot(adapter, kind, session, source):
    from gateway.platforms.api_server import _session_row
    from persistence.conversation.messages import TranscriptRead, TranscriptView
    from persistence.conversation.sessions import SessionOrder, SessionQuery, SessionWindow

    store = adapter._store_beside(await adapter._ensure_store_async())
    if store is None:
        raise ArtifactError("Session database is unavailable.", 503)
    if kind == "sessions":
        query = SessionQuery(sources=(source,) if source else None, include_children=False,
                             order=SessionOrder.LAST_ACTIVE, include_pinned=False)
        entries = await store.aio.sessions.list_sessions(query, SessionWindow(limit=100001))
        return None, [adapter._session_response(_session_row(entry.session, entry)) for entry in entries]

    read = TranscriptRead(view=TranscriptView.ACTIVE, limit=100001, latest=False, include_lineage=True)
    for _ in range(3):
        tip = await store.aio.lineage.resolve_resume_target(session)
        rows = await store.aio.messages.read_transcript(tip, read)
        if await store.aio.lineage.resolve_resume_target(session) == tip:
            return tip, [message_response(adapter, row.as_wire_row()) for row in rows]
    raise ArtifactError("Conversation was compressed while loading. Refresh to retry.", 409)


class Snapshots:
    TTL = 600
    MAX_BYTES = 32 * 1024 * 1024

    def __init__(self, adapter):
        self.adapter = adapter
        self.uses_persistence = (not callable(getattr(adapter, "_ensure_session_db_async", None))
                                 and persistence_history_supported(adapter))
        self.values = OrderedDict()
        self.bytes = 0
        self.closed = False

    def close(self):
        self.closed = True
        self.values.clear()
        self.bytes = 0

    def _expire(self):
        now = time.monotonic()
        for key, value in list(self.values.items()):
            if now - value[0] > self.TTL:
                self.bytes -= value[3]
                del self.values[key]

    async def page(self, request, kind):
        if self.closed:
            raise ArtifactError("Bellpull was unloaded; restart the gateway.", 503)
        if self.uses_persistence and self.adapter._room_grant_token(request):
            raise ArtifactError("Account-level API credentials are required for history snapshots.", 403)
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
            if kind == "messages":
                _, rejection = await self.adapter._get_existing_session_or_404(session)
                if rejection is not None:
                    return rejection
            if self.uses_persistence:
                resolved, payload = await persistence_snapshot(self.adapter, kind, session, source)
            else:
                resolved, payload = await legacy_snapshot(self.adapter, kind, session, source)
            size = len(json.dumps(payload).encode())
            if size > self.MAX_BYTES or len(payload) > 100000:
                raise ArtifactError("This history exceeds the bounded snapshot limit.", 413)
            while self.values and (len(self.values) >= 8 or self.bytes + size > self.MAX_BYTES):
                _, old = self.values.popitem(last=False)
                self.bytes -= old[3]
            token, offset = uuid.uuid4().hex, 0
            if self.closed:
                raise ArtifactError("Bellpull was unloaded; restart the gateway.", 503)
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
