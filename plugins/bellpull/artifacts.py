"""Persistent, principal-scoped Bellpull artifacts. No network or inference dependencies."""
from __future__ import annotations

import hashlib
from contextlib import contextmanager
import mimetypes
import os
from pathlib import Path
import re
import sqlite3
import stat
import time
import uuid

MAX_BYTES = 25 * 1024 * 1024
MAX_SCOPE_BYTES = 512 * 1024 * 1024
MAX_ATTACHMENTS = 8
_ID = re.compile(r"[a-f0-9]{32}")
_SCOPE = re.compile(r"[a-f0-9]{64}")
_SESSION = re.compile(r"[a-zA-Z0-9_-]{1,200}")
_TYPES = {
    "image/png": ".png", "image/jpeg": ".jpg", "image/webp": ".webp",
    "application/pdf": ".pdf", "text/plain": ".txt", "text/markdown": ".md",
    "text/csv": ".csv", "application/json": ".json", "application/zip": ".zip",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document": ".docx",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": ".xlsx",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation": ".pptx",
    "application/octet-stream": ".bin",
}


class ArtifactError(ValueError):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


def checked_name(value):
    value = str(value or "").strip()
    if not value or len(value) > 180 or any(ord(c) < 32 or c in "/\\" for c in value) or value in (".", ".."):
        raise ArtifactError("Use a filename without folders or control characters.")
    return value


def checked_session(value):
    if not isinstance(value, str) or not _SESSION.fullmatch(value):
        raise ArtifactError("Invalid conversation identity.")
    return value


def checked_type(value):
    value = str(value or "application/octet-stream").split(";", 1)[0].strip().lower()
    if value not in _TYPES:
        raise ArtifactError("This file type is not supported for upload.", 415)
    return value


class ArtifactStore:
    """Immutable content plus metadata. All paths are generated, never supplied by clients.

    SQLite serializes quota/identity decisions between concurrent upload workers.
    Durable request retries use the same upload ID and digest; changed bytes conflict.
    Each call owns its connection, so no SQLite connection crosses request threads.
    """
    def __init__(self, home: Path, scope: str):
        if not _SCOPE.fullmatch(scope):
            raise ArtifactError("Invalid artifact scope.")
        self.root = Path(home) / "bellpull-artifacts" / scope
        if self.root.is_symlink() or self.root.parent.is_symlink():
            raise ArtifactError("Artifact namespace is unsafe.", 410)
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.blobs = self.root / "content"
        if self.blobs.is_symlink():
            raise ArtifactError("Artifact content folder is unsafe.", 410)
        self.blobs.mkdir(exist_ok=True, mode=0o700)
        self.output = self.root / "outputs"
        if self.output.is_symlink():
            raise ArtifactError("Artifact output folder is unsafe.", 410)
        self.output.mkdir(exist_ok=True, mode=0o700)
        with self._db() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS artifacts (
                id TEXT PRIMARY KEY, session_id TEXT NOT NULL, name TEXT NOT NULL,
                mime TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL,
                created_at REAL NOT NULL, source TEXT NOT NULL, request_id TEXT,
                message_id INTEGER, blob TEXT NOT NULL)""")
            db.execute("CREATE INDEX IF NOT EXISTS artifacts_session ON artifacts(session_id, created_at)")
            if "deleted_at" not in {row[1] for row in db.execute("PRAGMA table_info(artifacts)")}:
                db.execute("ALTER TABLE artifacts ADD COLUMN deleted_at REAL")
            db.execute("""CREATE TABLE IF NOT EXISTS collection_warnings (
                request_id TEXT NOT NULL, name TEXT NOT NULL, message TEXT NOT NULL,
                created_at REAL NOT NULL, PRIMARY KEY(request_id, name))""")
            db.execute("""CREATE TABLE IF NOT EXISTS artifact_links (
                artifact_id TEXT NOT NULL, session_id TEXT NOT NULL, message_id INTEGER,
                PRIMARY KEY(artifact_id, session_id))""")

    @contextmanager
    def _db(self):
        db = sqlite3.connect(self.root / "metadata.sqlite", timeout=15)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def _row(self, artifact_id):
        if not isinstance(artifact_id, str) or not _ID.fullmatch(artifact_id):
            raise ArtifactError("Invalid attachment identity.")
        with self._db() as db:
            row = db.execute("SELECT * FROM artifacts WHERE id = ?", (artifact_id,)).fetchone()
        if row is None:
            raise ArtifactError("Attachment is unavailable on this server.", 404)
        return dict(row)

    def _path(self, row):
        candidate = self.blobs / row["blob"]
        if candidate.is_symlink() or candidate.parent.resolve() != self.blobs.resolve():
            raise ArtifactError("Attachment content is unavailable.", 410)
        return candidate

    def _public(self, row):
        value = {key: row[key] for key in (
            "id", "session_id", "name", "mime", "size", "sha256", "created_at", "source", "request_id", "message_id")}
        try:
            path = self._path(row)
            value["available"] = not row.get("deleted_at") and path.is_file() and path.stat().st_size == row["size"]
        except (ArtifactError, OSError):
            value["available"] = False
        value["deleted"] = row.get("deleted_at") is not None
        with self._db() as db:
            links = db.execute("SELECT session_id, message_id FROM artifact_links WHERE artifact_id = ?", (row["id"],)).fetchall()
        value["message_ids"] = {link["session_id"]: link["message_id"] for link in links}
        value["session_ids"] = [row["session_id"]] + [link["session_id"] for link in links]
        return value

    def list(self, session_id=None):
        if session_id is not None:
            checked_session(session_id)
        with self._db() as db:
            rows = db.execute("SELECT a.* FROM artifacts a" +
                (" WHERE a.session_id = ? OR EXISTS (SELECT 1 FROM artifact_links l WHERE l.artifact_id = a.id AND l.session_id = ?)" if session_id else "") +
                " ORDER BY created_at DESC", (session_id, session_id) if session_id else ()).fetchall()
        return [self._public(dict(row)) for row in rows]

    def metadata(self, artifact_id):
        return self._public(self._row(artifact_id))

    def path(self, artifact_id, session_id=None):
        row = self._row(artifact_id)
        if session_id is not None and row["session_id"] != session_id:
            with self._db() as db:
                linked = db.execute("SELECT 1 FROM artifact_links WHERE artifact_id = ? AND session_id = ?", (artifact_id, session_id)).fetchone()
            if linked is None:
                raise ArtifactError("Attachment belongs to another conversation.", 409)
        path = self._path(row)
        if row.get("deleted_at") or not path.is_file() or path.stat().st_size != row["size"]:
            raise ArtifactError("Attachment content is no longer available.", 410)
        return path, self._public(row)

    def store(self, chunks, *, session_id, name, mime, artifact_id=None, source="upload"):
        session_id, name, mime = checked_session(session_id), checked_name(name), checked_type(mime)
        artifact_id = artifact_id or uuid.uuid4().hex
        if not _ID.fullmatch(artifact_id) or source not in ("upload", "agent"):
            raise ArtifactError("Invalid attachment identity or source.")
        # Keep a type-derived extension for the existing paginated document tools.
        blob = artifact_id + _TYPES[mime]
        temporary = self.blobs / (".upload-" + uuid.uuid4().hex)
        size = 0
        digest = hashlib.sha256()
        try:
            with temporary.open("xb") as file:
                os.chmod(temporary, 0o600)
                for chunk in chunks:
                    size += len(chunk)
                    if size > MAX_BYTES:
                        raise ArtifactError("File exceeds the 25 MiB upload limit.", 413)
                    digest.update(chunk)
                    file.write(chunk)
                if size == 0:
                    raise ArtifactError("The selected file is empty.")
                file.flush()
                os.fsync(file.fileno())
            sha256 = digest.hexdigest()
            with self._db() as db:
                db.execute("BEGIN IMMEDIATE")
                old = db.execute("SELECT * FROM artifacts WHERE id = ?", (artifact_id,)).fetchone()
                if old:
                    if old["deleted_at"] is not None:
                        raise ArtifactError("This attachment was explicitly deleted. Choose a new upload identity.", 410)
                    if any(old[key] != value for key, value in {
                        "session_id": session_id, "name": name, "mime": mime,
                        "sha256": sha256, "size": size, "source": source}.items()):
                        raise ArtifactError("Upload identity already belongs to different content.", 409)
                    if not self._public(dict(old))["available"]:
                        os.replace(temporary, self._path(dict(old)))
                    return self._public(dict(old))
                total = db.execute("SELECT COALESCE(SUM(size), 0) FROM artifacts WHERE deleted_at IS NULL").fetchone()[0]
                if total + size > MAX_SCOPE_BYTES:
                    raise ArtifactError("Server attachment storage is full (512 MiB per account).", 507)
                os.replace(temporary, self.blobs / blob)
                db.execute("INSERT INTO artifacts (id, session_id, name, mime, size, sha256, created_at, source, request_id, message_id, blob) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?)",
                           (artifact_id, session_id, name, mime, size, sha256, time.time(), source, blob))
            return self.metadata(artifact_id)
        finally:
            temporary.unlink(missing_ok=True)

    def attach(self, ids, session_id, request_id):
        if (not isinstance(ids, list) or len(ids) > MAX_ATTACHMENTS or
                any(not isinstance(value, str) for value in ids) or len(set(ids)) != len(ids)):
            raise ArtifactError("Choose up to eight different attachments.")
        checked_session(session_id)
        rows = [self.path(value, session_id)[1] for value in ids]
        if sum(row["size"] for row in rows) > MAX_BYTES:
            raise ArtifactError("Combined attachments exceed 25 MiB.", 413)
        with self._db() as db:
            db.execute("BEGIN IMMEDIATE")
            for row in rows:
                current = db.execute("SELECT request_id FROM artifacts WHERE id = ?", (row["id"],)).fetchone()[0]
                if current not in (None, request_id):
                    raise ArtifactError("Attachment was already submitted with another message.", 409)
                db.execute("UPDATE artifacts SET request_id = ? WHERE id = ?", (request_id, row["id"]))
        return rows

    def output_directory(self, session_id, request_id=None, *, create=True):
        folder = self.output / checked_session(session_id)
        if folder.is_symlink():
            raise ArtifactError("Conversation output folder is unavailable.", 410)
        if create:
            folder.mkdir(exist_ok=True, mode=0o700)
        if request_id:
            folder = folder / checked_session(request_id)
            if folder.is_symlink():
                raise ArtifactError("Run output folder is unavailable.", 410)
            if create:
                folder.mkdir(exist_ok=True, mode=0o700)
        return folder

    def collect_outputs(self, session_id, request_id, *, folder=None):
        """Copy bounded regular generated files into immutable authenticated records.

        Never follow symlinks, devices, hard links, subfolders or caller-supplied paths.
        A file recreated with identical bytes deduplicates; changed bytes get a new ID.
        """
        folder = folder or self.output_directory(session_id)
        records = []
        for index, source in enumerate(sorted(folder.iterdir())):
            if source.name in (".active", ".complete"):
                continue
            if index >= 1000:
                self.warning(request_id, "collection-limit", "More than 1000 outputs: remaining files need explicit collection.")
                break
            try:
                name = checked_name(source.name)
                fd = os.open(source, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
                with os.fdopen(fd, "rb") as file:
                    info = os.fstat(file.fileno())
                    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or not 0 < info.st_size <= MAX_BYTES:
                        self.warning(request_id, name, "Output is empty, oversized, or not a safe regular file.")
                        continue
                    data = file.read(MAX_BYTES + 1)
                digest = hashlib.sha256(data).hexdigest()
                identity = hashlib.sha256((session_id + "\0" + request_id + "\0" + name + "\0" + digest).encode()).hexdigest()[:32]
                mime = mimetypes.guess_type(name)[0] or "application/octet-stream"
                if mime not in _TYPES:
                    mime = "application/octet-stream"
                row = self.store([data], session_id=session_id, name=name, mime=mime,
                                 artifact_id=identity, source="agent")
                with self._db() as db:
                    db.execute("UPDATE artifacts SET request_id = COALESCE(request_id, ?) WHERE id = ?",
                               (request_id, identity))
                records.append(row)
            except (OSError, ArtifactError) as exc:
                # One unavailable output must not hide the other valid artifacts.
                self.warning(request_id, source.name[:180], str(exc) if isinstance(exc, ArtifactError) else "Output could not be read safely.")
                continue
        return records

    def warning(self, request_id, name, message):
        with self._db() as db:
            db.execute("INSERT OR REPLACE INTO collection_warnings VALUES (?, ?, ?, ?)",
                (request_id, name, message, time.time()))

    def warnings(self):
        with self._db() as db:
            return [dict(row) for row in db.execute("SELECT * FROM collection_warnings ORDER BY created_at DESC LIMIT 100")]

    def usage(self):
        with self._db() as db:
            used = db.execute("SELECT COALESCE(SUM(size), 0) FROM artifacts WHERE deleted_at IS NULL").fetchone()[0]
        return {"used_bytes": used, "quota_bytes": MAX_SCOPE_BYTES, "retention_seconds": None,
                "scratch_retention_seconds": 7 * 86400, "upload_cleanup_seconds": 86400}

    def delete(self, artifact_id):
        row = self._row(artifact_id)
        # Persist intent before removing bytes: retries cannot resurrect the content.
        with self._db() as db:
            db.execute("UPDATE artifacts SET deleted_at = COALESCE(deleted_at, ?) WHERE id = ?", (time.time(), artifact_id))
        self._path(row).unlink(missing_ok=True)

    def cleanup(self, *, now=None):
        now = time.time() if now is None else now
        with self._db() as db:
            db.execute("DELETE FROM collection_warnings WHERE created_at < ?", (now - 30 * 86400,))
            owned = {row[0] for row in db.execute("SELECT blob FROM artifacts WHERE deleted_at IS NULL")}
        for path in self.blobs.iterdir():
            if path.is_symlink():
                continue
            if path.name not in owned and path.is_file() and now - path.stat().st_mtime > 86400:
                path.unlink(missing_ok=True)
        # Completed run scratch only. Unfinished/interrupted runs require review.
        import shutil
        for marker in self.output.glob("*/*/.complete"):
            if not marker.is_symlink() and now - marker.stat().st_mtime > 7 * 86400:
                if not marker.parent.is_symlink() and not marker.parent.parent.is_symlink():
                    shutil.rmtree(marker.parent)

    def inherit_prefix(self, source_id, branch_id, message_map):
        """Only proven message associations belong to an exact prefix. Legacy records
        without an association remain downloadable in their original conversation."""
        with self._db() as db:
            rows = db.execute("SELECT id, message_id FROM artifacts WHERE session_id = ? UNION "
                "SELECT artifact_id AS id, message_id FROM artifact_links WHERE session_id = ?", (source_id, source_id)).fetchall()
            for row in rows:
                if row["message_id"] in message_map:
                    db.execute("INSERT OR IGNORE INTO artifact_links VALUES (?, ?, ?)",
                        (row["id"], branch_id, message_map[row["message_id"]]))
