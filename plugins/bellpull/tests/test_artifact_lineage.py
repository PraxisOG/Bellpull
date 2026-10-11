"""Real SessionDB compression aliases never confer ordinary branch membership."""
from pathlib import Path
import tempfile
import unittest

from hermes_state import SessionDB
from plugins.bellpull.artifacts import ArtifactStore, ArtifactError


class ArtifactLineageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.home = Path(self.temp.name)
        self.db = SessionDB(self.home / "state.db")
        self.db.create_session("root", "cli")
        self.store = ArtifactStore(self.home, "a" * 64)

    def tearDown(self):
        self.db.close()
        self.temp.cleanup()

    def file(self, session, name="answer.txt"):
        return self.store.store([name.encode()], session_id=session, name=name, mime="text/plain", source="agent")

    def continuation(self, parent, child, config=None):
        self.db.end_session(parent, "compression")
        self.db.create_session(child, "cli", parent_session_id=parent, model_config=config)
        self.db.append_message(child, "assistant", "Summary")

    def test_old_and_new_files_are_visible_across_multiple_compression_generations(self):
        old = self.file("root")
        self.continuation("root", "middle")
        self.assertEqual([old["id"]], [r["id"] for r in self.store.list("middle")])
        self.continuation("middle", "tip")
        new = self.file("tip", "new.txt")
        for session in ("root", "middle", "tip"):
            self.assertEqual({old["id"], new["id"]}, {r["id"] for r in self.store.list(session)})
            for row in (old, new):
                self.assertTrue(self.store.path(row["id"], session)[0].is_file())
                self.assertEqual({"root", "middle", "tip"}, set(self.store.metadata(row["id"])["session_ids"]))

    def test_branches_reset_delegates_and_other_profiles_remain_distinct(self):
        old = self.file("root")
        self.continuation("root", "tip")
        for key in ("_branched_from", "_reset_from", "_delegate_from"):
            child = key.strip("_")
            self.db.create_session(child, "cli", parent_session_id="root", model_config={key: "root"})
            self.db.append_message(child, "assistant", "Separate")
            self.assertEqual([], self.store.list(child))
            with self.assertRaises(ArtifactError):
                self.store.path(old["id"], child)
        other = self.home / "profiles" / "work"
        other.mkdir(parents=True)
        db = SessionDB(other / "state.db")
        db.create_session("root", "cli")
        db.create_session("tip", "cli")
        db.close()
        self.assertEqual([], ArtifactStore(other, "a" * 64).list("tip"))
        self.assertEqual([], ArtifactStore(self.home, "b" * 64).list("tip"))

    def test_exact_prefix_links_survive_branch_compression_without_inheriting_other_files(self):
        first = self.db.append_message("root", "assistant", "First")
        second = self.db.append_message("root", "assistant", "Later")
        included, excluded = self.file("root"), self.file("root", "later.txt")
        with self.store._db() as conn:
            conn.execute("UPDATE artifacts SET message_id = ? WHERE id = ?", (first, included["id"]))
            conn.execute("UPDATE artifacts SET message_id = ? WHERE id = ?", (second, excluded["id"]))
        self.db.create_session("branch", "cli", parent_session_id="root", model_config={"_branched_from": "root"})
        copied = self.db.append_message("branch", "assistant", "First")
        self.store.inherit_prefix("root", "branch", {first: copied})
        self.continuation("branch", "branch_tip", {"_branched_from": "root"})
        self.assertEqual([included["id"]], [row["id"] for row in self.store.list("branch_tip")])
        self.assertEqual(copied, self.store.metadata(included["id"])["message_ids"]["branch"])
        self.assertNotIn("branch_tip", self.store.metadata(excluded["id"])["session_ids"])

    def test_ambiguous_compression_children_fail_closed(self):
        old = self.file("root")
        self.continuation("root", "one")
        self.db.create_session("two", "cli", parent_session_id="root")
        self.db.append_message("two", "assistant", "Another possible continuation")
        self.assertEqual(["root"], self.store.metadata(old["id"])["session_ids"])
        self.assertEqual([], self.store.list("one"))
        self.assertEqual([], self.store.list("two"))
