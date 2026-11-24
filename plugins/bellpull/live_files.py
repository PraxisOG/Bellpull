"""Agent-selected live documents. HTTP clients can address registered IDs only."""
import asyncio
import json
from pathlib import Path
from aiohttp import web
from .artifacts import ArtifactError, checked_session
from .live_files_store import LiveFiles
from .principal import artifact_scope
from . import shared_paths


def store():
    from hermes_constants import get_hermes_home
    home = Path(get_hermes_home())
    return LiveFiles(home, artifact_scope(home))


def reference(args, *, session_id="", **kwargs):
    from hermes_constants import get_hermes_home
    from hermes_state import SessionDB
    try:
        session_id = checked_session(session_id)
        database = SessionDB(Path(get_hermes_home()) / "state.db", read_only=True)
        try:
            if database.get_session(session_id) is None:
                raise ArtifactError("The active conversation is not saved yet.", 409)
        finally:
            database.close()
        if not isinstance(args, dict) or set(args) - {"path", "name", "access", "pinned"}:
            raise ArtifactError("Provide only path, name, access and pinned.")
        return store().reference(session=session_id, **args)
    except (ArtifactError, OSError, ValueError, TypeError) as exc:
        return {"error": str(exc)}


def register(ctx):
    if callable(getattr(ctx, "register_skill", None)):
        ctx.register_skill("live-files", Path(__file__).parent / "skills/live-files/SKILL.md",
                           description="Show server documents and editable lists in Bellpull.")
    ctx.register_tool(name="bellpull_file", toolset="bellpull",
        description="Show or refresh an explicitly selected server file in Bellpull",
        schema={"name": "bellpull_file", "description":
            "Add an existing UTF-8 Markdown or plain-text file to Bellpull Files, referencing the "
            "original path without copying it. Call again after changing the file to refresh the phone. "
            "Use only a path supplied by the user or already known from the requested work; do not search "
            "directories to discover files. New references are read-only. Set access=checklist only when "
            "the user requests permission to check/uncheck or add list items; access=read revokes that grant. "
            "Omitting access or pinned preserves the existing choice. Symlinks and hard links are unsupported.",
            "parameters": {"type": "object", "properties": {
                "path": {"type": "string", "description": "Absolute path to the existing file in this server's filesystem."},
                "name": {"type": "string", "description": "Short display title."},
                "access": {"type": "string", "enum": ["read", "checklist"]},
                "pinned": {"type": "boolean"}}, "required": ["path"], "additionalProperties": False}},
        handler=lambda args, **kwargs: json.dumps(reference(args, **kwargs)))


def routes(adapter):
    async def handle(request):
        if request.method == "GET" and request.match_info.get("file_id") is None and "path" in request.query:
            try:
                offset = int(request.query.get("offset", "0"))
                limit = int(request.query.get("limit", str(shared_paths.MAX_TEXT)))
            except ValueError:
                raise ArtifactError("Provide integer offset and limit values.")
            path = request.query["path"]
            def read_path():
                return shared_paths.snapshot(shared_paths.roots(), path, offset, limit)
            result = await asyncio.to_thread(read_path)
            headers = {"Cache-Control": "no-store", "ETag": result["etag"]}
            if request.headers.get("If-None-Match") == result["etag"]:
                return web.Response(status=304, headers=headers)
            return web.json_response(result, headers=headers)
        registry = await asyncio.to_thread(store)
        identity = request.match_info.get("file_id")
        headers = {"Cache-Control": "no-store"}
        if identity is None:
            result = await asyncio.to_thread(registry.list)
            result["path_roots"] = sorted(await asyncio.to_thread(shared_paths.roots))
        elif request.method == "GET":
            result = await asyncio.to_thread(registry.snapshot, identity)
            headers["ETag"] = result["etag"]
            if request.headers.get("If-None-Match") == result["etag"]:
                return web.Response(status=304, headers=headers)
        else:
            if request.content_length is not None and request.content_length > 16384:
                raise ArtifactError("List edit exceeds the request limit.", 413)
            raw = bytearray()
            async for chunk in request.content.iter_chunked(4096):
                raw.extend(chunk)
                if len(raw) > 16384: raise ArtifactError("List edit exceeds the request limit.", 413)
            try: body = json.loads(raw)
            except (ValueError, UnicodeError): raise ArtifactError("Provide a JSON object.")
            if not isinstance(body, dict): raise ArtifactError("Provide a JSON object.")
            if request.path.endswith("/pin"):
                if set(body) != {"pinned"}: raise ArtifactError("Provide pinned only.")
                result = await asyncio.to_thread(registry.pin, identity, body["pinned"])
            else:
                result = await asyncio.to_thread(registry.edit, identity, body)
                headers["ETag"] = result["etag"]
        return web.json_response(result, headers=headers)
    return [("GET", "/api/bellpull/files", handle), ("GET", "/api/bellpull/files/{file_id}", handle),
            ("POST", "/api/bellpull/files/{file_id}/pin", handle), ("POST", "/api/bellpull/files/{file_id}/edit", handle)]
