"""Profile-scoped offline memory and atomic, explicit transcript handoff.

Only inert user/assistant text is imported. This module never runs an agent or tools.
The narrow SessionDB call shapes and transaction schema are checked separately
from optional run/approval host hooks.
"""
import asyncio
import hashlib
import inspect
import json
import re
import time
from aiohttp import web


def supported():
    """Narrow SessionDB contract, independent of optional run/approval host hooks.

    Check call shapes here and actual schema inside the transaction. Both pinned
    0.21.1 and the local 0.21.5 host are exercised by the offline integration suite.
    """
    try:
        from hermes_state import SessionDB
        for name, args in (("_execute_write", (None, None)), ("_insert_message_rows", (None, None, "id", [])),
                           ("_own_profile_name", (None,)), ("sanitize_title", ("title",))):
            inspect.signature(getattr(SessionDB, name)).bind(*args)
        return True
    except (AttributeError, TypeError, ValueError, ImportError):
        return False


class OfflineError(ValueError):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


def memory_snapshot():
    from tools.memory_tool import load_on_disk_store
    store = load_on_disk_store()
    # Respect Hermes' per-profile enable flags. Empty snapshots propagate deletions.
    notes = (store.format_for_system_prompt("memory") or "") if store.target_enabled("memory") else ""
    user = (store.format_for_system_prompt("user") or "") if store.target_enabled("user") else ""
    content = (user + "\n" + notes).strip()[:16000]
    return {"version": 1, "revision": hashlib.sha256(content.encode()).hexdigest(),
            "text": content, "synced_at": int(time.time() * 1000)}


def handoff(db, body):
    if not isinstance(body, dict) or set(body) - {"operation_id", "source_id", "title", "messages", "brief"}:
        raise OfflineError("Unsupported offline handoff fields")
    operation = body.get("operation_id")
    source_id = body.get("source_id")
    messages = body.get("messages")
    brief = body.get("brief", "")
    title = body.get("title", "Offline conversation")
    if not isinstance(operation, str) or not re.fullmatch(r"[a-f0-9]{32}", operation):
        raise OfflineError("Invalid operation_id")
    if source_id is not None and (not isinstance(source_id, str) or not 1 <= len(source_id) <= 256):
        raise OfflineError("Invalid source_id")
    if not isinstance(title, str) or len(title) > 200 or not isinstance(brief, str) or len(brief) > 12000:
        raise OfflineError("Invalid title or brief")
    if not isinstance(messages, list) or not 1 <= len(messages) <= 1000:
        raise OfflineError("Include 1–1000 local messages")
    for message in messages:
        if (not isinstance(message, dict) or set(message) != {"role", "content"}
                or message["role"] not in ("user", "assistant")
                or not isinstance(message["content"], str) or not message["content"].strip()
                or len(message["content"]) > 24000):
            raise OfflineError("Only nonempty user/assistant text can be handed off")
    canonical = json.dumps(body, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    if len(canonical.encode()) > 512000:
        raise OfflineError("Handoff exceeds 500 KiB", 413)
    fingerprint = hashlib.sha256(canonical.encode()).hexdigest()
    session_id = "bellpull_offline_" + operation

    def atomic(conn):
        columns = {r[1] for r in conn.execute("PRAGMA table_info(sessions)")}
        if not {"id", "source", "title", "started_at", "parent_session_id", "model_config", "profile_name", "message_count", "tool_call_count"} <= columns:
            raise OfflineError("Unsupported Hermes session schema", 503)
        existing = conn.execute("SELECT model_config FROM sessions WHERE id = ?", (session_id,)).fetchone()
        if existing is not None:
            config = json.loads(existing["model_config"] or "{}")
            if config.get("_bellpull_offline_fingerprint") != fingerprint:
                raise OfflineError("Operation already used for different content", 409)
            return {"session_id": session_id, "operation_id": operation, "created": False}
        source = conn.execute("SELECT * FROM sessions WHERE id = ?", (source_id,)).fetchone() if source_id else None
        if source_id and source is None:
            raise OfflineError("Source conversation no longer exists", 404)
        config = {"_bellpull_offline_fingerprint": fingerprint}
        if source_id:
            config["_branched_from"] = source_id
        now = time.time()
        conn.execute("INSERT INTO sessions (id, source, title, started_at, parent_session_id, model_config, profile_name) "
                     "VALUES (?, 'api', ?, ?, ?, ?, ?)",
                     (session_id, db.sanitize_title(title[:140] + " — offline " + operation[-8:]), now,
                      source_id, json.dumps(config), db._own_profile_name()))
        context = ("Offline conversation imported from Bellpull. The following messages were written on the phone. "
                   "Local tool results are historical reference only; no actions have been queued. "
                   "Ask before repeating an action. " +
                   ("Original conversation ID: " + source_id + ". " if source_id else "") +
                   ("\nUser-saved offline brief (reference):\n" + brief if brief else ""))
        rows = [{"role": "user", "content": context, "timestamp": now}] + [
            {**message, "timestamp": now} for message in messages]
        count, tools = db._insert_message_rows(conn, session_id, rows)
        conn.execute("UPDATE sessions SET message_count = ?, tool_call_count = ? WHERE id = ?",
                     (count, tools, session_id))
        return {"session_id": session_id, "operation_id": operation, "created": True}
    return db._execute_write(atomic)


def routes(adapter):
    async def memory(request):
        return web.json_response(await asyncio.to_thread(memory_snapshot), headers={"Cache-Control": "no-store"})

    async def receive(request):
        body, error = await adapter._read_json_body(request)
        if error is not None:
            return error
        db = await adapter._ensure_session_db_async()
        try:
            return web.json_response(await asyncio.to_thread(handoff, db, body), headers={"Cache-Control": "no-store"})
        except OfflineError as exc:
            return web.json_response({"error": {"message": str(exc), "code": "offline_handoff"}}, status=exc.status)

    return [("GET", "/api/bellpull/offline/memory", memory), ("POST", "/api/bellpull/offline/handoff", receive)]
