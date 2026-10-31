"""Native Hermes boards through the existing authenticated gateway.

Only non-executing moves are exposed. Receipts and native writes share one SQLite
transaction; an interrupted response can be recovered without another action.
"""
import asyncio
from contextlib import contextmanager
from functools import lru_cache
import hashlib
import inspect
import json
import os
from pathlib import Path
import re
import socket
import sqlite3
import time
import uuid

from aiohttp import web
from .artifacts import ArtifactError
from .principal import artifact_scope

COLUMNS = ("triage", "todo", "scheduled", "ready", "running", "blocked", "review", "done", "archived")
LIMIT = 500
EMPTY_REVISION = "0" * 64
EDITABLE_STATUSES = ("triage", "todo", "scheduled", "ready", "blocked")


def native():
    from hermes_cli import kanban_db
    return kanban_db


def database_api():
    from hermes_cli import kanban_db_connect
    return kanban_db_connect


def supported():
    try:
        kb = native()
        db = database_api()
        return all(callable(getattr(kb, name, None)) for name in (
            "list_boards", "kanban_home", "kanban_db_path", "get_task",
            "add_comment", "_append_event")) and callable(db.connect) and (
            "allow_nested" in inspect.signature(db.write_txn).parameters) and set(COLUMNS) <= kb.VALID_STATUSES
    except (ImportError, AttributeError, ValueError):
        return False


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


@lru_cache(maxsize=1)
def editing_supported():
    try:
        create = native().create_task
        return supported() and "allow_nested=True" in inspect.getsource(create) and all(
            key in inspect.signature(create).parameters for key in ("triage", "board", "idempotency_key", "priority", "assignee"))
    except (ImportError, AttributeError, OSError, TypeError):
        return False


def task_fields(value):
    if not isinstance(value, dict) or set(value) != {"title", "body", "assignee", "priority"}:
        raise ArtifactError("Task fields must include title, body, assignee and priority.", 400)
    if not isinstance(value["title"], str) or not value["title"].strip() or len(value["title"]) > 500:
        raise ArtifactError("A task title requires 1–500 characters.", 400)
    if not isinstance(value["body"], str) or len(value["body"]) > 16384:
        raise ArtifactError("Task descriptions are limited to 16,384 characters.", 400)
    assignee = value["assignee"]
    if assignee is not None and (not isinstance(assignee, str) or not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}", assignee)):
        raise ArtifactError("Assignee must be a lowercase Hermes profile name, or null for unassigned.", 400)
    if type(value["priority"]) is not int or not -(2**31) <= value["priority"] < 2**31:
        raise ArtifactError("Priority must be a 32-bit integer; higher values sort first.", 400)
    return {**value, "title": value["title"].strip()}


def identity(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{32}", value):
        raise ArtifactError("Invalid Kanban operation identity.", 400)
    return value


def slug(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}", value):
        raise ArtifactError("Invalid board.", 400)
    return value


def task_id(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,128}", value):
        raise ArtifactError("Invalid task.", 400)
    return value


def clipped(value, limit=16384):
    return str(value or "")[:limit]


class Boards:
    def __init__(self):
        if not supported():
            raise ArtifactError("Native Hermes Kanban is unavailable on this host.", 503)
        self.kb = native()
        # Native Kanban is installation-wide by design, including named profiles.
        # It does not follow the profile's HERMES_HOME. Never change process env.
        self.owner_id = artifact_scope(self.kb.kanban_home())
        self.owner_name = clipped(os.environ.get("BELLPULL_DEVICE_NAME") or socket.gethostname(), 200)

    def metadata(self, name):
        slug(name)
        entries = self.kb.list_boards(include_archived=True)
        meta = next((row for row in entries if row["slug"] == name), None)
        if meta is None:
            raise ArtifactError("Board no longer exists.", 404)
        return meta

    @contextmanager
    def connection(self, name):
        self.metadata(name)
        path = Path(self.kb.kanban_db_path(board=name))
        # Worker-only overrides alias every slug to the same database. Such a
        # process is not a gateway with independently addressable boards.
        if os.environ.get("HERMES_KANBAN_DB"):
            raise ArtifactError("Kanban gateway cannot use a worker database override.", 503)
        if path.is_symlink():
            raise ArtifactError("Unsafe Kanban database path.", 410)
        conn = database_api().connect(db_path=path)
        try:
            with database_api().write_txn(conn):
                conn.execute("CREATE TABLE IF NOT EXISTS bellpull_kanban_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                conn.execute("INSERT OR IGNORE INTO bellpull_kanban_meta VALUES ('board_id', ?)", (uuid.uuid4().hex,))
                conn.execute("""CREATE TABLE IF NOT EXISTS bellpull_kanban_operations (
                    id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, result TEXT NOT NULL)""")
            yield conn
        finally:
            conn.close()

    def board(self, conn, name):
        meta = self.metadata(name)
        return dict(id=conn.execute("SELECT value FROM bellpull_kanban_meta WHERE key='board_id'").fetchone()[0],
                    slug=name, name=clipped(meta.get("name") or name, 200),
                    description=clipped(meta.get("description"), 2000), archived=bool(meta.get("archived")),
                    owner_id=self.owner_id, owner_name=self.owner_name, scope="installation",
                    can_create=editing_supported() and not meta.get("archived", False))

    def listing(self):
        result = []
        entries = self.kb.list_boards(include_archived=True)
        if len(entries) > 100:
            raise ArtifactError("This host exceeds the 100-board mobile limit.", 413)
        for meta in entries:
            with self.connection(meta["slug"]) as conn:
                result.append(self.board(conn, meta["slug"]))
        return dict(version=1, boards=result, observed_at_ms=int(time.time()*1000))

    def task(self, conn, tid, detail=False):
        row = conn.execute("SELECT * FROM tasks WHERE id=?", (tid,)).fetchone()
        if row is None:
            raise ArtifactError("Task no longer exists.", 404)
        raw = dict(row)
        event = conn.execute("SELECT COALESCE(MAX(id),0) FROM task_events WHERE task_id=?", (tid,)).fetchone()[0]
        # Heartbeats alone should not invalidate a draft comment. Lifecycle and
        # task edits, comments and dependencies do change the revision.
        revision = digest({k: v for k, v in raw.items() if k not in ("last_heartbeat_at", "claim_expires")} | {"event": event})
        active = conn.execute("SELECT 1 FROM task_runs WHERE task_id=? AND ended_at IS NULL LIMIT 1", (tid,)).fetchone()
        movable = not active and not raw.get("claim_lock") and not raw.get("worker_pid")
        editable = movable and raw["status"] in EDITABLE_STATUSES and editing_supported()
        try:
            task_fields(dict(title=raw["title"], body=raw.get("body") or "", assignee=raw.get("assignee"), priority=raw.get("priority", 0)))
        except ArtifactError:
            editable = False  # Never overwrite a field from a truncated/unsupported representation.
        moves = [s for s in ("triage", "blocked") if s != raw["status"]] if movable and raw["status"] in (
            "triage", "todo", "scheduled", "ready", "blocked") else []
        result = dict(id=tid, title=clipped(raw["title"], 500), status=raw["status"],
                      revision=revision, assignee=raw.get("assignee"), project_id=raw.get("project_id"),
                      session_id=raw.get("session_id"), priority=raw.get("priority", 0),
                      current_run_id=raw.get("current_run_id"), allowed_moves=moves, editable=editable,
                      body=clipped(raw.get("body"), 1000), result=clipped(raw.get("result"), 1000))
        if detail:
            result.update(body=clipped(raw.get("body")), result=clipped(raw.get("result")))
            queries = {
                "comments": ("SELECT id,author,body,created_at FROM task_comments WHERE task_id=? ORDER BY id DESC LIMIT 51", 50),
                "runs": ("SELECT id,profile,status,outcome,summary,error,started_at,ended_at FROM task_runs WHERE task_id=? ORDER BY id DESC LIMIT 21", 20),
                "files": ("SELECT id,filename,stored_path AS path,size FROM task_attachments WHERE task_id=? ORDER BY id DESC LIMIT 51", 50),
            }
            truncated = len(raw.get("body") or "") > 16384 or len(raw.get("result") or "") > 16384
            for field, (query, limit) in queries.items():
                records = [dict(r) for r in conn.execute(query, (tid,)).fetchall()]
                truncated |= len(records) > limit
                records = records[:limit]
                for record in records:
                    for key in ("body", "summary", "error"):
                        if record.get(key) is not None:
                            truncated |= len(record[key]) > 16384
                            record[key] = clipped(record[key])
                    if field == "files":
                        record.update(device=self.owner_name, hash=None)
                result[field] = records
            result["parents"] = [r[0] for r in conn.execute("SELECT parent_id FROM task_links WHERE child_id=? LIMIT 101", (tid,))]
            result["children"] = [r[0] for r in conn.execute("SELECT child_id FROM task_links WHERE parent_id=? LIMIT 101", (tid,))]
            truncated |= len(result["parents"]) > 100 or len(result["children"]) > 100
            result["parents"] = result["parents"][:100]
            result["children"] = result["children"][:100]
            result["details_complete"] = not truncated
        return result

    def snapshot(self, name):
        with self.connection(name) as conn:
            conn.execute("BEGIN")
            board = self.board(conn, name)
            ids = [r[0] for r in conn.execute("SELECT id FROM tasks ORDER BY created_at DESC,id DESC LIMIT ?", (LIMIT,))]
            total = conn.execute("SELECT COUNT(*) FROM tasks").fetchone()[0]
            return dict(version=1, board=board, columns=list(COLUMNS), tasks=[self.task(conn, tid) for tid in ids],
                        total=total, complete=total <= LIMIT, observed_at_ms=int(time.time()*1000))

    def detail(self, name, tid):
        with self.connection(name) as conn:
            conn.execute("BEGIN")
            return dict(version=1, board=self.board(conn, name), task=self.task(conn, task_id(tid), True),
                        observed_at_ms=int(time.time()*1000))

    def receipt(self, name, operation):
        with self.connection(name) as conn:
            row = conn.execute("SELECT result FROM bellpull_kanban_operations WHERE id=?", (identity(operation),)).fetchone()
            return json.loads(row[0]) if row else dict(version=1, board=self.board(conn, name), operation_id=operation, state="not_found")

    def mutate(self, name, body):
        if not isinstance(body, dict):
            raise ArtifactError("Expected a Kanban operation object.", 400)
        operation = identity(body.get("operation_id"))
        tid = task_id(body.get("task_id"))
        action = body.get("action")
        if set(body) - {"operation_id", "task_id", "action", "owner_id", "board_id", "expected_revision", "status", "comment", "fields"}:
            raise ArtifactError("Unknown Kanban operation fields.", 400)
        if action not in ("move", "comment", "create", "edit"):
            raise ArtifactError("Unsupported Kanban action.", 400)
        fields = task_fields(body.get("fields")) if action in ("create", "edit") else None
        if action == "comment" and (not isinstance(body.get("comment"), str) or not body["comment"].strip() or len(body["comment"]) > 8000):
            raise ArtifactError("Comments require 1–8000 characters.", 400)
        if action == "create" and (not re.fullmatch(r"draft_[a-f0-9]{32}", tid) or body.get("expected_revision") != EMPTY_REVISION):
            raise ArtifactError("Creation requires a local draft identity and an empty revision.", 400)
        if fields is not None and (body.get("status") is not None or body.get("comment") is not None):
            raise ArtifactError("Task field operations cannot include a move or comment.", 400)
        if fields is None and body.get("fields") is not None:
            raise ArtifactError("Task fields require a create/edit action.", 400)
        fingerprint = digest(body)
        with self.connection(name) as conn, database_api().write_txn(conn):
            board = self.board(conn, name)
            if body.get("board_id") != board["id"] or body.get("owner_id") != self.owner_id:
                raise ArtifactError("Board owner or identity changed. Review the pending edit.", 409)
            previous = conn.execute("SELECT fingerprint,result FROM bellpull_kanban_operations WHERE id=?", (operation,)).fetchone()
            if previous:
                if previous[0] != fingerprint:
                    raise ArtifactError("Operation identity was reused with different content.", 409)
                return json.loads(previous[1])
            if conn.execute("SELECT COUNT(*) FROM bellpull_kanban_operations").fetchone()[0] >= 20000:
                raise ArtifactError("Kanban operation receipt capacity reached; existing receipts remain available.", 409)
            current = None if action == "create" else self.task(conn, tid)
            error = None
            if board["archived"]:
                error = "The board is archived."
            elif fields is not None and not editing_supported():
                error = "This host cannot safely create or edit native tasks."
            elif current is not None and body.get("expected_revision") != current["revision"]:
                error = "Task changed on the server. Review the current task before applying this edit."
            elif action == "move" and body.get("status") not in current["allowed_moves"]:
                error = "This move is unavailable. Active work must be managed by its executor."
            elif action == "edit" and not current["editable"]:
                error = "Task fields cannot be edited while work is active, completed, or outside mobile limits."
            if error is None:
                if action == "create":
                    try:
                        with database_api().write_txn(conn, allow_nested=True):
                            created = self.kb.create_task(conn, **fields, triage=True,
                                board=name, created_by="Bellpull", idempotency_key="bellpull:" + operation)
                            if self.kb.get_task(conn, created).status != "triage":
                                raise ValueError("Native creation did not preserve triage; the task was rolled back.")
                            # Native creation may silently drop an unresolved project link.
                            # A phone request must preserve the board's project or roll back.
                            project = self.metadata(name).get("project_id")
                            if project and self.kb.get_task(conn, created).project_id != project:
                                raise ValueError("This gateway cannot resolve the board's owning project. Create from its owner.")
                        tid = created
                    except ValueError as exc:
                        error = str(exc)
                elif action == "comment":
                    self.kb.add_comment(conn, tid, "Bellpull", body["comment"])
                elif action == "edit":
                    conn.execute("UPDATE tasks SET title=?,body=?,priority=? WHERE id=?",
                                 (fields["title"], fields["body"], fields["priority"], tid))
                    # Match native reassignment's failure bookkeeping without invoking
                    # its independent commit/hook from inside the receipt transaction.
                    if fields["assignee"] != current["assignee"]:
                        conn.execute("UPDATE tasks SET assignee=?,consecutive_failures=0,last_failure_error=NULL WHERE id=?",
                                     (fields["assignee"], tid))
                        self.kb._append_event(conn, tid, "assigned", {"assignee": fields["assignee"]})
                    self.kb._append_event(conn, tid, "updated", {"fields": list(fields), "source": "bellpull", "operation_id": operation})
                else:
                    conn.execute("UPDATE tasks SET status=?, claim_lock=NULL, claim_expires=NULL, worker_pid=NULL WHERE id=?",
                                 (body["status"], tid))
                    self.kb._append_event(conn, tid, "status", {"status": body["status"], "previous_status": current["status"],
                                                               "source": "bellpull", "operation_id": operation})
            result = dict(version=1, board=board, operation_id=operation,
                          state="conflict" if error else "applied", message=error,
                          task=None if action == "create" and error else self.task(conn, tid, True), observed_at_ms=int(time.time()*1000))
            conn.execute("INSERT INTO bellpull_kanban_operations VALUES (?,?,?)", (operation, fingerprint, json.dumps(result)))
            return result


def routes(adapter):
    async def listing(request):
        return web.json_response(await asyncio.to_thread(lambda: Boards().listing()), headers={"Cache-Control": "no-store"})
    async def board(request):
        name = slug(request.match_info["board"])
        if request.method == "POST":
            try:
                body = await request.json()
            except (ValueError, UnicodeError):
                raise ArtifactError("Invalid Kanban JSON.", 400)
            if len(json.dumps(body, ensure_ascii=False).encode()) > 80000:
                raise ArtifactError("Kanban operation too large.", 413)
            result = await asyncio.to_thread(lambda: Boards().mutate(name, body))
        else:
            result = await asyncio.to_thread(lambda: Boards().snapshot(name))
        return web.json_response(result, headers={"Cache-Control": "no-store"})
    async def detail(request):
        return web.json_response(await asyncio.to_thread(lambda: Boards().detail(request.match_info["board"], request.match_info["task"])), headers={"Cache-Control": "no-store"})
    async def receipt(request):
        return web.json_response(await asyncio.to_thread(lambda: Boards().receipt(request.match_info["board"], request.match_info["operation"])), headers={"Cache-Control": "no-store"})
    return [("GET", "/api/bellpull/kanban/boards", listing),
            ("GET", "/api/bellpull/kanban/boards/{board}", board),
            ("POST", "/api/bellpull/kanban/boards/{board}", board),
            ("GET", "/api/bellpull/kanban/boards/{board}/tasks/{task}", detail),
            ("GET", "/api/bellpull/kanban/boards/{board}/operations/{operation}", receipt)]
