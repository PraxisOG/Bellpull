"""Small, versioned Hermes host contract for API plugins.

The native host owns authentication, request fingerprints, atomic admission,
concurrency, execution, cancellation and approval policy. Preparers are read-only;
start/finish callbacks run only for the winner of native admission. This module
does not import Bellpull or replace any adapter method.
"""
from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
import inspect
import logging
from typing import Any, Callable
from weakref import WeakKeyDictionary

from aiohttp import web

API_VERSION = 1
MAX_ROUTE_BODY_BYTES = 32 * 1024 * 1024
logger = logging.getLogger(__name__)
_bindings = WeakKeyDictionary()
_BODY_LIMITS = web.AppKey("hermes_api_plugin_body_limits", dict)


class ExtensionError(ValueError):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


@dataclass(frozen=True)
class PreparedRun:
    user_message: Any
    persist_user_message: str
    instructions: str = ""
    agent_options: dict = field(default_factory=dict)
    on_start: Callable[[str, str], None] | None = None
    on_finish: Callable[[str, str, dict | None], None] | None = None


@dataclass(frozen=True)
class Hooks:
    name: str
    prepare: Callable | None = None
    status: Callable | None = None
    transport_options: Callable | None = None
    close: Callable | None = None


def register(adapter, app, hooks: Hooks):
    """Called by a normal register_platform_handler factory before router freeze."""
    entries = _bindings.setdefault(adapter, [])
    if any(existing.name == hooks.name for existing in entries):
        raise ValueError("Duplicate API extension registration")
    entries.append(hooks)

    async def cleanup(_app):
        if hooks.close:
            result = hooks.close()
            if inspect.isawaitable(result):
                await result
        entries.remove(hooks)
        if not entries:
            _bindings.pop(adapter, None)

    app.on_cleanup.append(cleanup)


def register_body_limit(app, path: str, limit: int):
    if not 0 < limit <= MAX_ROUTE_BODY_BYTES:
        raise ValueError("Plugin upload policy exceeds the host ceiling")
    if _BODY_LIMITS not in app:
        app[_BODY_LIMITS] = {}
    app[_BODY_LIMITS][path] = limit


def incoming_body_limit(request, default: int) -> int:
    resource = getattr(request.match_info.route, "resource", None)
    path = getattr(resource, "canonical", "")
    return request.app.get(_BODY_LIMITS, {}).get(path, default)


def prepare_run(adapter, request, body, session_id, text):
    prepared = None
    for hooks in tuple(_bindings.get(adapter, ())):
        if not hooks.prepare:
            continue
        value = hooks.prepare(request, body, session_id, text)
        if value is None:
            continue
        if prepared is not None or not isinstance(value, PreparedRun):
            raise ExtensionError("Conflicting API run preparations", 409)
        if not isinstance(value.persist_user_message, str) or not isinstance(value.instructions, str):
            raise ExtensionError("Invalid API run preparation")
        if set(value.agent_options) - {"requested_model", "requested_provider", "model_options", "route", "confirmed_runtime_lock"}:
            raise ExtensionError("API extension attempted to override host policy")
        prepared = value
    return prepared


def transport_options(adapter, provider, model, model_options):
    options = {}
    for hooks in tuple(_bindings.get(adapter, ())):
        if hooks.transport_options:
            value = hooks.transport_options(provider, model, model_options)
            if not isinstance(value, dict):
                raise ExtensionError("Invalid model transport options")
            options.update(value)
    return options


def status_changed(adapter, run_id, status):
    # Private native owner state is converted to a narrow read-only event by the host.
    scope = getattr(adapter, "_run_owners", {}).get(run_id)
    event = {key: status[key] for key in ("session_id", "status", "updated_at") if key in status}
    event.update(run_id=run_id, scope=scope)
    approval = status.get("approval") or {}
    if approval.get("request_id"):
        event["approval_request_id"] = approval["request_id"]
    for hooks in tuple(_bindings.get(adapter, ())):
        if hooks.status:
            try:
                hooks.status(dict(event))
            except Exception:
                logger.warning("API extension status observer failed: %s", hooks.name)
