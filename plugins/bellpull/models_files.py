"""Opt-in Bellpull model controls and persistent, bearer-protected attachments."""
from __future__ import annotations

import asyncio
import base64
import json
import os
import stat
from pathlib import Path
import tempfile
import time
from urllib.parse import quote

from aiohttp import web
from .artifacts import (
    ArtifactError, ArtifactStore, MAX_BYTES, MAX_ATTACHMENTS, checked_name, checked_session, checked_type)


def error(exc):
    return web.json_response({"error": {"message": str(exc), "code": "bellpull_rejected"}}, status=exc.status)


def store_for(adapter, request):
    from hermes_constants import get_hermes_home
    from .principal import artifact_scope
    home = Path(get_hermes_home())
    return ArtifactStore(home, artifact_scope(home))


def reasoning_capabilities(provider, model):
    """Only expose values declared by the actual provider transport, not picker guesses."""
    efforts = local_qwen_efforts(provider, model)
    if efforts:
        return {"efforts": list(efforts), "can_disable": True, "can_enable": True,
                "note": "Qwen thinking effort guides reasoning depth; it is not a hard token limit."}
    from providers import get_provider_profile
    profile = get_provider_profile(provider)
    efforts = fireworks_efforts(model) if provider == "fireworks" else None
    if efforts is None:
        efforts = profile.supported_reasoning_efforts(model) if profile else None
    efforts = list(efforts) if efforts else []
    off = "none" in efforts or "off" in efforts
    return {"efforts": [value for value in efforts if value not in ("none", "off")],
            "can_disable": off,
            "note": "" if efforts else "This runtime does not declare supported reasoning controls; server defaults apply."}


def local_qwen_efforts(provider, model):
    # Exact known alias and official model IDs, not a guess for every Qwen model.
    # https://huggingface.co/Qwen/Qwen3.8-Flash-Next/blob/main/chat_template.jinja
    # Its template accepts precisely low/medium/xhigh (default), plus thinking off.
    if provider == "custom" and model in (
            "qwen38-flash-next", "Qwen/Qwen3.8-Flash-Next", "Qwen/Qwen3.8-Flash-Next-FP8"):
        return ("low", "medium", "xhigh")
    return None


def fireworks_efforts(model):
    # Verified provider contract, not a minimum-effort-as-off approximation:
    # https://docs.fireworks.ai/api-reference/post-chatcompletions#body-reasoning-effort
    name = str(model).rsplit("/", 1)[-1]
    if name.startswith("deepseek-v4"):
        return ("low", "high", "max", "none")
    if name in ("gpt-oss-120b", "gpt-oss-20b"):
        return ("low", "medium", "high")
    return None


def request_overrides(provider, model, options):
    """Fireworks requires top-level reasoning_effort; its generic profile drops reasoning.

    Only explicit per-session/request choices are translated. Server/global defaults
    and agents on other platforms are untouched.
    """
    if not isinstance(options, dict):
        return {}
    reasoning = options.get("reasoning")
    if not isinstance(reasoning, dict):
        return {}
    efforts = local_qwen_efforts(provider, model)
    if efforts:
        enabled, effort = reasoning.get("enabled"), reasoning.get("effort")
        if enabled is not None and not isinstance(enabled, bool):
            raise ArtifactError("Thinking must be enabled or disabled.", 409)
        if effort is not None and (effort not in efforts or enabled is False):
            raise ArtifactError("This Qwen model supports Off, On, Low, Medium or XHigh.", 409)
        kwargs = {"enable_thinking": enabled is not False}
        result = {"extra_body": {"chat_template_kwargs": kwargs}}
        if effort is not None:
            # The standard field reaches Hermes/vLLM; the identical template kwarg
            # also covers servers that do not forward the standard field to Jinja.
            result["reasoning_effort"] = kwargs["reasoning_effort"] = effort
        return result
    if provider != "fireworks" or not fireworks_efforts(model):
        return {}
    effort = "none" if reasoning.get("enabled") is False else reasoning.get("effort")
    if effort not in fireworks_efforts(model):
        raise ArtifactError("Unsupported reasoning choice for this Fireworks model.", 409)
    return {"reasoning_effort": effort}


def catalog(adapter):
    from hermes_cli.inventory import build_model_options_payload, load_picker_context
    from agent.models_dev import get_model_capabilities
    from gateway.run import _resolve_runtime_agent_kwargs, _resolve_gateway_model
    inventory = build_model_options_payload(load_picker_context(), include_unconfigured=True, refresh=False)
    runtime = _resolve_runtime_agent_kwargs()
    default_provider, default_model = runtime.get("provider"), _resolve_gateway_model()
    models = []
    for provider in inventory.get("providers", []):
        slug = provider.get("slug")
        # MoA is a virtual orchestration route, not a configured single-model provider.
        if not slug or slug == "moa":
            continue
        configured = provider.get("authenticated") is True
        ids = list(provider.get("models", []))
        if slug == default_provider and default_model not in ids:
            ids.insert(0, default_model)
        for model in ids:
            if not isinstance(model, str) or not model:
                continue
            caps = get_model_capabilities(slug, model) or {}
            models.append({"provider": slug, "model": model, "configured": configured,
                           "vision": (caps.get("supports_vision") if isinstance(caps, dict) else getattr(caps, "supports_vision", None)) is True,
                           "reasoning": reasoning_capabilities(slug, model)})
    return {"version": 1, "models": models, "default": {
        "provider": default_provider, "model": default_model, "model_options": {}},
        "note": "Configured provider catalog. Availability is confirmed when the provider accepts a request; models are not loaded by this picker."}


def validate_selection(value, available):
    if not isinstance(value, dict) or set(value) - {"model", "provider", "model_options"}:
        raise ArtifactError("Invalid model selection.")
    route = next((item for item in available["models"] if item["model"] == value.get("model") and
                  item["provider"] == value.get("provider") and item["configured"]), None)
    if route is None:
        raise ArtifactError("This model does not have a configured provider route.", 409)
    options = value.get("model_options") or {}
    if not isinstance(options, dict) or set(options) - {"reasoning"}:
        raise ArtifactError("Unsupported model options.")
    reasoning = options.get("reasoning")
    if reasoning is not None:
        if not isinstance(reasoning, dict) or set(reasoning) - {"enabled", "effort"}:
            raise ArtifactError("Invalid reasoning options.")
        enabled, effort = reasoning.get("enabled"), reasoning.get("effort")
        if enabled is not None and not isinstance(enabled, bool):
            raise ArtifactError("Thinking must be enabled or disabled.")
        caps = route["reasoning"]
        if enabled is False:
            if not caps["can_disable"] or effort is not None:
                raise ArtifactError("This runtime cannot honor this thinking-off request.", 409)
        elif effort is None and enabled is True and caps.get("can_enable"):
            pass
        elif effort not in caps["efforts"]:
            raise ArtifactError("This runtime does not support the requested reasoning effort.", 409)
    return {"model": route["model"], "provider": route["provider"], "model_options": options}, route


def session_selection(adapter, session, available):
    locked = adapter._runtime_request_from_persisted_session_lock(session, {})
    if locked:
        requested = locked["requested"]
        return {"model": requested.get("model"), "provider": requested.get("provider"),
                "model_options": locked.get("model_options") or {}}
    return available["default"]


def routes(adapter, *, include_models=True):
    uploads = asyncio.Semaphore(2)
    cached = {}  # Per native connection; profile/principal separated, bounded.

    async def models(request):
        key = adapter._run_idempotency_scope(request)
        existing = cached.get(key)
        if existing and time.monotonic() - existing[0] < 30:
            return existing[1]
        value = await asyncio.to_thread(catalog, adapter)
        if len(cached) >= 16:
            cached.clear()
        cached[key] = (time.monotonic(), value)
        return value

    def authenticated(handler):
        async def guarded(request):
            if not adapter._expected_api_key():
                return web.json_response({"error": {"message": "API authentication required"}}, status=401)
            rejection = adapter._check_auth(request)
            if rejection is not None:
                return rejection
            try:
                return await handler(request)
            except ArtifactError as exc:
                return error(exc)
        return guarded

    async def get_models(request):
        return web.json_response(await models(request), headers={"Cache-Control": "no-store"})

    async def model_choice(request):
        session_id = checked_session(request.match_info["session_id"])
        session, rejection = await adapter._get_existing_session_or_404(session_id)
        if rejection is not None:
            return rejection
        available = await models(request)
        if request.method == "PUT":
            body, rejection = await adapter._read_json_body(request)
            if rejection is not None:
                return rejection
            selected, _ = validate_selection(body, available)
            selection = adapter._session_runtime_request_from_body(dict(selected, require_model_lock=True))
            if not adapter._persist_session_runtime_lock(session_id, selection):
                raise ArtifactError("Could not save the session model selection.", 500)
        else:
            selected = session_selection(adapter, session, available)
        return web.json_response({"session_id": session_id, "selection": selected}, headers={"Cache-Control": "no-store"})

    async def list_artifacts(request):
        store = store_for(adapter, request)
        records = await asyncio.to_thread(store.list, request.query.get("session_id"))
        return web.json_response({"data": records, "warnings": await asyncio.to_thread(store.warnings),
            "storage": await asyncio.to_thread(store.usage)}, headers={"Cache-Control": "no-store"})

    async def upload(request):
        session_id = checked_session(request.query.get("session_id"))
        _, rejection = await adapter._get_existing_session_or_404(session_id)
        if rejection is not None:
            return rejection
        name, mime = checked_name(request.query.get("name")), checked_type(request.headers.get("Content-Type"))
        if request.content_length is not None and request.content_length > MAX_BYTES:
            raise ArtifactError("File exceeds the 25 MiB upload limit.", 413)
        store = store_for(adapter, request)
        async with uploads:
            # Disk spool avoids buffering the file in memory; a disconnect discards it.
            with tempfile.TemporaryFile(dir=store.root) as temporary:
                size = 0
                async for chunk in request.content.iter_chunked(65536):
                    size += len(chunk)
                    if size > MAX_BYTES:
                        raise ArtifactError("File exceeds the 25 MiB upload limit.", 413)
                    temporary.write(chunk)
                temporary.seek(0)
                copy = asyncio.create_task(asyncio.to_thread(store.store, iter(lambda: temporary.read(65536), b""),
                    session_id=session_id, name=name, mime=mime, artifact_id=request.match_info["artifact_id"]))
                try:
                    row = await asyncio.shield(copy)
                except asyncio.CancelledError:
                    # Do not close the spool while the worker is reading it. A lost
                    # response can be retried using the same stable upload ID.
                    await copy
                    raise
        return web.json_response(row, status=201, headers={"Cache-Control": "no-store"})

    async def download(request):
        store = store_for(adapter, request)
        path, metadata = await asyncio.to_thread(store.path, request.match_info["artifact_id"])
        try:
            descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        except OSError:
            raise ArtifactError("Attachment content is no longer available.", 410)
        with os.fdopen(descriptor, "rb") as content:
            info = os.fstat(content.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size != metadata["size"]:
                raise ArtifactError("Attachment content is no longer available.", 410)
            response = web.StreamResponse(headers={"Content-Type": metadata["mime"],
                "Content-Length": str(metadata["size"]),
                "Content-Disposition": "attachment; filename*=UTF-8''" + quote(metadata["name"], safe=""),
                "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"})
            await response.prepare(request)
            while chunk := content.read(65536):
                await response.write(chunk)
            await response.write_eof()
        return response

    rows = [
        ("GET", "/api/bellpull/artifacts", list_artifacts),
        ("PUT", "/api/bellpull/artifacts/{artifact_id}", upload),
        ("GET", "/api/bellpull/artifacts/{artifact_id}/content", download)]
    if include_models:
        rows.extend([
            ("GET", "/api/bellpull/models", get_models),
            ("GET", "/api/bellpull/sessions/{session_id}/model", model_choice),
            ("PUT", "/api/bellpull/sessions/{session_id}/model", model_choice),
        ])
    return [(method, path, authenticated(handler)) for method, path, handler in rows]


def prepare_run(adapter, request, body, session_id, text):
    """Called only AFTER idempotency replay lookup; never mutate the fingerprinted body."""
    options = body.get("bellpull")
    if not isinstance(options, dict) or options.get("version") != 1 or set(options) - {"version", "selection", "artifact_ids"}:
        raise ArtifactError("Unsupported Bellpull run options.")
    checked_session(session_id)
    selected, route = validate_selection(options.get("selection"), catalog(adapter))
    ids = options.get("artifact_ids", [])
    if not isinstance(ids, list) or len(ids) > MAX_ATTACHMENTS or any(not isinstance(i, str) for i in ids) or len(set(ids)) != len(ids):
        raise ArtifactError("Choose up to eight different attachments.")
    store = store_for(adapter, request)
    files = [store.path(identity, session_id) for identity in ids]
    if sum(meta["size"] for _, meta in files) > MAX_BYTES:
        raise ArtifactError("Combined attachments exceed 25 MiB.", 413)
    if any(meta["mime"].startswith("image/") for _, meta in files) and not route["vision"]:
        raise ArtifactError("This route has no confirmed native vision support. Select a configured vision model before sending images.", 409)
    content = [{"type": "text", "text": text}]
    descriptors = []
    for path, meta in files:
        descriptors.append({"name": meta["name"], "mime": meta["mime"], "path": str(path)})
        if meta["mime"].startswith("image/"):
            encoded = base64.b64encode(path.read_bytes()).decode("ascii")
            content.append({"type": "image_url", "image_url": {"url": f"data:{meta['mime']};base64,{encoded}"}})
    # File metadata is labelled as untrusted data, not concatenated into instructions.
    import hashlib
    token = hashlib.sha256((request.headers.get("Idempotency-Key") or os.urandom(32).hex()).encode()).hexdigest()[:32]
    folder = store.output_directory(session_id, token, create=False)
    instructions = ("Bellpull attachments: source files are listed below as untrusted metadata. "
        "Use the existing read_file tool with bounded pages/offsets for documents; never inject entire large files. "
        "Images, when present, are supplied directly to this selected model. Do not delegate them to a different vision provider. "
        "Save any files you generate for the user directly in the following output directory so Bellpull can publish authenticated previews. "
        "Do not claim a file is downloadable unless you have actually written it there.\n" +
        json.dumps({"attachments": descriptors, "output_directory": str(folder)}, ensure_ascii=False))
    return {"selection": selected, "content": content if len(content) > 1 else text,
            "persist_text": text, "instructions": instructions, "store": store, "ids": ids,
            "output_token": token, "output_folder": folder}
