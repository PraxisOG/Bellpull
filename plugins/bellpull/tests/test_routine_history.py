import hashlib
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from plugins.bellpull import routine_history
from plugins.bellpull.artifacts import ArtifactError


class RoutineHistoryTest(unittest.TestCase):
    def setUp(self):
        if not routine_history.available():
            self.skipTest("This Hermes host has no native Bot Chat receipt history")
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.home = Path(temporary.name).resolve()
        env = patch.dict(os.environ, {"HERMES_HOME": str(self.home)})
        env.start()
        self.addCleanup(env.stop)
        from cron import executions
        self.executions = executions

    def event(self, job):
        event = self.executions.create_execution(job, source="scheduler")
        key = hashlib.sha256(json.dumps([
            str(self.home), job, event["id"], str(self.home)
        ], separators=(",", ":")).encode()).hexdigest()
        return event["id"], key

    def test_tied_claims_keep_owner_and_timeout_notice_on_separate_pages(self):
        from cron.bot_chat_delivery import defer, read_pending
        from tools.bot_live_delivery import deliver_to_live_owner, read_delivery_result
        first, key = self.event("earlier")
        notice = hashlib.sha256((key + ":degraded").encode()).hexdigest()
        defer(notice, {"id": "earlier", "execution_id": first}, "private output", None, self.home, degraded=True)
        second, owner_key = self.event("later")
        owner = dict(profile_home=str(self.home), session_id="chat", lease_id="lease", live_session_id="owner")
        deliver_to_live_owner(self.home, owner, "private chat content", delivery_id=owner_key)
        stamp = "2026-11-24T14:45:00+00:00"
        with sqlite3.connect(self.home / "cron" / "executions.db") as db:
            db.execute("update executions set claimed_at=?", (stamp,))
        with patch.object(routine_history, "PAGE_SIZE", 1):
            latest = routine_history.page()
            older = routine_history.page(latest["next"])
            self.assertEqual(latest, routine_history.page())
        records = latest["deliveries"] + older["deliveries"]
        self.assertEqual({notice, owner_key}, {r["receipt"]["id"] for r in records})
        self.assertEqual(sorted([first, second], reverse=True), [r["receipt"]["execution_id"] for r in records])
        self.assertIsNone(older["next"])
        self.assertEqual("queued", read_pending(notice)["status"])
        self.assertEqual("queued", read_delivery_result(self.home, owner_key)["status"])
        self.assertEqual(2, len(self.executions.list_executions()))
        notice_row = next(r["receipt"] for r in records if r["job"] == "earlier")
        self.assertTrue(notice_row["degraded_notice"])
        self.assertEqual("deferred", notice_row["source"])
        self.assertNotIn("private", json.dumps(records))
        with tempfile.TemporaryDirectory() as other, patch.dict(os.environ, {"HERMES_HOME":other}):
            self.assertFalse(routine_history.page()["deliveries"])

    def test_unrelated_records_and_bad_read_do_not_hide_valid_history(self):
        from cron.bot_chat_delivery import defer
        exe, key = self.event("match")
        defer(key, {"id": "other", "execution_id": exe}, "private content", None, self.home)
        notice = hashlib.sha256((key + ":degraded").encode()).hexdigest()
        defer(notice, {"id": "match", "execution_id": exe}, "", None, self.home, degraded=True)
        self.assertEqual([notice], [r["receipt"]["id"] for r in routine_history.page()["deliveries"]])
        (self.home / "cron" / "bot_chat_pending" / (key + ".json")).write_text("{")
        page = routine_history.page()
        self.assertEqual(1, page["unreadable"])
        self.assertEqual(notice, page["deliveries"][0]["receipt"]["id"])
        with self.assertRaises(ArtifactError) as problem:
            routine_history.page("invalid")
        self.assertEqual(400, problem.exception.status)
