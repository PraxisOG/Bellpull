"""Stock outbox lifecycle with real SQLite, bounded retries and injected failures."""
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

from hermes_state import SessionDB
from plugins.bellpull import artifacts
from plugins.bellpull.principal import artifact_scope
from plugins.bellpull.sharing import share_files


class SharingTest(unittest.TestCase):
    def setUp(self):
        # Thousands of real SQLite commits should not benchmark Docker overlay fsync.
        self.temp = tempfile.TemporaryDirectory(dir="/dev/shm" if Path("/dev/shm").is_dir() else None)
        self.home = Path(self.temp.name)
        self.env = patch.dict(os.environ, {"HERMES_HOME": str(self.home)})
        self.env.start()
        db = SessionDB(self.home / "state.db")
        db.create_session("chat", "cli")
        db.close()
        self.store = artifacts.ArtifactStore(self.home, artifact_scope(self.home))
        self.folder = Path(share_files({"action": "prepare"}, session_id="chat")["outbox"])

    def tearDown(self):
        self.env.stop()
        self.temp.cleanup()

    def publish(self):
        return share_files({"action": "publish"}, session_id="chat")

    def test_publish_retry_delete_and_housekeeping_leave_no_retired_source_bytes(self):
        source = self.folder / "answer.txt"
        source.write_bytes(b"immutable")
        result = self.publish()
        row = result["artifacts"][0]
        self.assertEqual("published", result["status"])
        self.assertFalse(source.exists())
        self.assertEqual("empty", self.publish()["status"])
        self.assertEqual(b"immutable", self.store.path(row["id"])[0].read_bytes())
        self.store.delete(row["id"])
        self.store.cleanup(now=time.time() + 40 * 86400)
        self.assertEqual(0, self.store.usage()["used_bytes"])
        self.assertEqual([], list(self.folder.iterdir()))
        source.write_bytes(b"immutable")
        retry = self.publish()
        self.assertEqual("partial", retry["status"])
        self.assertTrue(source.exists()) # Explicit deletion cannot be resurrected by a retry.
        self.assertIn("explicitly deleted", retry["warnings"][0]["message"])

    def test_more_than_1000_historical_publications_do_not_starve_new_outputs(self):
        for index in range(1001):
            (self.folder / f"file-{index:04d}.txt").write_bytes(b"x")
        first = self.publish()
        self.assertEqual(("partial", 1000, 1), (first["status"], first["published_count"], first["remaining_count"]))
        (self.folder / "zz-new.txt").write_bytes(b"new")
        second = self.publish()
        self.assertEqual({"file-1000.txt", "zz-new.txt"}, {row["name"] for row in second["artifacts"]})
        self.assertEqual("published", second["status"])
        (self.folder / "zzz-latest.txt").write_bytes(b"latest")
        self.assertEqual(["zzz-latest.txt"], [row["name"] for row in self.publish()["artifacts"]])
        self.assertEqual(1003, len(self.store.list()))

    def test_quota_failure_preserves_unpublished_files_and_recovers(self):
        (self.folder / "a.txt").write_bytes(b"aaaa")
        (self.folder / "b.txt").write_bytes(b"bbbb")
        with patch.object(artifacts, "MAX_SCOPE_BYTES", 5):
            first = self.publish()
            self.assertEqual(("partial", 1, 1), (first["status"], first["published_count"], first["remaining_count"]))
            self.assertFalse((self.folder / "a.txt").exists())
            self.assertTrue((self.folder / "b.txt").exists())
            self.assertIn("storage is full", first["warnings"][0]["message"])
        recovered = self.publish()
        self.assertEqual("published", recovered["status"])
        self.assertEqual("b.txt", recovered["artifacts"][0]["name"])
        self.assertEqual(b"aaaa", self.store.path(first["artifacts"][0]["id"])[0].read_bytes())

    def test_interruption_after_commit_retries_same_identity_before_retiring(self):
        source = self.folder / "result.txt"
        source.write_bytes(b"result")
        original = artifacts.ArtifactStore.store
        def interrupted(store, *args, **kwargs):
            original(store, *args, **kwargs)
            raise OSError("interrupted after durable commit")
        with patch.object(artifacts.ArtifactStore, "store", interrupted):
            self.assertEqual("partial", self.publish()["status"])
        saved_id = self.store.list()[0]["id"]
        self.assertTrue(source.exists())
        recovered = self.publish()
        self.assertEqual(saved_id, recovered["artifacts"][0]["id"])
        self.assertFalse(source.exists())
        self.assertEqual(1, len(self.store.list()))

    def test_failed_blob_commit_keeps_staging_for_retry(self):
        source = self.folder / "result.txt"
        source.write_bytes(b"recoverable")
        with patch.object(artifacts.ArtifactStore, "_commit_blob", side_effect=OSError("disk sync failed")):
            result = self.publish()
        self.assertEqual("partial", result["status"])
        self.assertEqual([], self.store.list())
        self.assertEqual(b"recoverable", source.read_bytes())
        self.assertEqual("published", self.publish()["status"])

    def test_changed_staging_copy_is_retained_and_outside_files_cannot_be_retired(self):
        source = self.folder / "result.txt"
        source.write_bytes(b"first")
        original = artifacts.ArtifactStore.store
        def replaced(store, *args, **kwargs):
            result = original(store, *args, **kwargs)
            source.unlink()
            source.write_bytes(b"replacement")
            return result
        with patch.object(artifacts.ArtifactStore, "store", replaced):
            result = self.publish()
        self.assertEqual("partial", result["status"])
        self.assertEqual(b"replacement", source.read_bytes())
        self.assertEqual(b"first", self.store.path(result["artifacts"][0]["id"])[0].read_bytes())
        self.assertEqual("published", self.publish()["status"])
        with self.assertRaises(artifacts.ArtifactError):
            self.store.collect_outputs("chat", "shared-outbox", folder=self.home, retire=True)

    def test_failed_old_files_do_not_starve_new_name_after_collection_limit(self):
        for index in range(1000):
            (self.folder / f"empty-{index:04d}.txt").touch()
        self.assertEqual("partial", self.publish()["status"])
        (self.folder / "zz-new.txt").write_bytes(b"new")
        self.assertEqual(["zz-new.txt"], [row["name"] for row in self.publish()["artifacts"]])
