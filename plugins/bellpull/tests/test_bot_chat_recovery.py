import asyncio
import os
from pathlib import Path
import tempfile
from concurrent.futures import ThreadPoolExecutor
import unittest
from unittest.mock import patch

from plugins.bellpull import bot_chat_recovery as recovery, routine_history, routine_results
from plugins.bellpull.artifacts import ArtifactError
from plugins.bellpull.planning import PlanningStore


@unittest.skipUnless(recovery.available(), "Hermes native Bot Chat recovery unavailable")
class BotChatRecoveryTest(unittest.TestCase):
    def setUp(self):
        from cron import executions
        from hermes_state import SessionDB
        from hermes_cli.active_sessions import try_acquire_active_session, release_active_session
        from tools import bot_live_delivery
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.home = Path(tmp.name)
        env = patch.dict(os.environ, {"HERMES_HOME": str(self.home)})
        env.start()
        self.addCleanup(env.stop)
        db = SessionDB(self.home / "state.db")
        db.create_session("bot", "tui", model="fixture")
        db.set_session_title("bot", "Bot Chat")
        db.close()
        lease, error = try_acquire_active_session(session_id="bot", surface="tui", config={},
            metadata=dict(bot_live_delivery_consumer=True, live_session_id="owner"), registry_home=self.home)
        self.assertIsNone(error)
        self.addCleanup(release_active_session, lease)
        self.native = bot_live_delivery
        self.owner = self.native.find_canonical_live_owner(self.home)
        self.store = PlanningStore(self.home)
        self.job = "saved-job"
        self.execution = executions.create_execution(self.job, source="scheduler")["id"]
        self.root, self.notice = routine_history.delivery_keys(self.home, self.job, self.execution)

    def request(self, root=None, parent=None, status="ambiguous", **extras):
        return dict(job=self.job, execution_id=self.execution, receipt=root or self.root,
                    parent=parent or root or self.root, reviewed_status=status, decision="retry_saved_result", **extras)

    def original(self):
        self.native.deliver_to_live_owner(self.home, self.owner, "Already saved result.", delivery_id=self.root)
        self.native.claim_pending_delivery(self.home, self.owner)
        return self.native.complete_delivery(self.home, self.root, status="ambiguous", reason="owner did not acknowledge")

    def test_concurrent_reviews_admit_one_child_and_preserve_every_original(self):
        from cron import executions
        original = self.original()
        request = self.request()
        with ThreadPoolExecutor(max_workers=2) as pool:
            first, second = list(pool.map(lambda _: recovery.retry(self.store, request), range(2)))
        self.assertEqual(first["id"], second["id"])
        first = recovery.retry(self.store, request)
        self.assertEqual((self.root, "queued", "recorded"), (first["parent"], first["status"], first["admission"]))
        self.assertEqual(original, self.native.read_delivery_result(self.home, self.root))
        self.assertEqual(first["id"], self.native.claim_pending_delivery(self.home, self.owner)["id"])
        self.assertIsNone(self.native.claim_pending_delivery(self.home, self.owner))
        self.native.complete_delivery(self.home, first["id"], status="failed", error="fixture failed")
        next_attempt = recovery.retry(self.store, self.request(parent=first["id"], status="failed"))
        self.assertNotEqual(first["id"], next_attempt["id"])
        with patch.object(self.native, "find_canonical_live_owner", return_value=None):
            self.assertEqual(first["id"], recovery.retry(self.store, request)["id"])
        previous = recovery.history(self.store, request)
        self.assertEqual([next_attempt["id"], first["id"]], [r["id"] for r in previous["attempts"]])
        self.assertEqual("ambiguous", previous["original"]["status"])
        self.assertEqual(1, len(executions.list_executions()))

    def test_reconciles_a_lost_native_reply_without_repeating_the_admission(self):
        self.original()
        native = self.native.deliver_to_live_owner
        def truncated(*args, **kwargs):
            native(*args, **kwargs)
            raise OSError("lost acknowledgement")
        with patch.object(self.native, "deliver_to_live_owner", side_effect=truncated):
            with self.assertRaises(OSError):
                recovery.retry(self.store, self.request())
        with patch.object(self.native, "deliver_to_live_owner", side_effect=AssertionError):
            repaired = recovery.retry(self.store, self.request())
        self.assertEqual("recorded", repaired["admission"])
        self.assertEqual(repaired["id"], self.native.claim_pending_delivery(self.home, self.owner)["id"])
        self.assertIsNone(self.native.claim_pending_delivery(self.home, self.owner))

    def test_unresolved_or_foreign_receipts_cannot_admit_work(self):
        self.native.deliver_to_live_owner(self.home, self.owner, "A pending result", delivery_id=self.root)
        for params in (self.request(), self.request(root="e"*64), self.request(parent="d"*64),
                       dict(self.request(), decision=""), dict(self.request(), job="other-job")):
            with self.assertRaises(ArtifactError):
                recovery.retry(self.store, params)
        self.native.claim_pending_delivery(self.home, self.owner)
        with self.assertRaises(ArtifactError):
            recovery.retry(self.store, self.request())
        self.assertEqual([], recovery.history(self.store, self.request())["attempts"])

    def test_retry_requires_the_original_chat_even_when_another_chat_owns_the_title(self):
        original = self.original()
        other = dict(self.owner, session_id="replacement-chat")
        with patch.object(self.native, "find_canonical_live_owner", return_value=other):
            with self.assertRaises(ArtifactError) as problem:
                recovery.retry(self.store, self.request())
            self.assertEqual(409, problem.exception.status)
        self.assertFalse(recovery.history(self.store, self.request())["attempts"])
        self.assertEqual(original, self.native.read_delivery_result(self.home, self.root))
        replacement_owner = dict(self.owner, lease_id="new-lease", live_session_id="new-worker")
        with patch.object(self.native, "find_canonical_live_owner", return_value=replacement_owner):
            child = recovery.retry(self.store, self.request())
        self.assertEqual(replacement_owner, self.native.read_delivery_result(self.home, child["id"])["owner"])

    def test_legacy_notice_uses_only_the_reviewed_answer_at_the_selected_revision(self):
        from cron import bot_chat_delivery as queue, jobs
        queue.defer(self.notice, dict(id=self.job, execution_id=self.execution), "Timeout notice", None, self.home, degraded=True)
        with patch("cron.scheduler_delivery._deliver_to_bot_chat", return_value="fixture timeout"):
            queue.drain()
        original = queue.read_pending(self.notice)
        self.assertEqual("ambiguous", original["status"])
        request = self.request(root=self.notice)
        with self.assertRaises(ArtifactError):
            recovery.retry(self.store, request)
        with jobs.use_cron_store(self.home):
            jobs.save_job_output(self.job, "# Cron Job: report\n\n## Prompt\n\nOriginal private job instructions\n\n## Response\n\nRetained answer.")
        file, = routine_results.list_results(self.home, job=self.job)["results"]
        chosen = {"stamp": file["stamp"], "revision": file["revision"]}
        with self.assertRaises(ArtifactError):
            recovery.retry(self.store, dict(request, selection=dict(chosen, revision="e"*64)))
        self.assertFalse(recovery.history(self.store, request)["attempts"])
        retry = recovery.retry(self.store, dict(request, selection=chosen))
        msg = self.native.read_delivery_result(self.home, retry["id"])["message"]
        self.assertIn("Retained answer.", msg)
        self.assertNotIn("private job instructions", msg)
        self.assertNotIn("Timeout notice", msg)
        with self.assertRaises(ArtifactError):
            recovery.retry(self.store, dict(request, selection=dict(chosen, revision="e"*64)))
        self.assertEqual(original, queue.read_pending(self.notice))

    def test_archived_prompt_and_split_unicode_heading_stay_out_of_clipped_result(self):
        from cron import jobs
        prefix = "# Cron Job: report\n\n## Prompt\n\nprivate instructions " + "é" * 20000
        # Force the response marker to straddle the native 32 KiB read boundary.
        prefix += "x" * (2 * routine_results.CHUNK_BYTES - 4 - len(prefix.encode()))
        with jobs.use_cron_store(self.home):
            jobs.save_job_output(self.job, prefix + "\n## Response\n" + "start" + "α"*12000 + "end")
        file, = routine_results.list_results(self.home, job=self.job)["results"]
        answer = recovery.selected_answer(self.home, self.job, file["stamp"], file["revision"])
        self.assertNotIn("private instructions", answer)
        self.assertIn("start", answer)
        self.assertIn("chars omitted", answer)
        self.assertTrue(answer.endswith("end"))
        with jobs.use_cron_store(self.home):
            jobs.save_job_output("no-answer", "# Cron Job: unfinished\n\n## Prompt\n\nOnly the old instructions.")
        incomplete, = routine_results.list_results(self.home, job="no-answer")["results"]
        with self.assertRaises(ArtifactError):
            recovery.selected_answer(self.home, "no-answer", incomplete["stamp"], incomplete["revision"])

    def test_script_headings_and_failure_diagnostics_are_saved_output(self):
        from cron import jobs
        from cron.scheduler import _job_doc_header, _run_doc_header
        from cron.scheduler_diagnostics import format_run_error
        with jobs.use_cron_store(self.home):
            header = _job_doc_header("A report", self.job, "2000-01-01 00:00:00", "no_agent (script)")
            output = "meter reading\n## Prompt\nusage section\n## Response\nexample section"
            jobs.save_job_output(self.job, header + "\n---\n\n" + output + "\n")
            failed = _run_doc_header({}, "A report (FAILED)", "failed", "original task\n## Response\nprivate instructions")
            jobs.save_job_output("failed", failed + format_run_error(RuntimeError("Meter disconnected")))
        def read(job):
            file, = routine_results.list_results(self.home, job=job)["results"]
            return recovery.selected_answer(self.home, job, file["stamp"], file["revision"])
        self.assertIn(output, read(self.job))
        diagnostic = read("failed")
        self.assertIn("Meter disconnected", diagnostic)
        self.assertNotIn("private instructions", diagnostic)

    def test_pruned_original_and_child_never_readmit_a_saved_decision(self):
        self.original()
        with patch.object(self.native, "find_canonical_live_owner", return_value=None), self.assertRaises(ArtifactError):
            recovery.retry(self.store, self.request())
        self.assertFalse(recovery.recent(self.store))
        first = recovery.retry(self.store, self.request())
        restarted = PlanningStore(self.home)
        native_read = self.native.read_delivery_result
        with patch.object(self.native, "read_delivery_result", side_effect=lambda h, k: None if k == self.root else native_read(h, k)):
            page = recovery.history(restarted, self.request())
            self.assertEqual("review", page["original"]["source"])
            self.assertEqual(first, recovery.retry(restarted, self.request()))
        with patch.object(self.native, "read_delivery_result", return_value=None), patch.object(self.native, "deliver_to_live_owner", side_effect=AssertionError):
            self.assertEqual("record_missing", recovery.retry(restarted, self.request())["admission"])

    def test_interruption_before_native_receipt_remains_unconfirmed(self):
        self.original()
        with patch.object(self.native, "deliver_to_live_owner", side_effect=OSError), self.assertRaises(OSError):
            recovery.retry(self.store, self.request())
        with patch.object(self.native, "deliver_to_live_owner", side_effect=AssertionError):
            attempt = recovery.retry(PlanningStore(self.home), self.request())
        self.assertEqual(("unconfirmed", "unknown"), (attempt["admission"], attempt["status"]))
        self.assertIsNone(self.native.claim_pending_delivery(self.home, self.owner))

    def test_http_authentication_scope_and_native_receipt_after_reconnect(self):
        from aiohttp import web
        from aiohttp.test_utils import TestClient, TestServer
        from gateway.config import PlatformConfig
        from gateway.platforms.api_server import APIServerAdapter
        from plugins.bellpull.http import wire
        self.original()
        async def verify():
            adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={"key": "retry-fixture"}))
            app = web.Application()
            stop = wire(app, adapter)
            path = "/api/bellpull/planning/bot-chat-recovery"
            query = dict(job=self.job, execution_id=self.execution, receipt=self.root)
            headers = {"Authorization": "Bearer retry-fixture"}
            try:
                async with TestClient(TestServer(app)) as client:
                    self.assertEqual(401, (await client.get(path, params=query)).status)
                    self.assertEqual(401, (await client.post(path, json=self.request())).status)
                    self.assertFalse(recovery.recent(self.store))
                    response = await client.get(path, params=query, headers=headers)
                    self.assertEqual(200, response.status, await response.text())
                    self.assertEqual([], (await response.json())["attempts"])
                    response = await client.post(path, json=self.request(), headers=headers)
                    self.assertEqual(200, response.status, await response.text())
                    first = await response.json()
                    response = await client.get(path, params=query, headers=headers)
                    self.assertEqual([first], (await response.json())["attempts"])
                    response = await client.get("/api/bellpull/planning", headers=headers)
                    snapshot = await response.json()
                    self.assertTrue(snapshot["bot_chat_recovery_available"])
                    self.assertEqual([first], snapshot["recent_bot_chat_attempts"])
                    response = await client.get(path, params=dict(query, receipt="f"*64), headers=headers)
                    self.assertEqual(404, response.status)
            finally:
                stop()
                for database in getattr(adapter, "_session_dbs", {}).values():
                    database.close()
        asyncio.run(verify())
