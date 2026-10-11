"""Atomic prefix branching using the pinned Hermes SessionDB contract.
Copied native database interactions retain the MIT notice in LICENSE.hermes-agent.
"""
import asyncio, json, re, time, uuid
from aiohttp import web
from gateway.platforms.api_server import _error_response
from .models_files import store_for

async def _handle_fork_at_message(adapter, request: "web.Request") -> "web.Response":
    """Selected-reply fork. A distinct route fails closed on older gateways."""
    source_id = request.match_info["session_id"]
    _, err = await adapter._get_existing_session_or_404(source_id)
    if err:
        return err
    body, err = await adapter._read_json_body(request)
    if err:
        return err
    fork_id = str(body.get("id") or body.get("session_id") or f"api_{int(time.time())}_{uuid.uuid4().hex[:8]}").strip()
    if not fork_id or re.search(r'[\r\n\x00]', fork_id):
        return _error_response("Invalid session ID", 400, code="invalid_session_id")
    db = await adapter._ensure_session_db_async()
    return await _fork_at_message(adapter, db, source_id, fork_id, body, artifact_store=store_for(adapter, request))

async def _handle_fork_before_message(adapter, request: "web.Request") -> "web.Response":
    """Selected-reply fork. A distinct route fails closed on older gateways."""
    source_id = request.match_info["session_id"]
    _, err = await adapter._get_existing_session_or_404(source_id)
    if err:
        return err
    body, err = await adapter._read_json_body(request)
    if err:
        return err
    fork_id = str(body.get("id") or body.get("session_id") or f"api_{int(time.time())}_{uuid.uuid4().hex[:8]}").strip()
    if not fork_id or re.search(r'[\r\n\x00]', fork_id):
        return _error_response("Invalid session ID", 400, code="invalid_session_id")
    db = await adapter._ensure_session_db_async()
    return await _fork_at_message(adapter, db, source_id, fork_id, body, before=True, artifact_store=store_for(adapter, request))

async def _fork_at_message(adapter, db, source_id: str, fork_id: str, body: dict, *, before=False, artifact_store=None):
    """Copy the selected transcript prefix atomically; leave the source untouched.

    The native _branched_from marker keeps the copy visible in normal session lists
    and prevents resume resolution from redirecting the original to its new branch.
    """
    field = "before_message_id" if before else "through_message_id"
    cutoff = body.get(field)
    if isinstance(cutoff, bool) or not isinstance(cutoff, int) or cutoff <= 0:
        return _error_response(f"{field} must be a positive integer", 400,
                               code="invalid_branch_message")
    from gateway.session import _is_path_unsafe
    if _is_path_unsafe(fork_id) or len(fork_id) > adapter._MAX_SESSION_HEADER_LEN:
        return _error_response("Invalid session ID", 400, code="invalid_session_id")
    resolved_id = await asyncio.to_thread(db.resolve_resume_session_id, source_id)

    def _atomic(conn):
        source = conn.execute("SELECT * FROM sessions WHERE id = ?", (resolved_id,)).fetchone()
        if source is None:
            return None, "session_not_found", {}
        if conn.execute("SELECT 1 FROM sessions WHERE id = ?", (fork_id,)).fetchone():
            return None, "session_exists", {}
        rows = conn.execute(
            "SELECT * FROM messages WHERE session_id = ? AND active = 1 AND id <= ? ORDER BY id",
            (resolved_id, cutoff)).fetchall()
        if not rows or rows[-1]["id"] != cutoff or rows[-1]["role"] != ("user" if before else "assistant"):
            return None, "invalid_branch_message", {}
        messages = [db._row_to_message_dict(row, warn_context="fork_at_message", summary_flag=True)
                    for row in rows]
        if not isinstance(messages[-1].get("content"), str) or not messages[-1]["content"].strip():
            return None, "invalid_branch_message", {}
        if before:
            messages = messages[:-1]
        # These are new rows in a newly created conversation. Copying old timestamps
        # would bury a fresh branch outside the sidebar's most-recent page.
        created_at = time.time()
        for message in messages:
            message["timestamp"] = created_at
        # An assistant may speak before requesting tools. A prefix ending there must
        # not carry requests whose results occur after the selected message.
        if messages and messages[-1].get("tool_calls"):
            messages[-1]["tool_calls"] = None
            messages[-1]["api_content"] = None
            messages[-1]["codex_message_items"] = None
            messages[-1]["finish_reason"] = "stop"
        config = adapter._parse_session_model_config(source["model_config"])
        config.pop("_delegate_from", None)
        config.pop("_reset_from", None)
        config.update({"_branched_from": source_id,
                       "_branched_before_message_id" if before else "_branched_at_message_id": cutoff})
        title = body.get("title")
        if title is None:
            base = source["title"] or "Conversation"
            title = f"{base[:db.MAX_TITLE_LENGTH - 24]} — branch {fork_id[-8:]}"
        title = db.sanitize_title(str(title))
        if title and conn.execute("SELECT 1 FROM sessions WHERE title = ?", (title,)).fetchone():
            return None, "invalid_title", {}
        conn.execute(
            "INSERT INTO sessions (id, source, user_id, model, model_config, system_prompt, system_prompt_hash, parent_session_id, "
            "title, started_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (fork_id, source["source"], source["user_id"], source["model"], json.dumps(config), source["system_prompt"], source["system_prompt_hash"],
             source_id, title, created_at))
        count, tool_count = db._insert_message_rows(conn, fork_id, messages)
        conn.execute("UPDATE sessions SET message_count = ?, tool_call_count = ? WHERE id = ?",
                     (count, tool_count, fork_id))
        row = conn.execute(
            "SELECT s.*, COALESCE(sp.prompt, s.system_prompt) AS _system_prompt_resolved "
            "FROM sessions s LEFT JOIN system_prompts sp ON sp.hash = s.system_prompt_hash WHERE s.id = ?",
            (fork_id,)).fetchone()
        copied = conn.execute("SELECT id FROM messages WHERE session_id = ? AND active = 1 ORDER BY id", (fork_id,)).fetchall()
        mapping = {old["id"]: new["id"] for old, new in zip(messages, copied)}
        return db._session_row_dict(row), None, mapping

    fork, error, mapping = await asyncio.to_thread(db._execute_write, _atomic)
    if error:
        status = 404 if error == "session_not_found" else 409 if error == "session_exists" else 400
        message = {"invalid_branch_message": "Choose an assistant message from the current saved transcript.",
                   "invalid_title": "Branch title is already in use.",
                   "session_exists": "Branch session already exists.",
                   "session_not_found": "Source session no longer exists."}[error]
        return _error_response(message, status, code=error)
    if artifact_store is not None:
        try:
            await asyncio.to_thread(artifact_store.inherit_prefix, source_id, fork_id, mapping)
            if source_id != resolved_id:
                await asyncio.to_thread(artifact_store.inherit_prefix, resolved_id, fork_id, mapping)
        except (OSError, ValueError):
            artifact_store.warning(fork_id, "branch-files", "Branch created; attachment links need reconciliation. Original files remain available.")
    from .projects import store_for as projects_for
    try:
        projects = await projects_for(adapter)
        await asyncio.to_thread(projects.inherit, source_id, fork_id)
    except (OSError, ValueError):
        # The native branch already committed. A later project snapshot can repair
        # inheritance from its explicit native marker without repeating the fork.
        if artifact_store is not None:
            artifact_store.warning(fork_id, "branch-project", "Branch created; project membership will reconcile on refresh.")
    return web.json_response({"object": "hermes.session", "session": adapter._session_response(fork)}, status=201)
