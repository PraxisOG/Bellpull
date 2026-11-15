"""Explicit file references, bounded text reads and optimistic Markdown list edits."""
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import sqlite3
import stat
import time
import uuid
from .artifacts import ArtifactError

MAX_TEXT = 2 * 1024 * 1024
TASK = re.compile(r"^([ \t]*(?:[-+*]|\d+[.)])[ \t]+)\[([ xX])\]([ \t]+)(.*?)(\r?\n)?$")


def tasks(text):
    result = []
    fence = None
    for number, line in enumerate(text.splitlines(keepends=True)):
        stripped = line.lstrip()
        indent = len(line) - len(stripped)
        marker = re.match(r"(`{3,}|~{3,})", stripped) if indent <= 3 else None
        if marker:
            value = marker.group(1)
            if fence is None:
                fence = value
            elif value[0] == fence[0] and len(value) >= len(fence) and not stripped[len(value):].strip():
                fence = None
            continue
        # Indented code must never become an actionable checkbox. Deeper nested
        # lists remain Markdown; the native editing surface covers top-level lists.
        if fence is None and indent <= 3 and not line.startswith("\t") and (match := TASK.match(line)):
            result.append({"line": number, "text": match[4], "checked": match[2].lower() == "x"})
    return result


@contextmanager
def opened(path):
    """Walk held directory descriptors; never traverse a symlink or a special file."""
    if not isinstance(path, str) or len(path) > 4096 or "\0" in path:
        raise ArtifactError("Provide an absolute file path.")
    parts = PurePosixPath(path).parts
    if not parts or parts[0] != "/" or ".." in parts or len(parts) < 2 or parts[1] in {"proc", "sys", "dev"}:
        raise ArtifactError("Provide an absolute path to an ordinary text file.")
    parent = os.open("/", os.O_RDONLY | os.O_DIRECTORY)
    file = None
    try:
        for name in parts[1:-1]:
            next_parent = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
            os.close(parent); parent = next_parent
        file = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
        info = os.fstat(file)
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
            raise ArtifactError("Only regular text files without hard links can be referenced.", 403)
        yield parent, file, parts[-1], info
    except FileNotFoundError as exc:
        raise ArtifactError("This referenced file is no longer available.", 404) from exc
    except OSError as exc:
        raise ArtifactError("This file is not accessible; symlink traversal is not supported.", 403) from exc
    finally:
        if file is not None: os.close(file)
        os.close(parent)


def read_text(fd, info):
    if info.st_size > MAX_TEXT:
        raise ArtifactError("This document exceeds the 2 MiB text-view limit.", 413)
    os.lseek(fd, 0, os.SEEK_SET)
    data = bytearray()
    while chunk := os.read(fd, min(65536, MAX_TEXT + 1 - len(data))):
        data.extend(chunk)
        if len(data) > MAX_TEXT:
            raise ArtifactError("This document exceeds the 2 MiB text-view limit.", 413)
    after = os.fstat(fd)
    if (info.st_size, info.st_mtime_ns, info.st_ctime_ns) != (after.st_size, after.st_mtime_ns, after.st_ctime_ns):
        raise ArtifactError("The document changed while being read. Refresh it.", 409)
    try:
        text = bytes(data).decode("utf-8")
    except UnicodeError as exc:
        raise ArtifactError("Only UTF-8 text documents are supported.", 415) from exc
    if any(ord(c) < 32 and c not in "\t\r\n" for c in text):
        raise ArtifactError("This file contains binary data.", 415)
    return text


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


class LiveFiles:
    def __init__(self, home, scope):
        if not re.fullmatch(r"[a-f0-9]{64}", scope): raise ArtifactError("Invalid file scope.")
        self.scope = scope
        self.root = Path(home) / "bellpull-live-files" / scope
        if self.root.is_symlink() or self.root.parent.is_symlink(): raise ArtifactError("Invalid file registry.", 403)
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.database = self.root / "files.sqlite"
        if self.database.is_symlink(): raise ArtifactError("Invalid file registry.", 403)
        with self.db() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS files (id TEXT PRIMARY KEY, path TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL, access TEXT NOT NULL, pinned INTEGER NOT NULL DEFAULT 0,
                referenced_at REAL NOT NULL, accessed_at REAL NOT NULL DEFAULT 0,
                reference_revision INTEGER NOT NULL DEFAULT 1, session_id TEXT NOT NULL)""")
            db.execute("""CREATE TABLE IF NOT EXISTS edits (id TEXT PRIMARY KEY, file_id TEXT NOT NULL,
                request_hash TEXT NOT NULL, before_hash TEXT NOT NULL, after_hash TEXT NOT NULL,
                state TEXT NOT NULL, created_at REAL NOT NULL)""")
        os.chmod(self.database, 0o600)

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.database, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db: yield db
        finally: db.close()

    @contextmanager
    def lock(self):
        fd = os.open(self.root / "edit.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "w") as file:
            fcntl.flock(file, fcntl.LOCK_EX)
            yield

    def entry(self, identity):
        if not re.fullmatch(r"[a-f0-9]{32}", identity): raise ArtifactError("Unknown file.", 404)
        with self.db() as db: row = db.execute("SELECT * FROM files WHERE id=?", (identity,)).fetchone()
        if row is None: raise ArtifactError("This file has not been added to Bellpull.", 404)
        return dict(row) | {"pinned": bool(row["pinned"])}

    def list(self):
        with self.db() as db:
            return {"version": 1, "files": [dict(row) | {"pinned": bool(row["pinned"])} for row in db.execute(
                "SELECT * FROM files ORDER BY pinned DESC, max(accessed_at,referenced_at) DESC, id LIMIT 2000")]}

    def reference(self, path, session, name=None, access=None, pinned=None):
        with opened(path) as (_, fd, _, info): read_text(fd, info)
        path = str(PurePosixPath(path))
        identity = hashlib.sha256((self.scope + ":" + path).encode()).hexdigest()[:32]
        if access is not None and access not in {"read", "checklist"}: raise ArtifactError("Choose read or checklist access.")
        if name is not None and (not isinstance(name, str) or not name.strip() or len(name) > 240): raise ArtifactError("Invalid document name.")
        if pinned is not None and type(pinned) is not bool: raise ArtifactError("Pinned must be a boolean.")
        with self.lock(), self.db() as db:
            old = db.execute("SELECT * FROM files WHERE id=?", (identity,)).fetchone()
            if old is None and db.execute("SELECT COUNT(*) FROM files").fetchone()[0] >= 2000:
                raise ArtifactError("The file reference limit has been reached.", 413)
            db.execute("""INSERT INTO files(id,path,name,access,pinned,referenced_at,session_id) VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET name=excluded.name,access=excluded.access,pinned=excluded.pinned,
                referenced_at=excluded.referenced_at,session_id=excluded.session_id,reference_revision=files.reference_revision+1""",
                (identity, path, name or (old["name"] if old else Path(path).name),
                 access or (old["access"] if old else "read"), int(pinned if pinned is not None else old["pinned"] if old else False),
                 time.time(), session))
        return {"version": 1, "file": self.entry(identity), "status": "referenced"}

    def snapshot(self, identity, accessed=True):
        row = self.entry(identity)
        with opened(row["path"]) as (_, fd, _, info): text = read_text(fd, info)
        result = {"version": 1, "id": identity, "text": text, "size": len(text.encode()),
                  "modified_ns": info.st_mtime_ns, "access": row["access"], "tasks": tasks(text)}
        result["etag"] = '"' + hashlib.sha256(json.dumps(result, sort_keys=True, ensure_ascii=False).encode()).hexdigest() + '"'
        if accessed:
            with self.db() as db: db.execute("UPDATE files SET accessed_at=? WHERE id=?", (time.time(), identity))
        return result

    def pin(self, identity, pinned):
        self.entry(identity)
        if type(pinned) is not bool: raise ArtifactError("Pinned must be a boolean.")
        with self.lock(), self.db() as db: db.execute("UPDATE files SET pinned=? WHERE id=?", (int(pinned), identity))
        return self.entry(identity)

    def edit(self, identity, body):
        if not isinstance(body, dict) or not re.fullmatch(r"[a-f0-9]{32}", str(body.get("operation_id", ""))):
            raise ArtifactError("A stable operation_id is required.")
        if set(body) - {"operation_id", "base_etag", "action", "line", "checked", "text", "after_line"}:
            raise ArtifactError("Unknown list-edit fields.")
        request_hash = digest(json.dumps([identity, body], sort_keys=True))
        with self.lock():
            row = self.entry(identity)
            if row["access"] != "checklist": raise ArtifactError("This document is read-only.", 403)
            snapshot = self.snapshot(identity, accessed=False)
            with self.db() as db: prior = db.execute("SELECT * FROM edits WHERE id=?", (body["operation_id"],)).fetchone()
            if prior:
                if prior["request_hash"] != request_hash: raise ArtifactError("That edit identity belongs to another request.", 409)
                if prior["state"] == "done" or digest(snapshot["text"]) == prior["after_hash"]:
                    with self.db() as db: db.execute("UPDATE edits SET state='done' WHERE id=?", (body["operation_id"],))
                    return snapshot
                if digest(snapshot["text"]) != prior["before_hash"]:
                    raise ArtifactError("The earlier edit has an uncertain outcome. Refresh and review this list.", 409)
            else:
                with self.db() as db:
                    if db.execute("SELECT COUNT(*) FROM edits").fetchone()[0] >= 100000:
                        raise ArtifactError("This profile's list-edit receipt limit has been reached.", 413)
            if body.get("base_etag") != snapshot["etag"]:
                raise ArtifactError("This file changed. Refresh it before editing.", 409)
            lines = snapshot["text"].splitlines(keepends=True)
            if body.get("action") == "check":
                line = body.get("line")
                if type(line) is not int or type(body.get("checked")) is not bool or line not in {x["line"] for x in snapshot["tasks"]}:
                    raise ArtifactError("Choose a current checklist item.")
                match = TASK.match(lines[line])
                lines[line] = match[1] + ("[x]" if body["checked"] else "[ ]") + match[3] + match[4] + (match[5] or "")
            elif body.get("action") == "add":
                value = body.get("text")
                if not isinstance(value, str) or not value.strip() or len(value) > 1000 or any(ord(c) < 32 for c in value):
                    raise ArtifactError("Enter one item, up to 1,000 characters.")
                after = body.get("after_line")
                if after is not None and (type(after) is not int or after not in {x["line"] for x in snapshot["tasks"]}):
                    raise ArtifactError("Choose a current list to add to.")
                at = len(lines) if after is None else after + 1
                newline = "\r\n" if "\r\n" in snapshot["text"] else "\n"
                if at and not lines[at - 1].endswith("\n"): lines[at - 1] += newline
                escaped = re.sub(r"([\\`*{}\[\]<>#!_|~])", r"\\\1", value.strip())
                indent = re.match(r"\s*", lines[after])[0] if after is not None else ""
                lines.insert(at, indent + "- [ ] " + escaped + newline)
            else: raise ArtifactError("Choose check or add.")
            text = "".join(lines)
            data = text.encode("utf-8")
            if len(data) > MAX_TEXT: raise ArtifactError("This document exceeds the text-view limit.", 413)
            with opened(row["path"]) as (parent, fd, name, info):
                current = read_text(fd, info)
                if current != snapshot["text"]: raise ArtifactError("The file changed before this edit. Refresh it.", 409)
                if info.st_uid != os.geteuid():
                    raise ArtifactError("Checklist editing requires the server to own this file.", 403)
                temporary = ".bellpull-edit-" + uuid.uuid4().hex
                output = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, stat.S_IMODE(info.st_mode), dir_fd=parent)
                try:
                    with os.fdopen(output, "wb") as file:
                        os.fchown(file.fileno(), info.st_uid, info.st_gid)
                        os.fchmod(file.fileno(), stat.S_IMODE(info.st_mode) & 0o777)
                        file.write(data); file.flush(); os.fsync(file.fileno())
                    with self.db() as db:
                        db.execute("INSERT OR IGNORE INTO edits VALUES(?,?,?,?,?,'prepared',?)", (body["operation_id"], identity,
                            request_hash, digest(current), digest(text), time.time()))
                    latest = os.stat(name, dir_fd=parent, follow_symlinks=False)
                    if (latest.st_dev, latest.st_ino, latest.st_size, latest.st_mtime_ns, latest.st_ctime_ns) != (
                            info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns):
                        raise ArtifactError("The file changed before saving. Refresh it.", 409)
                    os.replace(temporary, name, src_dir_fd=parent, dst_dir_fd=parent)
                    os.fsync(parent)
                    with self.db() as db: db.execute("UPDATE edits SET state='done' WHERE id=?", (body["operation_id"],))
                finally:
                    try: os.unlink(temporary, dir_fd=parent)
                    except FileNotFoundError: pass
            return self.snapshot(identity)
