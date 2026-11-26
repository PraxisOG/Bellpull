"""Stock notification recovery must trust the original admission, scope, and control generation."""
import asyncio
from pathlib import Path
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, Mock, patch

from aiohttp import web

from plugins.bellpull.notification_journal import NotificationJournal
from plugins.bellpull.notification_runs import AdmissionNotifications
from plugins.bellpull.notifications import Relay, topic


class StockNotificationTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.home = str(Path(directory.name))
        self.run = "run_" + "1" * 32
        self.scope = "original-profile-scope"
        self.identity = self.home, self.scope, self.run
        self.authority = SimpleNamespace(profile_id=self.home)
        self.row = {"request_id": self.run, "admission_id": "claim", "status": "started",
                    "payload": {"api_turn_v1": {"run_owner_scope": self.scope}}}
        self.status = {"run_id": self.run, "admission_id": "claim", "session_id": "chat",
                       "status": "unknown", "execution_generation": 5}
        self.modules = {
            "gateway.platforms.api_server_authority_runs": SimpleNamespace(
                run_admission=Mock(return_value=(self.authority, self.row)),
                run_projection=Mock(return_value=self.status)),
            "gateway.session_authorities": SimpleNamespace(active_authority=Mock(return_value=self.authority),
                authority_for_profile_id=Mock(return_value=self.authority)),
            "hermes_constants": SimpleNamespace(set_hermes_home_override=Mock(), reset_hermes_home_override=Mock()),
        }
        self.adapter = Mock()
        self.adapter._check_auth.return_value = None
        self.adapter._run_idempotency_scope.return_value = self.scope
        self.adapter._run_idempotency_store.status_for_run.return_value = None

    def observer(self):
        return AdmissionNotifications(self.adapter, Relay("", NotificationJournal(self.home)))

    async def test_restart_and_same_scope_replay_preserve_one_approval_and_one_terminal_receipt(self):
        with patch.dict("sys.modules", self.modules):
            watcher = self.observer()
            watcher.worker = asyncio.current_task()
            response = web.json_response({"run_id": self.run}, status=202)
            request = SimpleNamespace(method="POST", path="/v1/runs")
            self.assertIs(response, await watcher.middleware(request, AsyncMock(return_value=response)))
            watcher = self.observer()
            journal = watcher.relay.journal
            self.assertEqual([self.identity], journal.due_runs())
            self.assertEqual(60, watcher._check(*self.identity))
            self.status.update(status="waiting_for_approval", pending_controls=[
                {"kind": "approval", "prompt_id": "old", "execution_generation": 4},
                {"kind": "clarify", "prompt_id": "question", "execution_generation": 5},
                {"kind": "approval", "prompt_id": "current", "execution_generation": 5}])
            def snapshot():
                return journal.history(topic(self.scope), 0, 10)["events"]
            self.assertEqual([], snapshot())
            watcher._check(*self.identity)
            watcher._check(*self.identity)
            receipt, = snapshot()
            self.assertEqual("current", receipt["approval_request_id"])
            self.row["status"] = "terminal"
            self.status["status"] = "failed"
            with patch.object(journal, "record", side_effect=OSError("disk temporarily unavailable")), self.assertLogs():
                watcher._batch()
            self.assertFalse(journal.observe_run(*self.identity))
            with patch("plugins.bellpull.notification_journal.time.time", return_value=time.time() + 11):
                watcher._batch()
            self.assertEqual([], journal.due_runs())
            self.assertEqual(["waiting_for_approval", "failed"], [e["status"] for e in snapshot()])
            journal.observe_run(*self.identity)
            watcher._batch()
            self.assertEqual(2, len(snapshot()))

    async def test_scope_or_authority_mismatch_and_historical_status_reads_emit_nothing(self):
        with patch.dict("sys.modules", self.modules):
            watcher = self.observer()
            journal = watcher.relay.journal
            self.status["status"] = "completed"
            self.assertIsNone(watcher._check(self.home, "other", self.run))
            self.modules["gateway.session_authorities"].active_authority.return_value = object()
            self.assertEqual(60, watcher._check(*self.identity))
            self.assertEqual([], journal.history(topic("other"), 0, 10)["events"])
            self.assertEqual([], journal.history(topic(self.scope), 0, 10)["events"])
            watcher.worker = asyncio.current_task()
            request = SimpleNamespace(method="GET", path="/v1/runs/" + self.run)
            response = web.json_response(self.status)
            await watcher.middleware(request, AsyncMock(return_value=response))
            self.assertEqual([], journal.due_runs())
            self.status["status"] = "running"
            self.modules["gateway.session_authorities"].active_authority.return_value = self.authority
            await watcher.middleware(request, AsyncMock(return_value=web.json_response(self.status)))
            self.assertEqual([self.identity], journal.due_runs())
