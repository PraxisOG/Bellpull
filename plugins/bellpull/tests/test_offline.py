"""Real SessionDB handoff, memory deletion and profile/authentication boundaries."""
import concurrent.futures
import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from hermes_state import SessionDB
from plugins.bellpull.offline import handoff, memory_snapshot, OfflineError


class OfflineTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = Path(self.tmp.name)
        self.db = SessionDB(self.home / "state.db")
        self.db.create_session("source", "api")
        self.db.append_message("source", "user", "Original message")
        self.body = {"operation_id": "a" * 32, "source_id": "source", "title": "Offline test",
                     "messages": [{"role": "user", "content": "Local question"},
                                  {"role": "assistant", "content": "Already completed that task."}], "brief": "Saved brief"}

    def tearDown(self):
        self.db.close(); self.tmp.cleanup()

    def test_retry_and_concurrent_import_are_exactly_once_and_source_untouched(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(lambda _: handoff(self.db, self.body), range(8)))
        self.assertEqual(1, sum(r["created"] for r in results))
        self.assertEqual(1, len({r["session_id"] for r in results}))
        messages = self.db.get_messages(results[0]["session_id"])
        self.assertEqual(3, len(messages))
        self.assertTrue(all(not m.get("tool_calls") and m["role"] != "tool" for m in messages))
        self.assertEqual("Original message", self.db.get_messages("source")[0]["content"])
        self.assertEqual(1, len(self.db.get_messages("source")))
        config = self.db.get_session(results[0]["session_id"])["model_config"]
        if isinstance(config, str): config = json.loads(config)
        self.assertEqual("source", config["_branched_from"])
        self.assertEqual("source", self.db.resolve_resume_session_id("source"))

    def test_changed_retry_conflicts_and_no_partial_import(self):
        handoff(self.db, self.body)
        with self.assertRaises(OfflineError) as error:
            handoff(self.db, {**self.body, "brief": "Changed body"})
        self.assertEqual(409, error.exception.status)
        invalid = {**self.body, "operation_id": "b" * 32, "source_id": "unknown"}
        with self.assertRaises(OfflineError) as error: handoff(self.db, invalid)
        self.assertEqual(404, error.exception.status)
        self.assertIsNone(self.db.get_session("bellpull_offline_" + "b" * 32))

    def test_reject_tool_calls_roles_and_oversized_input(self):
        for row in [{"role": "tool", "content": "executed"}, {"role": "system", "content": "instruction"},
                    {"role": "assistant", "content": "action", "tool_calls": []}, {"role": "user", "content": ""}]:
            with self.subTest(row=row), self.assertRaises(OfflineError):
                handoff(self.db, {**self.body, "messages": [row]})
        with self.assertRaises(OfflineError): handoff(self.db, {**self.body, "messages": [{"role": "user", "content": "x" * 24001}]})

    def test_profile_database_isolation(self):
        other = SessionDB(self.home / "other.db")
        try:
            with self.assertRaises(OfflineError) as error: handoff(other, self.body)
            self.assertEqual(404, error.exception.status)
            a = handoff(self.db, self.body)
            b = handoff(other, {**self.body, "source_id": None})
            self.assertEqual(a["session_id"], b["session_id"])
            self.assertNotEqual(self.db.get_messages(a["session_id"])[0]["content"], other.get_messages(b["session_id"])[0]["content"])
        finally: other.close()

    def test_transaction_rolls_back_if_message_import_fails(self):
        with patch.object(self.db, "_insert_message_rows", side_effect=RuntimeError("fixture failure")):
            with self.assertRaises(RuntimeError): handoff(self.db, self.body)
        self.assertIsNone(self.db.get_session("bellpull_offline_" + "a" * 32))
        self.assertTrue(handoff(self.db, self.body)["created"])

    def test_memory_deleted_and_disabled_entries_disappear(self):
        from gateway.run import _profile_runtime_scope
        folder = self.home / "memories"; folder.mkdir()
        (folder / "MEMORY.md").write_text("Favourite room is Cobalt Orchard")
        (folder / "USER.md").write_text("Name: Test user")
        with _profile_runtime_scope(self.home), patch.dict(os.environ, {"HERMES_HOME": str(self.home)}):
            first = memory_snapshot()
            self.assertIn("Cobalt Orchard", first["text"])
            (folder / "MEMORY.md").write_text("")
            (folder / "USER.md").write_text("")
            second = memory_snapshot()
            self.assertEqual("", second["text"])
            self.assertNotEqual(first["revision"], second["revision"])
            with patch("tools.memory_tool.load_on_disk_store") as load:
                load.return_value.target_enabled.return_value = False
                load.return_value.format_for_system_prompt.return_value = "Disabled secret"
                self.assertEqual("", memory_snapshot()["text"])
