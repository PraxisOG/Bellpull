"""Authenticated plugin-owned endpoints. No private adapter replacement."""
import asyncio
from functools import partial
import json
import os
from pathlib import Path
import time

from aiohttp import web

from . import VERSION, branches, models_files, streaming, projects, planning
from .artifacts import ArtifactError, MAX_BYTES
from .notifications import Relay
from .pagination import Snapshots
from .telemetry import Sampler
from .compatibility import source_mode


def wire(app, adapter):
    from hermes_cli import __version__
    required = ("_expected_api_key", "_check_auth", "_run_idempotency_scope", "_ensure_session_db_async",
                "_get_existing_session_or_404", "_session_response", "_read_json_body")
    mode = source_mode()
    compatible = mode is not None and __version__ == "0.21.1" and all(callable(getattr(adapter, key, None)) for key in required)
    # File storage has no dependency on private run admission or a particular
    # core source revision. Keep the narrower contract separate from branches.
    files_ready = all(callable(getattr(adapter, key, None)) for key in
        ("_expected_api_key", "_check_auth", "_get_existing_session_or_404"))
    projects_ready = files_ready and all(callable(getattr(adapter, key, None)) for key in
        ("_ensure_session_db_async", "_read_json_body", "_handle_create_session", "_session_response"))
    try:
        from gateway.platforms import api_server_extensions as host
        host_ready = compatible and mode == "after" and host.API_VERSION == 1
    except ImportError:
        host = None
        host_ready = False
    relay = Relay(os.environ.get("BELLPULL_NTFY_URL", "http://127.0.0.1:2586"))
    sampler = Sampler()
    snapshots = Snapshots(adapter)
    reasoning_ready, stop_streaming = streaming.wire(adapter)
    housekeeping = {}
    active = True
    def stop():
        nonlocal active
        active = False
        stop_streaming()
        snapshots.close()
        relay.stop()
    async def close():
        stop_streaming()
        snapshots.close()
        await relay.close()

    def authenticated(handler):
        async def guarded(request):
            if not active:
                return web.json_response({"error": {"message": "Bellpull was unloaded; restart the gateway"}}, status=503)
            if not callable(getattr(adapter, "_expected_api_key", None)) or not callable(getattr(adapter, "_check_auth", None)):
                return web.json_response({"error": {"message": "Unsupported Hermes authentication contract"}}, status=503)
            if not adapter._expected_api_key():
                return web.json_response({"error": {"message": "API authentication is required"}}, status=401)
            rejection = adapter._check_auth(request)
            if rejection is not None:
                return rejection
            try:
                if "/api/bellpull/artifacts" in request.path:
                    store = models_files.store_for(adapter, request)
                    key = str(store.root)
                    if time.monotonic() - housekeeping.get(key, -3600) >= 3600:
                        await asyncio.to_thread(store.cleanup)
                        if len(housekeeping) >= 64:
                            housekeeping.clear()
                        housekeeping[key] = time.monotonic()
                return await handler(request)
            except ArtifactError as exc:
                return models_files.error(exc)
        return guarded

    async def capabilities(request):
        return web.json_response({"version": 1, "plugin_version": VERSION, "hermes_version": __version__,
            "host_api_version": host.API_VERSION if host_ready else None,
            "features": {"session_fork_at_message": compatible, "session_fork_before_message": compatible,
                "bellpull_persistent_artifacts": files_ready, "bellpull_model_controls": host_ready,
                "bellpull_run_attachments": host_ready,
                "bellpull_reasoning_streaming": reasoning_ready,
                "bellpull_notification_events": host_ready and bool(relay.url),
                "bellpull_snapshot_paging": compatible,
                "bellpull_projects": projects_ready,
                "bellpull_planning": projects_ready,
                "bellpull_routines": projects_ready and planning.cron_ready(),
                "bellpull_artifact_lifecycle": files_ready},
            "note": "" if host_ready else "Persistent file sharing is plugin-owned. Native image inputs and model overrides require the optional run extension."},
            headers={"Cache-Control": "no-store"})

    async def gpu(request):
        return web.json_response(await sampler.read(), headers={"Cache-Control": "no-store"})

    async def storage(request):
        store = models_files.store_for(adapter, request)
        if request.method == "DELETE":
            await asyncio.to_thread(store.delete, request.match_info["artifact_id"])
            return web.json_response({"deleted": True})
        return web.json_response(await asyncio.to_thread(store.usage), headers={"Cache-Control": "no-store"})

    async def events(request):
        return await relay.events(adapter._run_idempotency_scope(request), request)

    def prepare(request, body, session_id, text):
        if "bellpull" not in body:
            return None
        if not active:
            raise host.ExtensionError("Bellpull was unloaded; restart the gateway", 503)
        try:
            prepared = models_files.prepare_run(adapter, request, body, session_id, text)
        except ArtifactError as exc:
            raise host.ExtensionError(str(exc), exc.status) from exc
        store, folder = prepared["store"], prepared["output_folder"]
        database = adapter._ensure_session_db()
        resolved = database.resolve_resume_session_id(session_id)
        previous = database.get_messages(resolved, limit=1, latest=True)
        baseline = previous[-1]["id"] if previous else 0

        def start(run_id, run_session):
            store.output_directory(run_session, prepared["output_token"])
            store.attach(prepared["ids"], run_session, run_id)
            (folder / ".active").write_text(json.dumps({"run_id": run_id, "session_id": run_session}))

        def finish(run_id, run_session, result):
            try:
                if not folder.exists():
                    return
                store.collect_outputs(run_session, run_id, folder=folder)
                # Associate only unambiguous saved rows. Concurrent same-chat runs must
                # not assign each other's artifacts by guessing a timestamp.
                tip = database.resolve_resume_session_id(run_session)
                recent = [row for row in database.get_messages(tip) if row["id"] > baseline]
                user = [row for row in recent if row["role"] == "user" and row.get("content") == text]
                reply = [row for row in recent if row["role"] == "assistant" and result and row.get("content") == result.get("final_response")]
                with store._db() as db:
                    for source, candidates in (("upload", user), ("agent", reply)):
                        if len(candidates) == 1:
                            db.execute("UPDATE artifacts SET message_id = ? WHERE request_id = ? AND source = ?",
                                (candidates[0]["id"], run_id, source))
            except (OSError, ArtifactError) as exc:
                store.warning(run_id, "collection", str(exc) if isinstance(exc, ArtifactError) else "Generated outputs could not be collected.")
            finally:
                if folder.exists():
                    (folder / ".complete").touch()

        selected = prepared["selection"]
        return host.PreparedRun(prepared["content"], prepared["persist_text"], prepared["instructions"],
            {"requested_model": selected["model"], "requested_provider": selected["provider"],
             "model_options": selected["model_options"], "route": {"model": selected["model"], "provider": selected["provider"]},
             "confirmed_runtime_lock": True}, start, finish)

    routes = [("GET", "/api/bellpull/capabilities", capabilities)]
    if projects_ready:
        routes.extend(projects.routes(adapter))
        routes.extend(planning.routes(adapter))
    if files_ready:
        routes.extend([
            ("GET", "/api/bellpull/plugins/gpu", gpu),
            ("GET", "/api/bellpull/artifacts/storage", storage),
            ("DELETE", "/api/bellpull/artifacts/{artifact_id}", storage),
        ])
        routes.extend(models_files.routes(adapter, include_models=host_ready))
    if compatible:
        routes.extend([
            ("POST", "/api/sessions/{session_id}/fork-at-message", partial(branches._handle_fork_at_message, adapter)),
            ("POST", "/api/sessions/{session_id}/fork-before-message", partial(branches._handle_fork_before_message, adapter)),
            ("GET", "/api/bellpull/sessions", partial(snapshots.page, kind="sessions")),
            ("GET", "/api/bellpull/sessions/{session_id}/messages", partial(snapshots.page, kind="messages")),
        ])
    if host_ready:
        routes.append(("GET", "/api/bellpull/events", events))
    existing = {(route.method, route.resource.canonical) for route in app.router.routes()}
    collisions = [prefix + path for method, path, _ in routes for prefix in ("", "/p/{profile}")
                  if (method, prefix + path) in existing]
    if collisions:
        stop()
        raise RuntimeError("Bellpull routes already installed. Use the clean host image and disable the legacy bellpull_gpu plugin.")
    if host_ready:
        host.register(adapter, app, host.Hooks("bellpull", prepare, relay.status,
            models_files.request_overrides, close))
        for prefix in ("", "/p/{profile}"):
            host.register_body_limit(app, prefix + "/api/bellpull/artifacts/{artifact_id}", MAX_BYTES)
    else:
        async def cleanup(_app):
            await close()
        app.on_cleanup.append(cleanup)
    for method, path, handler in routes:
        for prefix in ("", "/p/{profile}"):
            app.router.add_route(method, prefix + path, authenticated(handler))
    return stop
