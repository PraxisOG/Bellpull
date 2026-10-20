import concurrent.futures
from pathlib import Path
import tempfile
import unittest
from hermes_state import SessionDB
from plugins.bellpull.titles import repair, supported


@unittest.skipUnless(supported(), "Host has no automatic title provenance")
class VoiceTitleTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = SessionDB(Path(self.tmp.name) / "state.db")
        self.db.create_session("voice", "api")

    def tearDown(self):
        self.db.close()
        self.tmp.cleanup()

    def test_repair_wrapper_title_is_idempotent_and_remains_automatic(self):
        self.db.set_auto_title("voice", "Reply in spoken paragraphs", source="derived")
        self.assertTrue(repair(self.db, "voice", {"title": "Inspect turbine blades"})["updated"])
        repair(self.db, "voice", {"title": "Inspect turbine blades"})
        self.assertEqual("Inspect turbine blades", self.db.get_session_title("voice"))
        self.assertEqual("derived", self.db.get_session_title_source("voice"))

    def test_user_legacy_and_llm_titles_are_preserved(self):
        for source in ("user", "llm", None):
            self.db.set_session_title("voice", "Chosen name")
            self.db._execute_write(lambda conn: conn.execute(
                "UPDATE sessions SET title_source = ? WHERE id = 'voice'", (source,)).rowcount)
            self.assertFalse(repair(self.db, "voice", {"title": "Automatic title"})["updated"])
            self.assertEqual("Chosen name", self.db.get_session_title("voice"))

    def test_concurrent_manual_rename_always_wins(self):
        for index in range(20):
            self.db.set_session_title("voice", "")
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                a = pool.submit(repair, self.db, "voice", {"title": "Automatic title"})
                b = pool.submit(self.db.set_session_title, "voice", f"Manual {index}")
                a.result(); b.result()
            self.assertEqual(f"Manual {index}", self.db.get_session_title("voice"))
            self.assertEqual("user", self.db.get_session_title_source("voice"))

    def test_missing_invalid_and_duplicate_names(self):
        with self.assertRaises(LookupError): repair(self.db, "missing", {"title": "Title"})
        for body in ({}, {"title": ""}, {"title": "x" * 201}, {"title": "Title", "source": "user"}):
            with self.assertRaises(ValueError): repair(self.db, "voice", body)
        self.db.create_session("other", "api")
        self.db.set_session_title("other", "Same question")
        result = repair(self.db, "voice", {"title": "Same question"})
        self.assertTrue(result["updated"])
        self.assertTrue(result["title"].startswith("Same question"))
        self.assertEqual("Same question", self.db.get_session_title("other"))
