"""Profile-owned conversation folders. No model calls or native schema changes."""
import asyncio
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import time

from aiohttp import web
from .artifacts import ArtifactError, checked_session
from .lineage import compression_ids

ICONS = {"folder", "code", "home", "craft", "book", "star"}


class ProjectError(ArtifactError):
    def __init__(self, message, status=400, code="project_rejected"):
        super().__init__(message, status)
        self.code = code


def identity(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{32}", value):
        raise ProjectError("Invalid project or operation identity.")
    return value


def fields(body):
    name, description = body.get("name"), body.get("description", "")
    color, icon = body.get("color"), body.get("icon")
    if color == "": color = None
    if icon == "": icon = None
    if not isinstance(name, str) or not name.strip() or len(name.strip()) > 120 or any(ord(c) < 32 for c in name):
        raise ProjectError("Use a project name between 1 and 120 characters.")
    if not isinstance(description, str) or len(description) > 1000 or any(ord(c) < 32 and c not in "\n\t" for c in description):
        raise ProjectError("Use a description of at most 1000 characters.")
    if color is not None and (not isinstance(color, str) or not re.fullmatch(r"#[a-fA-F0-9]{6}", color)):
        raise ProjectError("Invalid project color.")
    if icon is not None and (not isinstance(icon, str) or icon not in ICONS):
        raise ProjectError("Invalid project icon.")
    return name.strip(), description.strip(), color.upper() if color else None, icon


def fingerprint(body):
    return hashlib.sha256(json.dumps(body, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


class ProjectStore:
    def __init__(self, home, scope, database):
        if not re.fullmatch(r"[a-f0-9]{64}", scope):
            raise ProjectError("Invalid project scope.")
        self.database = database
        self.root = Path(home) / "bellpull-projects" / scope
        if self.root.is_symlink() or self.root.parent.is_symlink():
            raise ProjectError("Unsafe project storage.", 410)
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.path = self.root / "metadata.sqlite"
        if self.path.is_symlink():
            raise ProjectError("Unsafe project database.", 410)
        with self.db() as conn:
            conn.executescript("""
                CREATE TABLE IF NOT EXISTS projects (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL,
                    color TEXT, icon TEXT, archived INTEGER NOT NULL DEFAULT 0,
                    created_at REAL NOT NULL, updated_at REAL NOT NULL);
                CREATE UNIQUE INDEX IF NOT EXISTS project_names ON projects(lower(name));
                CREATE TABLE IF NOT EXISTS memberships (
                    session_id TEXT PRIMARY KEY, project_id TEXT REFERENCES projects(id) ON DELETE SET NULL,
                    pinned INTEGER NOT NULL DEFAULT 0, updated_at REAL NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS state (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL);
                INSERT OR IGNORE INTO state VALUES (1, 0);
                CREATE TABLE IF NOT EXISTS receipts (id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS creations (
                    session_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, created_at REAL NOT NULL);
            """)
            if "updated_at" not in {row[1] for row in conn.execute("PRAGMA table_info(memberships)")}:
                conn.execute("ALTER TABLE memberships ADD COLUMN updated_at REAL NOT NULL DEFAULT 0")
        self.path.chmod(0o600)

    @contextmanager
    def db(self):
        conn = sqlite3.connect(self.path, timeout=15)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys = ON")
        try:
            with conn:
                conn.execute("BEGIN IMMEDIATE")
                yield conn
        finally:
            conn.close()

    def canonical(self, session_id):
        checked_session(session_id)
        if not self.database.get_session(session_id):
            raise ProjectError("Conversation no longer exists in this profile.", 404)
        return compression_ids(self.database, session_id)[0]

    @staticmethod
    def bump(conn):
        conn.execute("UPDATE state SET revision=revision+1 WHERE id=1")

    @staticmethod
    def expected(conn, revision):
        if isinstance(revision, bool) or not isinstance(revision, int) or revision < 0:
            raise ProjectError("A valid project revision is required.")
        if revision != conn.execute("SELECT revision FROM state WHERE id=1").fetchone()[0]:
            raise ProjectError("Projects changed on another client. Refresh and try again.", 409, "project_revision_conflict")

    @staticmethod
    def project(conn, project_id, *, writable=False):
        row = conn.execute("SELECT * FROM projects WHERE id=?", (identity(project_id),)).fetchone()
        if not row:
            raise ProjectError("Project no longer exists.", 404)
        if writable and row["archived"]:
            raise ProjectError("Restore this archived project before adding conversations.", 409)
        return row

    def normalize(self):
        # A continuation can appear before its parent is marked compressed. If an
        # explicit move landed in that window, retain the latest decision when the
        # two native IDs become one logical conversation, including explicit Unfiled.
        with self.db() as conn:
            rows = conn.execute("SELECT * FROM memberships LIMIT 10001").fetchall()
            if len(rows) > 10000:
                raise ProjectError("Too many memberships to reconcile safely.", 413)
            groups = {}
            for row in rows:
                if self.database.get_session(row["session_id"]):
                    key = compression_ids(self.database, row["session_id"])[0]
                    groups.setdefault(key, []).append(row)
            changed = False
            for key, group in groups.items():
                if len(group) == 1 and group[0]["session_id"] == key:
                    continue
                winner = max(group, key=lambda row: (row["updated_at"], row["session_id"]))
                for row in group:
                    conn.execute("DELETE FROM memberships WHERE session_id=?", (row["session_id"],))
                conn.execute("INSERT INTO memberships VALUES (?, ?, ?, ?)", (key, winner["project_id"], winner["pinned"], winner["updated_at"]))
                changed = True
            if changed:
                self.bump(conn)

    def inherit(self, source_id, branch_id, *, normalize=True):
        if normalize:
            self.normalize()
        source, branch = self.canonical(source_id), self.canonical(branch_id)
        if source == branch:
            return
        with self.db() as conn:
            old = conn.execute("SELECT project_id FROM memberships WHERE session_id=?", (source,)).fetchone()
            added = conn.execute("INSERT OR IGNORE INTO memberships VALUES (?, ?, 0, ?)", (branch, old[0] if old else None, time.time())).rowcount
            if added:
                self.bump(conn)

    def reconcile_branches(self):
        # Repair a crash after native fork commit, before membership inheritance.
        # An explicit Unfiled row counts as already handled and is never overwritten.
        rows = self.database._read_all("SELECT * FROM sessions WHERE parent_session_id IS NOT NULL ORDER BY started_at, id LIMIT 10001")
        if len(rows) > 10000:
            raise ProjectError("Too many branch records to reconcile safely.", 413)
        with self.db() as conn:
            known = {row[0] for row in conn.execute("SELECT session_id FROM memberships")}
        for raw in rows:
            row = dict(raw)
            if row["id"] in known:
                continue
            config = row.get("model_config") or {}
            if isinstance(config, str):
                try:
                    config = json.loads(config)
                except ValueError:
                    continue
            parent = row.get("parent_session_id")
            if (isinstance(config, dict) and config.get("_branched_from") == parent and
                    row.get("source") != "tool" and
                    not any(config.get(key) == parent for key in ("_delegate_from", "_reset_from")) and
                    self.database.get_session(parent)):
                self.inherit(parent, row["id"], normalize=False)
                known.add(row["id"])

    def snapshot(self):
        self.normalize()
        self.reconcile_branches()
        with self.db() as conn:
            projects = [dict(row) for row in conn.execute("SELECT * FROM projects ORDER BY lower(name), id")]
            members = [dict(row) for row in conn.execute("SELECT * FROM memberships ORDER BY session_id")]
            revision = conn.execute("SELECT revision FROM state WHERE id=1").fetchone()[0]
        if len(members) > 10000:
            raise ProjectError("Too many memberships for a complete project snapshot.", 413)
        for row in projects:
            row["archived"] = bool(row["archived"])
        result = []
        for row in members:
            if not self.database.get_session(row["session_id"]):
                continue  # Pending creation intents or removed native sessions.
            row["session_ids"] = compression_ids(self.database, row["session_id"])
            row["pinned"] = bool(row["pinned"])
            row.pop("updated_at", None)
            result.append(row)
        return {"version": 1, "revision": revision, "projects": projects, "memberships": result}

    def mutate(self, body):
        self.normalize()
        op = body.get("operation")
        allowed = {"create": {"project_id", "name", "description", "color", "icon"},
                   "update": {"project_id", "name", "description", "color", "icon", "archived"},
                   "delete": {"project_id"}, "move": {"session_id", "project_id"}, "pin": {"session_id", "pinned"}}
        if not isinstance(op, str) or op not in allowed or set(body) - allowed[op] - {"operation", "operation_id", "expected_revision"}:
            raise ProjectError("Unsupported project mutation.")
        operation_id = identity(body.get("operation_id"))
        digest = fingerprint(body)
        session = self.canonical(body.get("session_id")) if op in ("move", "pin") else None
        with self.db() as conn:
            receipt = conn.execute("SELECT fingerprint FROM receipts WHERE id=?", (operation_id,)).fetchone()
            if receipt:
                if receipt[0] != digest:
                    raise ProjectError("This operation identity was already used for another change.", 409)
                return
            self.expected(conn, body.get("expected_revision"))
            if op in ("create", "update"):
                values = fields(body)
                project_id = identity(body.get("project_id"))
                duplicate = conn.execute("SELECT id FROM projects WHERE lower(name)=lower(?) AND id<>?", (values[0], project_id)).fetchone()
                if duplicate:
                    raise ProjectError("A project with this name already exists.", 409)
                now = time.time()
                if op == "create":
                    if conn.execute("SELECT 1 FROM projects WHERE id=?", (project_id,)).fetchone():
                        raise ProjectError("Project identity already exists.", 409)
                    if conn.execute("SELECT count(*) FROM projects").fetchone()[0] >= 500:
                        raise ProjectError("This profile has reached the 500-project limit.", 413)
                    conn.execute("INSERT INTO projects VALUES (?, ?, ?, ?, ?, 0, ?, ?)", (project_id, *values, now, now))
                else:
                    self.project(conn, project_id)
                    if not isinstance(body.get("archived"), bool):
                        raise ProjectError("Archived must be a boolean.")
                    conn.execute("UPDATE projects SET name=?,description=?,color=?,icon=?,archived=?,updated_at=? WHERE id=?",
                                 (*values, body["archived"], now, project_id))
            elif op == "delete":
                self.project(conn, body.get("project_id"))
                conn.execute("DELETE FROM projects WHERE id=?", (body["project_id"],))
            elif op == "move":
                project_id = body.get("project_id")
                if project_id is not None:
                    self.project(conn, project_id, writable=True)
                conn.execute("INSERT INTO memberships VALUES (?, ?, 0, ?) ON CONFLICT(session_id) DO UPDATE SET project_id=excluded.project_id, updated_at=excluded.updated_at", (session, project_id, time.time()))
            elif op == "pin":
                if not isinstance(body.get("pinned"), bool):
                    raise ProjectError("Pinned must be a boolean.")
                conn.execute("INSERT INTO memberships VALUES (?, NULL, ?, ?) ON CONFLICT(session_id) DO UPDATE SET pinned=excluded.pinned, updated_at=excluded.updated_at",
                             (session, body["pinned"], time.time()))
            self.bump(conn)
            conn.execute("INSERT INTO receipts VALUES (?, ?)", (operation_id, digest))
            conn.execute("DELETE FROM receipts WHERE rowid NOT IN (SELECT rowid FROM receipts ORDER BY rowid DESC LIMIT 1024)")

    def reserve_creation(self, project_id, body):
        self.normalize()
        session = checked_session(body.get("id"))
        digest = fingerprint({"project_id": project_id, **body})
        with self.db() as conn:
            previous = conn.execute("SELECT fingerprint FROM creations WHERE session_id=?", (session,)).fetchone()
            if previous:
                if previous[0] != digest:
                    raise ProjectError("Conversation creation identity conflicts with an earlier request.", 409)
                if not self.database.get_session(session):
                    self.project(conn, project_id, writable=True)
                return
            self.expected(conn, body.get("expected_revision"))
            self.project(conn, project_id, writable=True)
            if self.database.get_session(session):
                raise ProjectError("Conversation identity already exists.", 409)
            conn.execute("INSERT INTO memberships VALUES (?, ?, 0, ?)", (session, project_id, time.time()))
            conn.execute("INSERT INTO creations VALUES (?, ?, ?)", (session, digest, time.time()))
            self.bump(conn)


async def store_for(adapter):
    from hermes_constants import get_hermes_home
    from .principal import artifact_scope
    home = Path(get_hermes_home())
    database = await adapter._ensure_session_db_async()
    if database is None:
        raise ProjectError("Hermes session database is unavailable.", 503)
    return await asyncio.to_thread(ProjectStore, home, artifact_scope(home), database)


def routes(adapter):
    async def dispatch(request):
        try:
            store = await store_for(adapter)
            if request.method == "POST":
                body, err = await adapter._read_json_body(request)
                if err is not None:
                    return err
                if not isinstance(body, dict):
                    raise ProjectError("Expected a JSON object.")
                if request.match_info.get("project_id"):
                    allowed = {"id", "expected_revision", "title", "model", "provider", "model_options", "require_model_lock"}
                    if set(body) - allowed:
                        raise ProjectError("Unsupported project chat fields.")
                    await asyncio.to_thread(store.reserve_creation, request.match_info["project_id"], body)
                    existing = await asyncio.to_thread(store.database.get_session, body["id"])
                    if existing:
                        return web.json_response({"session": adapter._session_response(existing)}, headers={"Cache-Control": "no-store"})
                    return await adapter._handle_create_session(request)
                await asyncio.to_thread(store.mutate, body)
            return web.json_response(await asyncio.to_thread(store.snapshot), headers={"Cache-Control": "no-store"})
        except ProjectError as exc:
            return web.json_response({"error": {"message": str(exc), "code": exc.code}}, status=exc.status)
    return [("GET", "/api/bellpull/projects", dispatch), ("POST", "/api/bellpull/projects", dispatch),
            ("POST", "/api/bellpull/projects/{project_id}/sessions", dispatch)]
