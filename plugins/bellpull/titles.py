"""Conditional voice titles: automatic repair never outranks a manual rename."""
import asyncio
from aiohttp import web


def supported():
    try:
        from hermes_state import SessionDB
        return all(callable(getattr(SessionDB, name, None)) for name in
                   ("_execute_write", "sanitize_title", "get_session_title_source", "set_auto_title"))
    except ImportError:
        return False


def repair(db, session_id, body):
    if not isinstance(body, dict) or set(body) != {"title"} or not isinstance(body["title"], str):
        raise ValueError("Provide only a title")
    title = db.sanitize_title(body["title"])
    if not title or len(title) > 200:
        raise ValueError("Provide a title of 1–200 characters")

    def atomic(conn):
        columns = {r[1] for r in conn.execute("PRAGMA table_info(sessions)")}
        if "title_source" not in columns:
            raise ValueError("This Hermes version does not support conditional titles")
        current = conn.execute("SELECT title, title_source FROM sessions WHERE id = ?", (session_id,)).fetchone()
        if current is None:
            raise LookupError("Conversation no longer exists")
        # Unknown legacy provenance is treated as user-authored. A model-generated
        # title also wins over the simple first-utterance title supplied by Bellpull.
        if current["title"] and current["title_source"] != "derived":
            return {"updated": False, "title": current["title"]}
        chosen = title
        if conn.execute("SELECT 1 FROM sessions WHERE title = ? AND id != ?", (chosen, session_id)).fetchone():
            import hashlib
            chosen = title[:180] + " · " + hashlib.sha256(session_id.encode()).hexdigest()[:12]
        changed = conn.execute(
            "UPDATE sessions SET title = ?, title_source = 'derived' "
            "WHERE id = ? AND title IS ? AND title_source IS ?",
            (chosen, session_id, current["title"], current["title_source"])).rowcount
        return {"updated": bool(changed), "title": chosen}
    return db._execute_write(atomic)


def routes(adapter):
    async def receive(request):
        session_id = request.match_info["session_id"]
        _, error = await adapter._get_existing_session_or_404(session_id)
        if error is not None:
            return error
        body, error = await adapter._read_json_body(request)
        if error is not None:
            return error
        db = await adapter._ensure_session_db_async()
        try:
            value = await asyncio.to_thread(repair, db, session_id, body)
            return web.json_response(value, headers={"Cache-Control": "no-store"})
        except (ValueError, LookupError) as exc:
            return web.json_response({"error": {"code": "voice_title", "message": str(exc)}},
                                     status=404 if isinstance(exc, LookupError) else 400)
    return [("POST", "/api/bellpull/sessions/{session_id}/voice-title", receive)]
