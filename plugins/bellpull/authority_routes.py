"""Model evidence from the worker that owns an admitted native HTTP run."""
from functools import wraps
import logging
import time

from .streaming import _route_identity, _fallback_destination

_STATE = "_bellpull_authority_route"
_LOG = logging.getLogger(__name__)


def register(ctx):
    try:
        from gateway.session_api_turn import api_execution
    except ImportError:
        return  # Older hosts execute runs directly on the HTTP adapter.

    def dispatch(name, *, session_id=None, turn_id=None, platform=None, **fields):
        if platform != "api_server" or not session_id or not turn_id:
            return
        try:
            execution = api_execution.get()
            observe = getattr(execution.get("adapter"), _STATE, None) if execution else None
            if observe:
                observe(execution, name, session_id, turn_id, fields)
        except Exception:
            _LOG.debug("Optional run model observation unavailable", exc_info=True)

    for hook in ("pre_llm_call", "pre_api_request", "post_api_request"):
        def observe(_hook=hook, **fields):
            dispatch(_hook, **fields)
        ctx.register_hook(hook, observe)


def admitted_agent(adapter, session_id):
    """Return the run, admission, generation and worker from one live API claim."""
    from gateway.session_authorities import active_authority
    from gateway.session_persistence import authority_persistence
    authority = active_authority(adapter.gateway_runner)
    owner = authority.logical_owner(session_id) if authority else None
    live = authority.sessions.get(owner) if owner else None
    if live is None:
        return
    with live.event_stream.lock:
        receipt = live.event_stream.execution or {}
        generation = receipt.get("execution_generation")
        adopted = authority.adopted.get(owner)
        if not adopted or adopted[0] != generation or authority.executing.get(owner) != generation:
            return
        agent = adopted[1]
        if agent.session_id != session_id or not receipt.get("admission_id"):
            return
        row = authority_persistence(authority).admissions.get(admission_id=receipt["admission_id"])
        if (row is None or row.principal_id != "api" or row.status != "started" or row.generation != generation
                or row.target_session_id != owner):
            return
        owner_scope = (row.payload.get("api_turn_v1") or {}).get("run_owner_scope")
        if owner_scope and adapter._run_owners.get(row.request_id) == owner_scope:
            return row.request_id, row.admission_id, generation, agent


def _projection(status, evidence):
    if (not isinstance(evidence, dict) or evidence.get("admission_id") != status.get("admission_id")
            or evidence.get("generation") != status.get("execution_generation")):
        return status
    status = dict(status)
    if evidence.get("fallback"):
        status["bellpull_model_fallback"] = evidence["fallback"]
    route = evidence.get("answered")
    if status.get("status") == "completed" and route:
        runtime = status.get("runtime") or {}
        if all(not runtime.get(k) or runtime[k] == route[k] for k in ("provider", "model")):
            status["runtime"] = {**runtime, **route}
    return status


def wire(adapter, loop, available):
    native = getattr(adapter, "_durable_run_status", None)
    if not callable(native) or not isinstance(getattr(adapter, "_run_owners", None), dict):
        return lambda: None

    def publish(run_id, admission_id, generation, field, value):
        status = adapter._run_statuses.get(run_id)
        if not available() or not status:
            return
        record = status.get(_STATE)
        if record and (record["admission_id"] != admission_id or record["generation"] > generation):
            return
        if record is None or record["generation"] != generation:
            record = {"admission_id": admission_id, "generation": generation}
            status[_STATE] = record
        if record.get(field) == value:
            return
        record[field] = value
        # Native status changes persist this dictionary with the caller's idempotency
        # record. A late worker notification can arrive just after the terminal write.
        if status.get("status") in {"completed", "failed", "cancelled", "interrupted"}:
            if run_id in adapter._run_idempotency_ids:
                adapter._run_idempotency_store.update_status(run_id, status)
        elif field == "fallback":
            stream = adapter._run_streams.get(run_id)
            if stream is not None:
                stream.put_nowait({"event": "run.model_fallback", "run_id": run_id,
                                   "timestamp": time.time(), "bellpull_model_fallback": value})

    def observe(execution, hook, session_id, turn_id, fields):
        if not available() or loop.is_closed():
            return
        state = execution.get(_STATE)
        if hook == "pre_llm_call":
            if state is None:
                claim = admitted_agent(adapter, session_id)
                if claim:
                    state = {"claim": claim, "session": session_id, "turn": turn_id,
                             "previous": _route_identity(claim[3])}
                    execution[_STATE] = state
            return
        if not state or state["session"] != session_id or state["turn"] != turn_id:
            return
        run_id, admission_id, generation, agent = state["claim"]
        route = _route_identity(agent)
        if not route or fields.get("model") != route["model"] or fields.get("provider") != route["provider"]:
            return

        def emit(field, value):
            loop.call_soon_threadsafe(publish, run_id, admission_id, generation, field, value)

        if hook == "pre_api_request":
            previous = state["previous"]
            if previous != route and previous:
                after = _fallback_destination(agent, route)
                if after:
                    emit("fallback", {"from": previous, "to": after})
            state["previous"] = route
            if state.pop("answered", False):
                emit("answered", None)
        elif (fields.get("finish_reason") == "stop" and fields.get("assistant_tool_call_count") == 0
              and fields.get("assistant_content_chars", 0) > 0):
            state["answered"] = True
            emit("answered", route)

    @wraps(native)
    def durable_status(request, run_id):
        status = native(request, run_id)
        if not status:
            return status
        if _STATE in status:
            status = {k: v for k, v in status.items() if k != _STATE}
        if not available() or not status.get("admission_id"):
            return status
        retained = adapter._run_statuses.get(run_id)
        try:
            if retained is None:
                row = adapter._run_idempotency_store.status_for_run(adapter._run_idempotency_scope(request), run_id)
                retained = row["status"] if row else None
            return _projection(status, (retained or {}).get(_STATE))
        except Exception:
            _LOG.debug("Retained run model evidence unavailable", exc_info=True)
            return status

    setattr(adapter, _STATE, observe)
    adapter._durable_run_status = durable_status

    def stop():
        if getattr(adapter, _STATE, None) is observe:
            delattr(adapter, _STATE)
        if adapter._durable_run_status is durable_status:
            adapter._durable_run_status = native
    return stop
