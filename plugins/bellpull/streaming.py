"""Connect native reasoning callbacks to the native run stream, without core file patches.

The platform-handler factory owns this adapter instance. Two narrow, feature-detected
wrappers fill the missing callback wiring; Hermes still owns auth, execution, status,
sequence numbers, retention and SSE. Unsupported adapters keep their native behavior.
"""
from functools import wraps
import inspect
import time

from . import memory_status


def wire(adapter):
    create = getattr(adapter, "_create_agent", None)
    make_progress = getattr(adapter, "_make_run_event_callback", None)
    if not callable(create) or not callable(make_progress) or not isinstance(getattr(adapter, "_run_streams", None), dict):
        return False, lambda: None
    try:
        signature = inspect.signature(create)
    except (TypeError, ValueError):
        return False, lambda: None
    if "tool_progress_callback" not in signature.parameters:
        return False, lambda: None
    accepts_reasoning = "reasoning_callback" in signature.parameters
    if not accepts_reasoning:
        # Older stock adapters omit this constructor argument even though their
        # native AIAgent already exposes the same callback attribute.
        try:
            from run_agent import AIAgent
            supported = "reasoning_callback" in inspect.signature(AIAgent).parameters
        except (ImportError, TypeError, ValueError):
            supported = False
        if not supported:
            return False, lambda: None
    active = True
    compacting = set()

    def phase_sink(run_id, loop, session_queue=None):
        def publish(start):
            statuses = getattr(adapter, "_run_statuses", {})
            status = statuses.get(run_id)
            if not active:
                return
            if not status or status.get("status") not in {"running", "waiting_for_approval"}:
                compacting.discard(run_id)
                if status is not None:
                    status.pop("bellpull_phase", None)
                return
            if (run_id in compacting) == start:
                return
            if start:
                compacting.add(run_id)
                status["bellpull_phase"] = "compacting"
            else:
                compacting.discard(run_id)
                status.pop("bellpull_phase", None)
            name = "run.compacting" if start else "run.compacted"
            if session_queue is not None:
                session_queue.enqueue(name, {})
            else:
                stream = adapter._run_streams.get(run_id)
                if stream is not None:
                    stream.put_nowait({"event": name, "run_id": run_id, "timestamp": time.time()})

        def emit(start):
            if active and not loop.is_closed():
                loop.call_soon_threadsafe(publish, start)
        return emit

    def fallback_sink(run_id, loop):
        def publish(transition):
            status = getattr(adapter, "_run_statuses", {}).get(run_id)
            if not active or not status or status.get("status") not in {"running", "waiting_for_approval"}:
                return
            # The native terminal write retains this observation in the idempotent run record.
            status["bellpull_model_fallback"] = transition
            stream = adapter._run_streams.get(run_id)
            if stream is not None:
                stream.put_nowait({"event": "run.model_fallback", "run_id": run_id,
                                   "timestamp": time.time(), "bellpull_model_fallback": transition})

        def emit(transition):
            if active and not loop.is_closed():
                loop.call_soon_threadsafe(publish, transition)
        return emit

    @wraps(make_progress)
    def progress_factory(run_id, loop, *args, **kwargs):
        native = make_progress(run_id, loop, *args, **kwargs)
        streamed = False

        def deliver(text):
            if not active:
                return
            stream = adapter._run_streams.get(run_id)
            if stream is not None:
                stream.put_nowait({"event": "reasoning.delta", "run_id": run_id,
                    "timestamp": time.time(), "text": text})

        def reasoning(text):
            nonlocal streamed
            if active and isinstance(text, str) and text:
                streamed = True
                loop.call_soon_threadsafe(deliver, text)

        @wraps(native)
        def progress(event_type, *args, **kwargs):
            nonlocal streamed
            # Native reasoning.available repeats the first 500 characters after a model
            # response. Keep it only when that response supplied no actual deltas.
            if event_type == "reasoning.available" and streamed:
                streamed = False
                return
            return native(event_type, *args, **kwargs)

        progress._bellpull_fallback = fallback_sink(run_id, loop)
        progress._bellpull_phase = phase_sink(run_id, loop)
        progress._bellpull_reasoning_callback = reasoning
        return progress

    @wraps(create)
    def create_agent(*args, **kwargs):
        bound = signature.bind_partial(*args, **kwargs)
        progress = bound.arguments.get("tool_progress_callback")
        reasoning = getattr(progress, "_bellpull_reasoning_callback", None)
        inject = active and reasoning is not None and bound.arguments.get("reasoning_callback") is None
        if inject and accepts_reasoning:
            bound.arguments["reasoning_callback"] = reasoning
        agent = create(*bound.args, **bound.kwargs)
        if active:
            try:
                memory_status.observe(agent, lambda: active)
            except Exception:
                # Optional metadata must not prevent a native agent from starting.
                pass
        if inject and not accepts_reasoning and hasattr(agent, "reasoning_callback") and agent.reasoning_callback is None:
            agent.reasoning_callback = reasoning
        fallback = getattr(progress, "_bellpull_fallback", None)
        if fallback is not None:
            _observe_provider_fallback(agent, fallback)
        phase = getattr(progress, "_bellpull_phase", None)
        # Stock session SSE owns a separate sequenced queue. Use its native enqueue
        # only on the known session handler, never a global session-id lookup.
        if phase is None and inspect.isfunction(progress) and progress.__qualname__.endswith(
                "._handle_session_chat_stream.<locals>._tool_progress"):
            queue = inspect.getclosurevars(progress).nonlocals.get("events")
            if (queue is not None and getattr(queue, "session_id", None) == bound.arguments.get("session_id")
                    and callable(getattr(queue, "enqueue", None)) and getattr(queue, "run_id", None)):
                phase = phase_sink(queue.run_id, queue.loop, queue)
        if phase is not None and hasattr(agent, "status_callback"):
            _observe_compression(agent, phase)
        return agent

    adapter._create_agent = create_agent
    adapter._make_run_event_callback = progress_factory

    def stop():
        nonlocal active
        active = False
        for run_id in compacting:
            getattr(adapter, "_run_statuses", {}).get(run_id, {}).pop("bellpull_phase", None)
        compacting.clear()
        if adapter._create_agent is create_agent:
            adapter._create_agent = create
        if adapter._make_run_event_callback is progress_factory:
            adapter._make_run_event_callback = make_progress

    return True, stop


def _observe_compression(agent, emit):
    """Keep host status notifications intact and expose only compression edges."""
    original_status = agent.status_callback
    announced = False

    def status(kind, message, *args, **kwargs):
        nonlocal announced
        if kind == "compacting" or (kind == "lifecycle" and "Compacting context" in str(message)):
            if not announced:
                announced = True
                emit(True)
        elif kind == "compacted" and announced:
            announced = False
            emit(False)
        if original_status is not None:
            return original_status(kind, message, *args, **kwargs)

    agent.status_callback = status
    compress = getattr(agent, "_compress_context", None)
    if callable(compress):
        @wraps(compress)
        def observed(*args, **kwargs):
            nonlocal announced
            try:
                return compress(*args, **kwargs)
            finally:
                # Some host abort/exception paths omit their completion callback.
                if announced:
                    announced = False
                    emit(False)
        agent._compress_context = observed

def _route_identity(agent):
    provider, model = getattr(agent, "provider", None), getattr(agent, "model", None)
    # Only publish identifiers. Provider objects, base URLs and credentials stay on the host.
    if not all(isinstance(value, str) and value.strip() and len(value) <= limit
               and value.isprintable() and "://" not in value for value, limit in ((provider, 80), (model, 200))):
        return None
    return {"provider": provider, "model": model}


def _observe_provider_fallback(agent, emit):
    activate = getattr(agent, "_try_activate_fallback", None)
    if not callable(activate):
        return

    @wraps(activate)
    def observed(*args, **kwargs):
        before = _route_identity(agent)
        result = activate(*args, **kwargs)
        if result is True and before:
            after = _fallback_destination(agent, _route_identity(agent))
            if after:
                emit({"from": before, "to": after})
        return result

    agent._try_activate_fallback = observed


def _fallback_destination(agent, route):
    if (not route or getattr(agent, "_provider_fallback_active", None) is not True
            or getattr(agent, "_provider_fallback_route", None) != (route["model"], route["provider"])):
        return None
    route = dict(route)
    try:
        from agent.models_dev import get_model_capabilities
        caps = get_model_capabilities(route["provider"], route["model"], allow_network=False)
        vision = getattr(caps, "supports_vision", None)
        if isinstance(vision, bool):
            route["vision"] = vision
    except Exception:
        pass  # Capability information is optional on older hosts.
    return route
