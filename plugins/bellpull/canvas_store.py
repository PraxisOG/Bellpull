"""Authoritative, profile/session-scoped canvas state and committed event journal."""
from contextlib import contextmanager
from copy import deepcopy
import hashlib
import json
import math
from pathlib import Path
import re
import sqlite3
import time

from .artifacts import ArtifactError, checked_session
from .lineage import compression_ids


class CanvasError(ArtifactError):
    def __init__(self, message, status=400):
        super().__init__(message, status)


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,80}", value) or value in {"__proto__", "prototype", "constructor"}:
        raise CanvasError("Invalid canvas identifier.")
    return value


def integer(value):
    if type(value) is not int or not 0 <= value <= 9007199254740991:
        raise CanvasError("Invalid canvas revision or cursor.")
    return value


def text(value, limit):
    if not isinstance(value, str) or len(value) > limit:
        raise CanvasError("Canvas text is missing or too long.")
    return value


def geometry(value, low, high):
    if type(value) not in (int, float) or not math.isfinite(value) or not low <= value <= high:
        raise CanvasError("Canvas geometry is outside its limits.")
    return value


def node_fields(value, partial=False):
    if not isinstance(value, dict):
        raise CanvasError("Expected node properties.")
    allowed = {"shape", "label", "x", "y", "width", "height", "color"}
    if set(value) - allowed - (set() if partial else {"id"}) or (not partial and set(value) != allowed | {"id"}):
        raise CanvasError("Unsupported or missing node properties.")
    row = deepcopy(value)
    if not partial: identifier(row["id"])
    if "shape" in row and row["shape"] not in ("rectangle", "ellipse", "note", "text"):
        raise CanvasError("Unsupported canvas shape.")
    if "color" in row and row["color"] not in ("sand", "rose", "sage", "blue", "white"):
        raise CanvasError("Unsupported canvas color.")
    if "label" in row: text(row["label"], 500)
    for key in ("x", "y"):
        if key in row: geometry(row[key], -20000, 20000)
    for key in ("width", "height"):
        if key in row: geometry(row[key], 40, 2000)
    return row


def content(value):
    if not isinstance(value, dict) or set(value) != {"name", "nodes", "edges"}:
        raise CanvasError("Expected a canvas scene.")
    name = text(value["name"], 200).strip() or "Untitled canvas"
    nodes, edges = value["nodes"], value["edges"]
    if not isinstance(nodes, list) or not isinstance(edges, list) or len(nodes) > 200 or len(edges) > 400:
        raise CanvasError("Canvas supports 200 objects and 400 connectors.")
    nodes = [node_fields(n) for n in nodes]
    ids = {n["id"] for n in nodes}
    if len(ids) != len(nodes): raise CanvasError("Duplicate object identity.")
    node_ids, pairs = set(ids), set()
    for edge in edges:
        if not isinstance(edge, dict) or set(edge) != {"id", "from", "to", "label", "arrow"}:
            raise CanvasError("Invalid connector fields.")
        identifier(edge["id"]); text(edge["label"], 200)
        if type(edge["arrow"]) is not bool: raise CanvasError("Invalid connector arrow.")
        pair = (identifier(edge["from"]), identifier(edge["to"]))
        if edge["id"] in ids or pair[0] not in node_ids or pair[1] not in node_ids or pair[0] == pair[1] or pair in pairs:
            raise CanvasError("Connector endpoints are missing or duplicated.")
        ids.add(edge["id"]); pairs.add(pair)
    return {"name": name, "nodes": nodes, "edges": deepcopy(edges)}


def apply(scene, operation):
    if not isinstance(operation, dict): raise CanvasError("Expected an operation object.")
    row = deepcopy(scene)
    kind = operation.get("type")
    fields = {"add-node": {"node"}, "update-node": {"id", "changes"}, "remove-nodes": {"ids"},
              "add-edge": {"edge"}, "update-edge": {"id", "label", "arrow"}, "remove-edge": {"id"}, "rename": {"name"}}
    if not isinstance(kind, str) or kind not in fields or set(operation) != fields[kind] | {"type"}:
        raise CanvasError("Unsupported canvas operation or fields.")
    if kind == "add-node": row["nodes"].append(node_fields(operation["node"]))
    elif kind == "add-edge": row["edges"].append(operation["edge"])
    elif kind == "rename": row["name"] = operation["name"]
    elif kind == "remove-nodes":
        ids = operation["ids"]
        if not isinstance(ids, list) or not 1 <= len(ids) <= 200: raise CanvasError("Select objects to remove.")
        ids = set(identifier(i) for i in ids)
        if not ids <= {n["id"] for n in row["nodes"]}: raise CanvasError("An object no longer exists.", 409)
        row["nodes"] = [n for n in row["nodes"] if n["id"] not in ids]
        row["edges"] = [e for e in row["edges"] if e["from"] not in ids and e["to"] not in ids]
    else:
        key = "nodes" if kind == "update-node" else "edges"
        target = next((n for n in row[key] if n["id"] == identifier(operation["id"])), None)
        if target is None: raise CanvasError("The object no longer exists.", 409)
        if kind == "remove-edge": row[key].remove(target)
        elif kind == "update-node": target.update(node_fields(operation["changes"], partial=True))
        else: target.update(label=operation["label"], arrow=operation["arrow"])
    return content(row)


def frame(doc):
    return {k: deepcopy(doc[k]) for k in ("content", "revision", "updatedAt", "summary")}


def view(doc):
    return {"id": doc["id"], "name": doc["content"]["name"], "revision": doc["revision"], "updatedAt": doc["updatedAt"],
            "content": doc["content"], "canUndo": bool(doc["undo"]), "canRedo": bool(doc["redo"]),
            "history": [{"revision": f["revision"], "at": f["updatedAt"], "summary": f["summary"]} for f in [*doc["undo"], frame(doc)]][::-1]}


class CanvasStore:
    def __init__(self, home, scope, database):
        if not re.fullmatch(r"[a-f0-9]{64}", scope): raise CanvasError("Invalid profile scope.")
        self.scope, self.database = scope, database
        self.root = Path(home) / "bellpull-canvas" / scope
        if self.root.is_symlink() or self.root.parent.is_symlink(): raise CanvasError("Unsafe canvas storage.")
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.path = self.root / "canvas.sqlite"
        if self.path.is_symlink(): raise CanvasError("Unsafe canvas database.")
        with self.db() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS documents (id TEXT PRIMARY KEY, session TEXT NOT NULL, body TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS selections (session TEXT PRIMARY KEY, id TEXT NOT NULL, at INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS receipts (id TEXT PRIMARY KEY, digest TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS events (seq INTEGER PRIMARY KEY AUTOINCREMENT, session TEXT NOT NULL, body TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS blocked (canvas TEXT, turn TEXT, PRIMARY KEY(canvas,turn));
                CREATE INDEX IF NOT EXISTS canvas_sessions ON documents(session);
                CREATE INDEX IF NOT EXISTS canvas_events ON events(session, seq);
            """)
        self.path.chmod(0o600)

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=15)
        db.row_factory = sqlite3.Row
        try:
            with db:
                db.execute("BEGIN IMMEDIATE")
                yield db
        finally: db.close()

    def chain(self, session):
        checked_session(session)
        if not self.database.get_session(session): raise CanvasError("Conversation does not exist in this profile.", 404)
        return compression_ids(self.database, session)

    def _snapshot(self, db, chain):
        marks = ",".join("?" for _ in chain)
        docs = [json.loads(r[0]) for r in db.execute(f"SELECT body FROM documents WHERE session IN ({marks})", chain)]
        docs.sort(key=lambda d: d["updatedAt"], reverse=True)
        selection = db.execute(f"SELECT id FROM selections WHERE session IN ({marks}) ORDER BY at DESC LIMIT 1", chain).fetchone()
        selected = next((d for d in docs if selection and d["id"] == selection[0]), docs[0] if docs else None)
        return {"version": 1, "scope": self.scope, "session_id": chain[0], "session_ids": chain,
                "cursor": db.execute("SELECT COALESCE(MAX(seq),0) FROM events").fetchone()[0],
                "documents": [{k: view(d)[k] for k in ("id", "name", "revision", "updatedAt")} for d in docs],
                "document": view(selected) if selected else None}

    def snapshot(self, session):
        chain = self.chain(session)
        with self.db() as db: return self._snapshot(db, chain)

    def events(self, session, after):
        chain = self.chain(session); integer(after)
        with self.db() as db:
            low, high = db.execute("SELECT COALESCE(MIN(seq),0),COALESCE(MAX(seq),0) FROM events").fetchone()
            if after > high or after < low - 1:
                return [{"event": "canvas.reset", "snapshot": self._snapshot(db, chain)}]
            rows = db.execute("SELECT seq,body FROM events WHERE seq>? AND session IN (" + ",".join("?" for _ in chain) + ") ORDER BY seq LIMIT 200", [after, *chain])
            return [{"event": "canvas.committed", "snapshot": {**json.loads(r[1]), "cursor": r[0]}} for r in rows]

    def mutate(self, session, body, *, turn="", tool_call="", interrupted=lambda: False):
        chain = self.chain(session)
        if not isinstance(body, dict): raise CanvasError("Expected canvas mutation.")
        if len(json.dumps(body)) > 512000: raise CanvasError("Canvas request is too large.", 413)
        action = body.get("action")
        allowed = {"create": {"name", "content"}, "select": set(), "edit": {"operations"},
                   "undo": set(), "redo": set(), "restore": {"target"}}
        if not isinstance(action, str) or action not in allowed or set(body) - allowed[action] - {"action", "canvas_id", "operation_id", "base_revision"}:
            raise CanvasError("Unsupported canvas request fields.")
        id = identifier(body.get("canvas_id")); op = identifier(body.get("operation_id"))
        receipt = hashlib.sha256((chain[0] + ":" + op).encode()).hexdigest()
        digest = hashlib.sha256(json.dumps([body, turn, tool_call], sort_keys=True, allow_nan=False).encode()).hexdigest()
        with self.db() as db:
            prior = db.execute("SELECT digest FROM receipts WHERE id=?", (receipt,)).fetchone()
            if prior:
                if prior[0] != digest: raise CanvasError("Operation identity was reused with different content.", 409)
                return self._snapshot(db, chain)
            if interrupted(): raise CanvasError("Drawing stopped before commit.", 409)
            raw = db.execute("SELECT session,body FROM documents WHERE id=?", (id,)).fetchone()
            if raw and raw[0] not in chain: raise CanvasError("Canvas belongs to another conversation.", 409)
            doc = json.loads(raw[1]) if raw else None
            now = int(time.time() * 1000)
            if action == "create":
                if doc: raise CanvasError("Canvas already exists; refresh before editing.", 409)
                if db.execute("SELECT COUNT(*) FROM documents").fetchone()[0] >= 1000 or len(self._snapshot(db, chain)["documents"]) >= 100:
                    raise CanvasError("Canvas document limit reached.", 413)
                scene = content(body.get("content", {"name": body.get("name", "Untitled canvas"), "nodes": [], "edges": []}))
                doc = {"id": id, "content": scene, "revision": 0, "updatedAt": now, "summary": "Created canvas", "undo": [], "redo": [], "turn": ""}
                db.execute("INSERT INTO documents VALUES (?,?,?)", (id, chain[0], json.dumps(doc)))
            elif not doc: raise CanvasError("Canvas does not exist.", 404)
            elif action != "select":
                if integer(body.get("base_revision")) != doc["revision"]: raise CanvasError("Canvas changed. Refresh and review before editing.", 409)
                if turn and db.execute("SELECT 1 FROM blocked WHERE canvas=? AND turn=?", (id, turn)).fetchone():
                    raise CanvasError("This drawing turn was interrupted by another edit. Start a new turn to continue.", 409)
                if doc.get("turn") and doc["turn"] != turn:
                    db.execute("INSERT OR IGNORE INTO blocked VALUES (?,?)", (id, doc["turn"]))
                previous = frame(doc)
                if action == "edit":
                    ops = body.get("operations")
                    if not isinstance(ops, list) or not 1 <= len(ops) <= 400: raise CanvasError("Use 1..400 drawing operations.")
                    # Validate the whole batch before recording any mutation.
                    scenes = []; scene = doc["content"]
                    for operation in ops:
                        scene = apply(scene, operation); scenes.append(scene)
                    if not turn or doc.get("turn") != turn: doc["undo"] = [*doc["undo"], previous][-20:]
                    doc["redo"] = []
                    for index, scene in enumerate(scenes):
                        if interrupted(): raise CanvasError("Drawing stopped before commit.", 409)
                        doc.update(content=scene, revision=doc["revision"] + 1, updatedAt=now, summary="Hermes drawing" if turn else "Edited canvas", turn=turn)
                        db.execute("UPDATE documents SET body=? WHERE id=?", (json.dumps(doc), id))
                        self._event(db, chain, id, op, turn, tool_call, index)
                else:
                    stack = doc["redo"] if action == "redo" else doc["undo"]
                    target = next((f for f in stack if f["revision"] == integer(body.get("target"))), None) if action == "restore" else (stack[-1] if stack else None)
                    if not target: raise CanvasError("Canvas history is unavailable.", 409)
                    if action == "undo": doc["undo"] = doc["undo"][:-1]; doc["redo"] = [*doc["redo"], previous][-20:]
                    else:
                        doc["undo"] = [*doc["undo"], previous][-20:]
                        doc["redo"] = doc["redo"][:-1] if action == "redo" else []
                    doc.update(content=target["content"], revision=doc["revision"] + 1, updatedAt=now, summary=action.capitalize(), turn="")
                    db.execute("UPDATE documents SET body=? WHERE id=?", (json.dumps(doc), id))
            if action in ("create", "select"):
                db.execute("INSERT OR REPLACE INTO selections VALUES (?,?,?)", (chain[0], id, time.time_ns()))
            if action != "edit": self._event(db, chain, id, op, turn, tool_call, 0)
            db.execute("INSERT INTO receipts VALUES (?,?)", (receipt, digest))
            # Never discard idempotency receipts: retries cannot become fresh writes.
            db.execute("DELETE FROM events WHERE seq <= (SELECT MAX(seq)-1000 FROM events)")
            return self._snapshot(db, chain)

    def _event(self, db, chain, id, operation, turn, tool_call, step):
        snapshot = self._snapshot(db, chain)
        snapshot["change"] = {"canvas_id": id, "operation_id": operation, "turn_id": turn, "tool_call_id": tool_call, "step": step}
        db.execute("INSERT INTO events(session,body) VALUES (?,?)", (chain[0], json.dumps(snapshot)))
