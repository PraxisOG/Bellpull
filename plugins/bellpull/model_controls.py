"""Per-request reasoning for stock Hermes, using its existing agent/transport API.

Native Hermes owns routing, persisted model locks, admission and replay. This
adapter-instance wrapper only translates an explicit model_options choice after
the native agent has resolved its actual provider/model. No core files or global
configuration are changed.
"""
from copy import deepcopy
from functools import wraps
import inspect

from .models_files import request_overrides


def merge_overrides(existing, selected):
    """Keep unrelated provider extras (including template options) intact."""
    merged = deepcopy(existing or {})
    for key, value in selected.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = merge_overrides(merged[key], value)
        else:
            merged[key] = deepcopy(value)
    return merged


def wire(adapter):
    create = getattr(adapter, "_create_agent", None)
    required = ("_session_runtime_request_from_body", "_persist_session_runtime_lock",
                "_runtime_request_from_persisted_session_lock")
    if not callable(create) or not all(callable(getattr(adapter, name, None)) for name in required):
        return False, lambda: None
    try:
        from run_agent import AIAgent
        signature = inspect.signature(create)
        supported = {"model_options", "requested_model", "requested_provider", "confirmed_runtime_lock"}
        if not supported.issubset(signature.parameters) or "request_overrides" not in inspect.signature(AIAgent).parameters:
            return False, lambda: None
    except (ImportError, TypeError, ValueError):
        return False, lambda: None
    active = True

    @wraps(create)
    def create_agent(*args, **kwargs):
        bound = signature.bind_partial(*args, **kwargs)
        agent = create(*bound.args, **bound.kwargs)
        if active:
            selected = request_overrides(getattr(agent, "provider", None), getattr(agent, "model", None),
                                         bound.arguments.get("model_options"))
            if selected:
                agent.request_overrides = merge_overrides(getattr(agent, "request_overrides", None), selected)
        return agent

    adapter._create_agent = create_agent

    def stop():
        nonlocal active
        active = False
        if adapter._create_agent is create_agent:
            adapter._create_agent = create

    return True, stop
