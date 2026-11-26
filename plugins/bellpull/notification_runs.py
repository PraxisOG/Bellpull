"""Recover stock Hermes notification receipts from the original API admissions."""
import asyncio
import json
import logging
import re
import sqlite3
from aiohttp import web

_LOG = logging.getLogger(__name__)
_RUNS = re.compile(r"(?:/p/[^/]+)?/v1/runs(?:/(run_[0-9a-f]{32}))?")
_TERMINAL = {"completed", "failed", "cancelled", "interrupted"}


def supported(adapter):
    checks = ("_run_idempotency_scope", "_check_auth", "_expected_api_key")
    if getattr(adapter, "gateway_runner", None) is None or not all(callable(getattr(adapter, name, None)) for name in checks):
        return False
    try:
        from gateway.platforms.api_server_authority_runs import run_admission, run_projection
        from gateway.session_authorities import active_authority, authority_for_profile_id
        from hermes_constants import set_hermes_home_override, reset_hermes_home_override
    except ImportError:
        return False
    return True


class AdmissionNotifications:
    def __init__(self, adapter, relay):
        self.adapter = adapter
        self.relay = relay
        self.wake = asyncio.Event()
        self.worker = None

    @web.middleware
    async def middleware(self, request, handler):
        response = await handler(request)
        if self.worker is None or self.relay.closed:
            return response
        path = _RUNS.fullmatch(request.path)
        if not path or (request.method, response.status) not in {("POST", 202), ("GET", 200)}:
            return response
        try:
            from gateway.session_authorities import active_authority
            if not self.adapter._expected_api_key() or self.adapter._check_auth(request) is not None:
                return response
            result = json.loads(response.body)
            run = result.get("run_id")
            if not isinstance(run, str) or not re.fullmatch(r"run_[0-9a-f]{32}", run):
                return response
            # An active status read also enrolls work started before the upgrade.
            # Looking at a historical reply must not send a new notification.
            if request.method == "GET" and (path[1] != run or result.get("status") not in
                    {"running", "queued", "waiting_for_approval", "unknown"}):
                return response
            authority = active_authority(self.adapter.gateway_runner)
            if authority is not None:
                scope = self.adapter._run_idempotency_scope(request)
                if await asyncio.to_thread(self.relay.journal.observe_run, str(authority.profile_id), scope, run):
                    self.wake.set()
        except Exception:
            _LOG.exception("Could not track the accepted run for notification recovery")
        return response

    def _check(self, home, scope, run):
        from gateway.platforms.api_server_authority_runs import run_admission, run_projection
        from gateway.session_authorities import active_authority, authority_for_profile_id
        from hermes_constants import set_hermes_home_override, reset_hermes_home_override
        runner = self.adapter.gateway_runner
        authority = authority_for_profile_id(runner, home)
        if authority is None or getattr(authority, "retiring", False):
            return 60
        token = set_hermes_home_override(home)
        try:
            if active_authority(runner) is not authority:
                return 60
            owned = run_admission(self.adapter, run)
            if not owned:
                return None  # Retired or outside the stock session-authority contract.
            owner, row = owned
            if (owner is not authority or row["request_id"] != run
                    or (row["payload"].get("api_turn_v1") or {}).get("run_owner_scope") != scope):
                return None
            status = run_projection(self.adapter, run)
        finally:
            reset_hermes_home_override(token)
        if not status or status.get("admission_id") != row["admission_id"]:
            return 5
        event = {"scope": scope, "run_id": run, "session_id": status["session_id"], "status": status["status"]}
        if event["status"] in _TERMINAL:
            try:
                stored = self.adapter._run_idempotency_store.status_for_run(scope, run)
                retained = stored["status"] if stored else {}
            except (sqlite3.Error, OSError):
                retained = {}  # Timestamp enrichment is optional. The authority already settled this run.
            if retained.get("session_id") == event["session_id"] and retained.get("status") == event["status"]:
                event["updated_at"] = retained.get("updated_at")
            # Remove the watch only after the receipt's SQLite transaction commits.
            return None if self.relay.status(event) else 5
        if event["status"] == "waiting_for_approval" and status.get("execution_generation") is not None:
            for prompt in status.get("pending_controls", ()):
                if (prompt.get("kind") == "approval" and prompt.get("prompt_id")
                        and prompt.get("execution_generation") == status.get("execution_generation")):
                    self.relay.status({**event, "approval_request_id": prompt["prompt_id"]})
        if row["status"] == "terminal":
            return None
        return 60 if event["status"] == "unknown" else 1

    def _batch(self):
        journal = self.relay.journal
        for identity in journal.due_runs():
            if self.relay.closed:
                break
            try:
                delay = self._check(*identity)
            except Exception:
                _LOG.warning("Native run receipt unavailable, retrying", exc_info=True)
                delay = 10
            journal.checked_run(identity, delay)
        return journal.next_run_delay()

    async def _observe(self):
        while not self.relay.closed:
            self.wake.clear()
            try:
                delay = await asyncio.to_thread(self._batch)
            except Exception:
                _LOG.exception("Notification recovery journal unavailable")
                delay = 10
            try:
                await asyncio.wait_for(self.wake.wait(), timeout=delay)
            except asyncio.TimeoutError:
                pass

    def start(self):
        if self.worker is None or self.worker.done():
            self.worker = asyncio.create_task(self._observe())

    def stop(self):
        self.wake.set()

    async def close(self):
        if self.worker is not None:
            await self.worker
            self.worker = None
