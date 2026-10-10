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
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.blobs = self.root / "content"
        self.blobs.mkdir(exist_ok=True, mode=0o700)
        self.output = self.root / "outputs"
        self.output.mkdir(exist_ok=True, mode=0o700)
        with self._db() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS artifacts (
                id TEXT PRIMARY KEY, session_id TEXT NOT NULL, name TEXT NOT NULL,
                mime TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL,
                created_at REAL NOT NULL, source TEXT NOT NULL, request_id TEXT,
                message_id INTEGER, blob TEXT NOT NULL)""")
            db.execute("CREATE INDEX IF NOT EXISTS artifacts_session ON artifacts(session_id, created_at)")

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
        path = self._path(row)
        value["available"] = path.is_file() and path.stat().st_size == row["size"]
        return value

    def list(self, session_id=None):
        if session_id is not None:
            checked_session(session_id)
        with self._db() as db:
            rows = db.execute("SELECT * FROM artifacts" + (" WHERE session_id = ?" if session_id else "") +
                              " ORDER BY created_at DESC LIMIT 2000", (session_id,) if session_id else ()).fetchall()
        return [self._public(dict(row)) for row in rows]

    def metadata(self, artifact_id):
        return self._public(self._row(artifact_id))

    def path(self, artifact_id, session_id=None):
        row = self._row(artifact_id)
        if session_id is not None and row["session_id"] != session_id:
            raise ArtifactError("Attachment belongs to another conversation.", 409)
        path = self._path(row)
        if not path.is_file() or path.stat().st_size != row["size"]:
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
                    if any(old[key] != value for key, value in {
                        "session_id": session_id, "name": name, "mime": mime,
                        "sha256": sha256, "size": size, "source": source}.items()):
                        raise ArtifactError("Upload identity already belongs to different content.", 409)
                    if not self._public(dict(old))["available"]:
                        os.replace(temporary, self._path(dict(old)))
                    return self._public(dict(old))
                total = db.execute("SELECT COALESCE(SUM(size), 0) FROM artifacts").fetchone()[0]
                if total + size > MAX_SCOPE_BYTES:
                    raise ArtifactError("Server attachment storage is full (512 MiB per account).", 507)
                os.replace(temporary, self.blobs / blob)
                db.execute("INSERT INTO artifacts VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?)",
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

    def output_directory(self, session_id):
        folder = self.output / checked_session(session_id)
        if folder.is_symlink():
            raise ArtifactError("Conversation output folder is unavailable.", 410)
        folder.mkdir(exist_ok=True, mode=0o700)
        return folder

    def collect_outputs(self, session_id, request_id):
        """Copy bounded regular generated files into immutable authenticated records.

        Never follow symlinks, devices, hard links, subfolders or caller-supplied paths.
        A file recreated with identical bytes deduplicates; changed bytes get a new ID.
        """
        folder = self.output_directory(session_id)
        records = []
        for index, source in enumerate(sorted(folder.iterdir())):
            if index >= 64:
                break
            try:
                name = checked_name(source.name)
                fd = os.open(source, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
                with os.fdopen(fd, "rb") as file:
                    info = os.fstat(file.fileno())
                    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or not 0 < info.st_size <= MAX_BYTES:
                        continue
                    data = file.read(MAX_BYTES + 1)
                digest = hashlib.sha256(data).hexdigest()
                identity = hashlib.sha256((session_id + "\0" + name + "\0" + digest).encode()).hexdigest()[:32]
                mime = mimetypes.guess_type(name)[0] or "application/octet-stream"
                if mime not in _TYPES:
                    mime = "application/octet-stream"
                row = self.store([data], session_id=session_id, name=name, mime=mime,
                                 artifact_id=identity, source="agent")
                with self._db() as db:
                    db.execute("UPDATE artifacts SET request_id = COALESCE(request_id, ?) WHERE id = ?",
                               (request_id, identity))
                records.append(row)
            except (OSError, ArtifactError):
                # One unavailable output must not hide the other valid artifacts.
                continue
        return records
