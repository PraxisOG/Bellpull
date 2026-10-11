"""Hermetic persistent artifact tests; no running gateway or inference needed."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

from plugins.bellpull import artifacts


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.home = Path(self.temp.name)
        self.a = artifacts.ArtifactStore(self.home, "a" * 64)
        self.b = artifacts.ArtifactStore(self.home, "b" * 64)

    def tearDown(self):
        self.temp.cleanup()

    def upload(self, **kw):
        return self.a.store([b"original PDF bytes"], session_id="chat_a", name="Report.pdf",
                            mime="application/pdf", **kw)

    def test_repeatable_download_survives_store_reopen_and_is_scoped(self):
        row = self.upload()
        fresh = artifacts.ArtifactStore(self.home, "a" * 64)
        for _ in range(2):
            path, record = fresh.path(row["id"])
            self.assertEqual(b"original PDF bytes", path.read_bytes())
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), record["sha256"])
            self.assertNotIn("blob", record)
        with self.assertRaises(artifacts.ArtifactError):
            self.b.path(row["id"])
        self.assertEqual([], self.b.list())

    def test_retried_upload_deduplicates_but_changes_conflict(self):
        one = self.upload(artifact_id="1" * 32)
        self.assertEqual(one, self.upload(artifact_id="1" * 32))
        with self.assertRaises(artifacts.ArtifactError) as ctx:
            self.a.store([b"different"], session_id="chat_a", name="Report.pdf",
                         mime="application/pdf", artifact_id="1" * 32)
        self.assertEqual(409, ctx.exception.status)
        self.assertEqual(1, len(self.a.list()))

    def test_cancelled_or_oversize_upload_leaves_no_record_or_temporary_file(self):
        def broken():
            yield b"partial"
            raise OSError("content provider stopped")
        with self.assertRaises(OSError):
            self.a.store(broken(), session_id="chat_a", name="file.txt", mime="text/plain")
        with self.assertRaises(artifacts.ArtifactError):
            self.a.store([b"x" * (artifacts.MAX_BYTES + 1)], session_id="chat_a", name="file.txt", mime="text/plain")
        self.assertEqual([], self.a.list())
        self.assertEqual([], list(self.a.blobs.iterdir()))

    def test_paths_and_cross_conversation_associations_are_rejected(self):
        for name in ("../key", "/etc/passwd", "a\\b", "x\nkey"):
            with self.assertRaises(artifacts.ArtifactError):
                artifacts.checked_name(name)
        row = self.upload()
        with self.assertRaises(artifacts.ArtifactError):
            self.a.attach([row["id"]], "chat_b", "request_b")
        self.a.attach([row["id"]], "chat_a", "request_a")
        self.a.attach([row["id"]], "chat_a", "request_a")
        with self.assertRaises(artifacts.ArtifactError):
            self.a.attach([row["id"]], "chat_a", "request_b")

    def test_missing_content_is_unavailable_not_a_successful_empty_download(self):
        row = self.upload()
        path, _ = self.a.path(row["id"])
        path.unlink()
        self.assertFalse(self.a.metadata(row["id"])["available"])
        with self.assertRaises(artifacts.ArtifactError) as ctx:
            self.a.path(row["id"])
        self.assertEqual(410, ctx.exception.status)

    def test_generated_files_are_immutable_scoped_and_do_not_follow_links(self):
        folder = self.a.output_directory("chat_a")
        (folder / "answer.txt").write_text("First result")
        secret = self.home / "private.txt"
        secret.write_text("must not be indexed")
        (folder / "symlink.txt").symlink_to(secret)
        (folder / "hardlink.txt").hardlink_to(secret)
        first = self.a.collect_outputs("chat_a", "request_a")
        self.assertEqual(["answer.txt"], [r["name"] for r in first])
        self.assertEqual("agent", first[0]["source"])
        self.a.collect_outputs("chat_a", "request_a")
        self.assertEqual(1, len(self.a.list()))
        (folder / "answer.txt").write_text("New result")
        self.a.collect_outputs("chat_a", "request_b")
        self.assertEqual(2, len(self.a.list()))
        self.assertEqual(b"First result", self.a.path(first[0]["id"])[0].read_bytes())


if __name__ == "__main__":
    unittest.main()
