"""Connect native reasoning callbacks to the native run stream, without core file patches.

The platform-handler factory owns this adapter instance. Two narrow, feature-detected
wrappers fill the missing callback wiring; Hermes still owns auth, execution, status,
sequence numbers, retention and SSE. Unsupported adapters keep their native behavior.
"""
from functools import wraps
import inspect
import time


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
        if inject and not accepts_reasoning and hasattr(agent, "reasoning_callback") and agent.reasoning_callback is None:
            agent.reasoning_callback = reasoning
        return agent

    adapter._create_agent = create_agent
    adapter._make_run_event_callback = progress_factory

    def stop():
        nonlocal active
        active = False
        if adapter._create_agent is create_agent:
            adapter._create_agent = create
        if adapter._make_run_event_callback is progress_factory:
            adapter._make_run_event_callback = make_progress

    return True, stop
