"""Shared Canvas v1: authenticated HTTP/SSE and a native Hermes tool."""
import asyncio
from contextvars import ContextVar
import hashlib
import json
from pathlib import Path

from aiohttp import web
from .canvas_store import CanvasError, CanvasStore
from .principal import artifact_scope

_call = ContextVar("bellpull_canvas_call", default=None)


def capture_call(tool_name="", args=None, session_id="", tool_call_id="", turn_id="", api_request_id="", **kwargs):
    if tool_name == "bellpull_canvas":
        _call.set({"session": session_id, "call": tool_call_id, "turn": turn_id,
                   "args": json.dumps(args, sort_keys=True)})


def available():
    try:
        from tools.interrupt import is_interrupted
        from hermes_cli.plugins import PluginContext
        return callable(is_interrupted) and callable(getattr(PluginContext, "register_middleware", None))
    except ImportError: return False


def identify_execution(next_call, tool_name="", args=None, **context):
    if tool_name != "bellpull_canvas": return next_call(args)
    token = _call.set(None)
    try:
        capture_call(tool_name=tool_name, args=args, **context)
        return next_call(args)
    finally: _call.reset(token)


async def store_for(adapter):
    from hermes_constants import get_hermes_home
    home = Path(get_hermes_home())
    database = await adapter._ensure_session_db_async()
    if database is None: raise CanvasError("Session database is unavailable.", 503)
    return await asyncio.to_thread(CanvasStore, home, artifact_scope(home), database)


def routes(adapter, active=lambda: True):
    async def dispatch(request):
        try:
            store = await store_for(adapter)
            session = request.match_info["session_id"]
            if request.method == "POST":
                body, error = await adapter._read_json_body(request)
                if error is not None: return error
                result = await asyncio.to_thread(store.mutate, session, body)
            else: result = await asyncio.to_thread(store.snapshot, session)
            return web.json_response(result, headers={"Cache-Control": "no-store"})
        except (CanvasError, ValueError) as exc:
            return web.json_response({"error": {"message": str(exc), "code": "canvas_conflict" if getattr(exc, "status", 400) == 409 else "canvas_rejected"}}, status=getattr(exc, "status", 400))

    async def events(request):
        try:
            store = await store_for(adapter); session = request.match_info["session_id"]
            snapshot = await asyncio.to_thread(store.snapshot, session)
        except CanvasError as exc:
            return web.json_response({"error": {"message": str(exc)}}, status=exc.status)
        response = web.StreamResponse(headers={"Content-Type": "text/event-stream", "Cache-Control": "no-store", "X-Accel-Buffering": "no"})
        await response.prepare(request)
        async def emit(event):
            await response.write(("id: " + str(event["snapshot"]["cursor"]) + "\ndata: " + json.dumps(event) + "\n\n").encode())
        try:
            # Reconnect always starts from an atomic authoritative snapshot. No replayed
            # tool execution, and no unverified concatenation after an event gap.
            await emit({"event": "canvas.reset", "snapshot": snapshot})
            cursor = snapshot["cursor"]; ticks = 0
            while active() and request.transport and not request.transport.is_closing():
                rows = await asyncio.to_thread(store.events, session, cursor)
                for event in rows:
                    await emit(event); cursor = event["snapshot"]["cursor"]
                ticks += 1
                if ticks % 60 == 0: await response.write(b": keepalive\n\n")
                await asyncio.sleep(.15)
        except (ConnectionError, asyncio.CancelledError): pass
        return response
    base = "/api/bellpull/sessions/{session_id}/canvas"
    return [("GET", base, dispatch), ("POST", base, dispatch), ("GET", base + "/events", events)]


def execute(args, *, session_id="", **kwargs):
    from hermes_constants import get_hermes_home
    from hermes_state import SessionDB
    from tools.interrupt import is_interrupted
    database = None
    try:
        home = Path(get_hermes_home()); database = SessionDB(home / "state.db", read_only=True)
        store = CanvasStore(home, artifact_scope(home), database)
        if not isinstance(args, dict) or args.get("version") != 1: raise CanvasError("Canvas tool version must be 1.")
        body = {k: v for k, v in args.items() if k != "version"}
        if body.get("action") == "read":
            if set(body) != {"action"}: raise CanvasError("Read uses the current conversation; omit other fields.")
            return store.snapshot(session_id)
        identity = _call.get()
        if not identity or identity["session"] != session_id or not identity["call"] or not identity["turn"] or identity["args"] != json.dumps(args, sort_keys=True):
            raise CanvasError("Native tool-call identity is unavailable; no drawing was changed.", 503)
        if "operation_id" in body: raise CanvasError("Operation identity is assigned by Hermes.")
        body["operation_id"] = hashlib.sha256((identity["turn"] + ":" + identity["call"]).encode()).hexdigest()
        if body.get("action") == "create": body.setdefault("canvas_id", body["operation_id"][:32])
        return store.mutate(session_id, body, turn=identity["turn"], tool_call=identity["call"], interrupted=is_interrupted)
    except (CanvasError, OSError, ValueError) as exc:
        return {"error": str(exc), "status": getattr(exc, "status", 400), "instructions": "Read the canvas before retrying; never overwrite newer manual edits."}
    finally:
        if database is not None: database.close()


def register(ctx):
    # Execution middleware runs in the actual tool worker and carries native IDs.
    # A pre-tool hook alone may run in a different thread before parallel dispatch.
    if hasattr(ctx, "register_middleware"): ctx.register_middleware("tool_execution", identify_execution)
    ctx.register_tool(name="bellpull_canvas", toolset="bellpull", description="Draw and edit the shared Bellpull canvas in this conversation",
        schema={"name": "bellpull_canvas", "description":
            "Read and edit the live canvas shared by desktop and mobile in this EXISTING conversation. "
            "Always read first, then use its canvas_id (document.id) and exact base_revision (document.revision). "
            "Call create if no drawing exists. Use edit for one or a few visible steps per call so the user sees progress before your final reply. "
            "Never print canvas JSON in chat. Explain normally; drawing commands are shown in a collapsed Drawing section. "
            "Preserve unrelated objects and manual positions. Add nodes before connecting them; update existing IDs to move them. "
            "Shapes: rectangle, ellipse, note, text. Colors: sand, rose, sage, blue, white. Nodes need id, shape, label, x, y, width, height, color; "
            "width/height 40..2000, x/y -20000..20000. Edges need id, from, to, label, arrow (boolean). "
            "Operations use type: add-node {node}, update-node {id,changes}, remove-nodes {ids}, add-edge {edge}, "
            "update-edge {id,label,arrow}, remove-edge {id}, rename {name}. IDs use letters, digits, underscores, hyphens (1..80). "
            "On conflict, report that drawing paused; do not undo or overwrite manual work. Undo/redo require the user's request.",
            "parameters": {"type": "object", "properties": {
                "version": {"type": "integer", "enum": [1]},
                "action": {"type": "string", "enum": ["read", "create", "select", "edit", "undo", "redo"]},
                "canvas_id": {"type": "string"}, "base_revision": {"type": "integer", "minimum": 0},
                "name": {"type": "string"}, "operations": {"type": "array", "minItems": 1, "maxItems": 400, "items": {"type": "object"}}},
                "required": ["version", "action"], "additionalProperties": False}},
        handler=lambda args, **kwargs: json.dumps(execute(args, **kwargs)))
