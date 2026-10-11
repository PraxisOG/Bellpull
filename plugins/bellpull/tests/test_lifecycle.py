import concurrent.futures
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from plugins.bellpull.artifacts import ArtifactError, ArtifactStore
from plugins.bellpull.migrate_artifacts import migrate
from plugins.bellpull.principal import artifact_scope


class LifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.home = Path(self.temp.name)
        self.store = ArtifactStore(self.home, artifact_scope(self.home))

    def tearDown(self):
        self.temp.cleanup()

    def test_principal_survives_rotation_and_concurrent_initialization(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            scopes = list(pool.map(lambda _: artifact_scope(self.home), range(40)))
        self.assertEqual(1, len(set(scopes)))
        row = self.store.store([b"keep"], session_id="s", name="keep.txt", mime="text/plain")
        self.assertEqual(b"keep", ArtifactStore(self.home, artifact_scope(self.home)).path(row["id"])[0].read_bytes())

    def test_separate_profiles_have_separate_artifact_authorities(self):
        other = self.home / "other-profile"
        other.mkdir()
        self.assertNotEqual(artifact_scope(self.home), artifact_scope(other))

    def test_old_outputs_do_not_starve_a_new_run(self):
        old = self.store.output_directory("s")
        for i in range(100):
            (old / f"old-{i:03}.txt").write_text("old")
        folder = self.store.output_directory("s", "run-new")
        (folder / "new.txt").write_text("new")
        rows = self.store.collect_outputs("s", "run-new", folder=folder)
        self.assertEqual(["new.txt"], [row["name"] for row in rows])

    def test_more_than_64_new_outputs_are_collected(self):
        folder = self.store.output_directory("s", "run-many")
        for i in range(75):
            (folder / f"new-{i:03}.txt").write_text("new")
        self.assertEqual(75, len(self.store.collect_outputs("s", "run-many", folder=folder)))

    def test_quota_and_unsafe_outputs_are_visible_without_hiding_good_files(self):
        folder = self.store.output_directory("s", "run-partial")
        (folder / "good.txt").write_text("ok")
        (folder / "oversized.txt").write_text("too large")
        (folder / "symlink.txt").symlink_to(folder / "good.txt")
        with patch("plugins.bellpull.artifacts.MAX_SCOPE_BYTES", 3):
            self.assertEqual(1, len(self.store.collect_outputs("s", "run-partial", folder=folder)))
        self.assertEqual(2, len(self.store.warnings()))

    def test_delete_is_idempotent_and_retry_cannot_resurrect_a_file(self):
        row = self.store.store([b"keep"], session_id="s", name="keep.txt", mime="text/plain", artifact_id="a" * 32)
        self.store.delete(row["id"]); self.store.delete(row["id"])
        self.assertFalse(self.store.metadata(row["id"])["available"])
        self.assertEqual(0, self.store.usage()["used_bytes"])
        with self.assertRaises(ArtifactError):
            self.store.store([b"keep"], session_id="s", name="keep.txt", mime="text/plain", artifact_id=row["id"])

    def test_cleanup_keeps_ongoing_uploads_and_all_persistent_files(self):
        row = self.store.store([b"keep"], session_id="s", name="keep.txt", mime="text/plain")
        old = self.store.blobs / ".upload-old"
        old.write_text("partial"); os.utime(old, (1, 1))
        current = self.store.blobs / ".upload-current"
        current.write_text("current")
        self.store.cleanup()
        self.assertFalse(old.exists()); self.assertTrue(current.exists())
        self.assertTrue(self.store.metadata(row["id"])["available"])

    def test_offline_migration_preserves_ids_metadata_and_original_downloads(self):
        legacy_scope = "1" * 64
        legacy = ArtifactStore(self.home, legacy_scope)
        row = legacy.store([b"history"], session_id="s", name="old.txt", mime="text/plain")
        dry = migrate(self.home, [legacy_scope])
        self.assertFalse(dry["applied"])
        result = migrate(self.home, [legacy_scope], apply=True, offline=True)
        self.assertTrue(result["applied"])
        current = ArtifactStore(self.home, artifact_scope(self.home))
        self.assertEqual(row, current.metadata(row["id"]))
        self.assertEqual(b"history", legacy.path(row["id"])[0].read_bytes())
        self.assertEqual(b"history", current.path(row["id"])[0].read_bytes())
        migrate(self.home, [legacy_scope], apply=True, offline=True)
        self.assertEqual(1, len(current.list()))

    def test_migration_requires_explicit_offline_acknowledgment(self):
        legacy = ArtifactStore(self.home, "1" * 64)
        legacy.store([b"history"], session_id="s", name="old.txt", mime="text/plain")
        with self.assertRaises(ValueError):
            migrate(self.home, ["1" * 64], apply=True)

    def test_branch_and_edit_links_follow_only_proven_prefix_and_survive_rotation(self):
        row = self.store.store([b"input"], session_id="s", name="input.txt", mime="text/plain")
        with self.store._db() as database:
            database.execute("UPDATE artifacts SET message_id = 10 WHERE id = ?", (row["id"],))
        self.store.inherit_prefix("s", "branch", {10: 20})
        self.store.inherit_prefix("branch", "edited", {20: 30})
        item = self.store.list("edited")[0]
        self.assertEqual({"branch": 20, "edited": 30}, item["message_ids"])
        self.assertEqual(b"input", self.store.path(row["id"], "edited")[0].read_bytes())
        self.store.inherit_prefix("s", "excluded", {11: 40})
        self.assertEqual([], self.store.list("excluded"))
        self.store.delete(row["id"])
        self.assertFalse(self.store.list("branch")[0]["available"])

    def test_repeated_runs_keep_their_own_generated_file_associations(self):
        first = self.store.output_directory("s", "first")
        second = self.store.output_directory("s", "second")
        (first / "report.txt").write_text("identical content")
        (second / "report.txt").write_text("identical content")
        a = self.store.collect_outputs("s", "first", folder=first)[0]
        b = self.store.collect_outputs("s", "second", folder=second)[0]
        self.assertNotEqual(a["id"], b["id"])
        self.assertEqual(a["id"], self.store.collect_outputs("s", "first", folder=first)[0]["id"])


if __name__ == "__main__":
    unittest.main()
