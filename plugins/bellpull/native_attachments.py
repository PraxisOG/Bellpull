"""Attachment handoff around stock Hermes' native, durable run admission.

The original request is never rewritten: Hermes fingerprints, replays, reserves,
executes and stops it. File validation runs at its lookup boundary, before a new
reservation and only after replay has been ruled out. ContextVars keep concurrent
requests and profiles separate. No core files or provider defaults are changed.
"""
from contextvars import ContextVar
from functools import wraps
import inspect
import json
from pathlib import Path
import re

from aiohttp import web
from . import models_files
from .artifacts import ArtifactError, checked_session


def wire(app, adapter):
    existing = getattr(adapter, "_bellpull_native_attachments", None)
    if existing is not None:
        existing[0]["active"] = True
        return True, existing[1]
    create = getattr(adapter, "_create_agent", None)
    if not callable(getattr(adapter, "_runtime_lock_error", None)) or not callable(getattr(adapter, "_session_runtime_request_from_body", None)):
        return False, lambda: None
    store = getattr(adapter, "_run_idempotency_store", None)
    try:
        from run_agent import AIAgent
        signature = inspect.signature(create)
        required = {"session_id", "ephemeral_system_prompt", "requested_model", "requested_provider", "model_options", "route", "confirmed_runtime_lock"}
        if not required.issubset(signature.parameters) or "persist_user_message" not in inspect.signature(AIAgent.run_conversation).parameters:
            return False, lambda: None
        lookup, reserve = store.lookup, store.reserve
        if not {"scope", "key", "fingerprint", "run_id"}.issubset(inspect.signature(reserve).parameters):
            return False, lambda: None
    except (AttributeError, ImportError, TypeError, ValueError):
        return False, lambda: None
    state = {"active": True}
    context = ContextVar("bellpull_attachment_request", default=None)
    reserve_signature = inspect.signature(reserve)

    def live_owner(session):
        # Stock Hermes must remain the sole writer of a leased Bot Chat. Its
        # desktop mailbox currently accepts text only, without attachment context.
        if not hasattr(adapter, "_admit_to_live_bot_chat"):
            return False
        from tools.bot_live_delivery import find_canonical_live_owner
        database = adapter._ensure_session_db()
        owner = find_canonical_live_owner(Path(database.db_path).parent)
        return owner is not None and database.get_compression_tip(session) == owner["session_id"]

    def prepare(entry):
        if not state["active"]:
            raise ArtifactError("Bellpull is reloading. Retry this same message after reconnecting.", 503)
        body, request = entry["body"], entry["request"]
        session = checked_session(body.get("session_id"))
        if entry["locked"]:
            models_files.check_run_lock(body, body["bellpull"].get("selection"))
            runtime = adapter._session_runtime_request_from_body(body)
            if adapter._runtime_lock_error(runtime) is not None:
                raise ArtifactError("Hermes could not resolve the selected model lock.", 409)
            entry["route"] = runtime["route"]
        if not isinstance(body.get("input"), str) or not body["input"].strip():
            raise ArtifactError("This Bellpull request needs a text message and an existing conversation.")
        if any(body.get(key) for key in ("hosted_room_dispatch", "_room_execution_policy", "previous_response_id", "conversation_history")):
            raise ArtifactError("This Bellpull request requires a direct conversation.")
        database = adapter._ensure_session_db()
        if not database.get_session(session):
            raise ArtifactError("Conversation not found.", 404)
        if live_owner(session):
            if not entry["files"]:
                raise ArtifactError("The active Bot Chat owner cannot confirm the selected model for this turn.", 409)
            raise ArtifactError("This Bot Chat is open on desktop. Close it there before sending attachments, or choose another conversation.", 409)
        entry["session"] = session
        if not entry["files"]:
            return
        prepared = models_files.prepare_run(adapter, request, body, session, body["input"])
        selected = prepared["selection"]
        if any(body.get(key) != selected[key] for key in ("model", "provider")) or body.get("model_options", {}) != selected["model_options"]:
            raise ArtifactError("Attachment and native model selections must match.", 409)
        # Reject already-used uploads before admission; attach() checks again
        # atomically when execution starts, closing concurrent reuse races.
        for identity in prepared["ids"]:
            if prepared["store"].path(identity, session)[1].get("request_id"):
                raise ArtifactError("Attachment was already sent. Choose the file again for another message.", 409)
        entry["prepared"] = prepared

    @wraps(lookup)
    def checked_lookup(*args, **kwargs):
        outcome, record = lookup(*args, **kwargs)
        entry = context.get()
        if entry is not None and outcome not in ("reused", "conflict"):
            prepare(entry)
        return outcome, record

    @wraps(reserve)
    def checked_reserve(*args, **kwargs):
        result = reserve(*args, **kwargs)
        entry = context.get()
        if entry is not None and result[0] == "created":
            entry["run_id"] = reserve_signature.bind(*args, **kwargs).arguments["run_id"]
        return result

    @wraps(create)
    def create_agent(*args, **kwargs):
        entry = context.get()
        if entry is None:
            return create(*args, **kwargs)
        if not state["active"] or not entry.get("session") or not entry.get("run_id"):
            raise ArtifactError("Bellpull run admission was interrupted. Review the failed run before retrying.", 503)
        if live_owner(entry["session"]):
            raise ArtifactError("The active Bot Chat owner cannot accept this model or attachment handoff.", 409)
        bound = signature.bind_partial(*args, **kwargs)
        if entry["locked"]:
            # Stock 0.21.5 drops this bit between durable admission and agent construction.
            # Use the host's checked route resolver and its native no-fallback behavior.
            bound.arguments["confirmed_runtime_lock"] = True
            bound.arguments["route"] = entry["route"]
        if not entry["files"]:
            return create(*bound.args, **bound.kwargs)
        prepared, run_id = entry["prepared"], entry["run_id"]
        actual_session = bound.arguments.get("session_id") or entry["session"]
        bound.arguments["ephemeral_system_prompt"] = (bound.arguments.get("ephemeral_system_prompt") or "") + "\n\n" + prepared["instructions"]
        agent = create(*bound.args, **bound.kwargs)
        original_run = agent.run_conversation
        database = adapter._ensure_session_db()
        previous = database.get_messages(actual_session, limit=1, latest=True)
        baseline = previous[-1]["id"] if previous else 0

        def execute(*run_args, **run_kwargs):
            artifact_store, folder = prepared["store"], prepared["output_folder"]
            # Uploads belong to the requested conversation even after native
            # compression resolves execution onto its current continuation.
            artifact_store.attach(prepared["ids"], entry["session"], run_id)
            artifact_store.output_directory(entry["session"], prepared["output_token"])
            (folder / ".active").write_text(json.dumps({"run_id": run_id, "session_id": actual_session}))
            run_kwargs.update(user_message=prepared["content"], persist_user_message=prepared["persist_text"])
            result = None
            try:
                result = original_run(*run_args, **run_kwargs)
                return result
            finally:
                try:
                    artifact_store.collect_outputs(entry["session"], run_id, folder=folder)
                    tip = database.resolve_resume_session_id(actual_session)
                    recent = [row for row in database.get_messages(tip) if row["id"] > baseline]
                    for source, role, text in (("upload", "user", prepared["persist_text"]),
                            ("agent", "assistant", result.get("final_response") if isinstance(result, dict) else None)):
                        candidates = [row for row in recent if row["role"] == role and row.get("content") == text]
                        if text and len(candidates) == 1:
                            with artifact_store._db() as db:
                                db.execute("UPDATE artifacts SET message_id=? WHERE request_id=? AND source=?",
                                           (candidates[0]["id"], run_id, source))
                except (OSError, ArtifactError):
                    artifact_store.warning(run_id, "collection", "Generated outputs could not be collected.")
                finally:
                    (folder / ".complete").touch()
        @wraps(original_run)
        def run_conversation(*run_args, **run_kwargs):
            try:
                return execute(*run_args, **run_kwargs)
            finally:
                # A native host may retain its agent for the session. Never leave
                # a prior turn's file handoff installed on a reusable instance.
                if agent.run_conversation is run_conversation:
                    agent.run_conversation = original_run
        agent.run_conversation = run_conversation
        return agent

    @web.middleware
    async def attachments(request, handler):
        if request.method != "POST" or not re.fullmatch(r"(?:/p/[^/]+)?/v1/runs", request.path):
            return await handler(request)
        try:
            body = await request.json()
        except (ValueError, UnicodeError):
            return await handler(request)
        options = body.get("bellpull") if isinstance(body, dict) else None
        if not isinstance(options, dict):
            return await handler(request)
        files = bool(options.get("artifact_ids"))
        locked = body.get("require_model_lock") is True
        if not files and not locked:
            return await handler(request)
        rejection = adapter._check_auth(request)
        if rejection is not None:
            return rejection
        if not adapter._expected_api_key():
            return models_files.error(ArtifactError("API authentication required.", 401))
        if not request.headers.get("Idempotency-Key", "").strip():
            return models_files.error(ArtifactError("Bellpull model locks and attachments require an Idempotency-Key."))
        token = context.set({"request": request, "body": body, "files": files, "locked": locked})
        try:
            return await handler(request)
        except ArtifactError as exc:
            return models_files.error(exc)
        finally:
            context.reset(token)

    # Keep one inactive pass-through wrapper on unload so already-admitted tasks
    # cannot silently execute without their files. Reload reuses this same bridge.
    adapter._create_agent = create_agent
    store.lookup, store.reserve = checked_lookup, checked_reserve
    admit = getattr(adapter, "_admit_to_live_bot_chat", None)
    if callable(admit):
        @wraps(admit)
        async def admit_with_attachments(*args, **kwargs):
            if context.get() is not None:
                # Preflight and agent creation both reject a live owner. Never
                # enqueue a text-only copy or race that owner's inference.
                return None
            return await admit(*args, **kwargs)
        adapter._admit_to_live_bot_chat = admit_with_attachments
    app.middlewares.append(attachments)

    def stop():
        state["active"] = False
    adapter._bellpull_native_attachments = state, stop
    return True, stop
