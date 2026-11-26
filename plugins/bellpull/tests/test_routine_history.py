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

try:
    from cron.bot_chat_delivery import read_pending as lease_mailbox_read
except ImportError:
    lease_mailbox_read = None


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

    @unittest.skipUnless(lease_mailbox_read, "Requires the Hermes lease mailbox")
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

    @unittest.skipUnless(lease_mailbox_read, "Requires the Hermes lease mailbox")
    def test_owner_registry_evidence_preserves_original_claim_across_a_restart(self):
        from hermes_cli import active_sessions
        from tools import bot_live_delivery as mailbox
        from plugins.bellpull.routine_results import bot_chat_receipts

        _, key = self.event("restart")
        job = {"id": "restart", "last_delivery_queued": {"bot-chat:(own)": {"delivery_id": key}}}
        owner = dict(profile_home=str(self.home), session_id="original", lease_id="first", live_session_id="live")
        registered = dict(session_id="original", lease_id="first", metadata={"live_session_id": "live"})
        mailbox.deliver_to_live_owner(self.home, owner, "retained result", delivery_id=key)

        def observe(state):
            history = routine_history.page()["deliveries"][0]["receipt"]
            latest, = bot_chat_receipts(job)
            self.assertEqual((key, state), (history["id"], history.get("owner_state")))
            self.assertEqual((key, state), (latest["id"], latest.get("owner_state")))
            return history["status"]

        with patch.object(active_sessions, "active_session_registry_snapshot", return_value=[registered]) as lookup:
            self.assertEqual("queued", observe("registered"))
            mailbox.claim_pending_delivery(self.home, owner)
            self.assertEqual("claimed", observe("registered"))
            lookup.return_value = [dict(registered, session_id="compressed")]
            self.assertEqual("claimed", observe("registered"))
            lookup.return_value = [dict(registered, lease_id="second")]
            self.assertEqual("claimed", observe("replaced"))
            lookup.return_value = [dict(registered, session_id="another-chat", lease_id="second")]
            self.assertEqual("claimed", observe("absent"))
            lookup.side_effect = OSError("registry unavailable")
            self.assertEqual("claimed", observe(None))
            lookup.assert_called_with(registry_home=self.home, strict=True)
            mailbox.complete_delivery(self.home, key, status="settled")
            lookup.reset_mock()
            self.assertEqual("settled", observe(None))
            lookup.assert_not_called()
        self.assertEqual(1, len(self.executions.list_executions()))

    def test_new_admission_after_registry_sample_waits_for_fresh_evidence(self):
        from hermes_cli import active_sessions
        from plugins.bellpull.routine_results import BotChatOwners

        owners = BotChatOwners(self.home)
        first = dict(session_id="chat", lease_id="first", metadata={"live_session_id": "worker"})
        pin = dict(profile_home=str(self.home), session_id="chat", lease_id="second", live_session_id="replacement")
        with patch.object(active_sessions, "active_session_registry_snapshot", return_value=[first]) as lookup:
            sampled_at, _ = owners.snapshot
            new = dict(status="queued", owner=pin, created_at=sampled_at + 1)
            self.assertEqual({}, owners.evidence(new))
            lookup.assert_called_once()

    @unittest.skipUnless(lease_mailbox_read, "Requires the Hermes lease mailbox")
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

    @unittest.skipIf(lease_mailbox_read, "Requires the Hermes authority's deferred writer")
    def test_authority_deferred_history_matches_execution_and_preserves_older_evidence(self):
        from cron.bot_chat_legacy import defer
        from plugins.bellpull import bot_chat_recovery
        from plugins.bellpull.routine_results import bot_chat_receipts

        exe, key = self.event("parked")
        _, notice = routine_history.delivery_keys(self.home, "parked", exe)
        defer(key, {"id": "parked", "name": "Fixture"}, "Saved answer", self.home, for_failure=False)
        folder = self.home / "cron" / "bot_chat_pending"
        primary = folder / (key + ".json")
        old_notice = folder / (notice + ".json")
        old_notice.write_text(json.dumps(dict(id=notice, status="ambiguous", job=dict(id="parked", execution_id=exe),
            degraded=True, profile=None, home=str(self.home), content="Retained timeout notice", sequence=1)))
        originals = (primary.read_bytes(), old_notice.read_bytes())
        page = routine_history.page()
        self.assertEqual([(key, "queued"), (notice, "ambiguous")],
                         [(row["receipt"]["id"], row["receipt"]["status"]) for row in page["deliveries"]])
        self.assertNotIn("Saved answer", json.dumps(page))
        self.assertFalse(bot_chat_recovery.available())
        latest, = bot_chat_receipts(dict(id="parked", last_delivery_queued={"bot-chat:(own)": {"delivery_id": key}}))
        self.assertEqual(("deferred", "queued"), (latest["source"], latest["status"]))
        self.assertEqual(originals, (primary.read_bytes(), old_notice.read_bytes()))
        for change in ({"home": str(self.home / "other")}, {"job": {"id": "other"}},
                       {"job": {"id": "parked", "execution_id": "a" * 32}},
                       {"profile": None}, {"degraded": True}):
            primary.write_text(json.dumps(dict(json.loads(originals[0]), **change)))
            remaining, = routine_history.page()["deliveries"]
            self.assertEqual(notice, remaining["receipt"]["id"])
        primary.unlink()
        primary.symlink_to(old_notice)
        self.assertEqual(1, routine_history.page()["unreadable"])
        self.assertEqual(originals[1], old_notice.read_bytes())
