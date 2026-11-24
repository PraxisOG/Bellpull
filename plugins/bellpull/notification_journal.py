"""Persistent dispatch receipts for Hermes run-status notifications."""

from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import sqlite3
import time
import uuid


class NotificationJournal:
    def __init__(self, home):
        folder = Path(home) / "bellpull-notifications"
        if folder.is_symlink():
            raise ValueError("Invalid notification journal directory")
        folder.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.path = folder / "dispatch.sqlite"
        if self.path.is_symlink() or (self.path.exists() and self.path.stat().st_nlink != 1):
            raise ValueError("Invalid notification journal file")
        with self.db() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS clock (singleton INTEGER PRIMARY KEY, revision INTEGER NOT NULL);
                INSERT OR IGNORE INTO clock VALUES (1,0);
                CREATE TABLE IF NOT EXISTS events (
                    event_id TEXT PRIMARY KEY, topic TEXT NOT NULL, run_id TEXT NOT NULL,
                    session_id TEXT NOT NULL, status TEXT NOT NULL, approval TEXT NOT NULL,
                    occurred REAL NOT NULL, stored REAL NOT NULL, revision INTEGER NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0, due REAL NOT NULL, lease TEXT,
                    accepted REAL, last_http INTEGER
                );
                CREATE INDEX IF NOT EXISTS event_cursor ON events(topic,revision);
                CREATE INDEX IF NOT EXISTS dispatch_due ON events(due) WHERE accepted IS NULL;
            """)

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=3)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    @staticmethod
    def revision(db):
        db.execute("UPDATE clock SET revision=revision+1 WHERE singleton=1")
        return db.execute("SELECT revision FROM clock WHERE singleton=1").fetchone()[0]

    def record(self, topic, run, session, status, approval, occurred, now=None):
        now = time.time() if now is None else now
        identity = json.dumps(["hermes.run", topic, run, status, approval], separators=(",", ":"))
        event_id = hashlib.sha256(identity.encode()).hexdigest()
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            if not db.execute("SELECT 1 FROM events WHERE event_id=?", (event_id,)).fetchone():
                db.execute(
                    """INSERT INTO events
                    (event_id,topic,run_id,session_id,status,approval,occurred,stored,revision,due)
                    VALUES (?,?,?,?,?,?,?,?,?,?)""",
                    (
                        event_id,
                        topic,
                        run,
                        session,
                        status,
                        approval,
                        occurred if occurred is not None else now,
                        now,
                        self.revision(db),
                        now,
                    ),
                )
        return event_id

    def claim(self, now=None):
        now = time.time() if now is None else now
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            event = db.execute(
                """SELECT event_id,topic,attempts FROM events WHERE accepted IS NULL
                AND due<=? ORDER BY due,revision LIMIT 1""",
                (now,),
            ).fetchone()
            if event is None:
                return None
            token = uuid.uuid4().hex
            db.execute(
                """UPDATE events SET lease=?,due=?,attempts=attempts+1,revision=?
                WHERE event_id=?""",
                (token, now + 30, self.revision(db), event["event_id"]),
            )
            return dict(
                event_id=event["event_id"], topic=event["topic"], token=token, attempts=event["attempts"] + 1
            )

    def finish(self, claim, http_status, now=None):
        now = time.time() if now is None else now
        accepted = http_status is not None and 200 <= http_status < 300
        delay = min(300, 2 ** min(claim["attempts"] - 1, 9))
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            params = (claim["event_id"], claim["token"])
            if not db.execute("SELECT 1 FROM events WHERE event_id=? AND lease=?", params).fetchone():
                return
            db.execute(
                """UPDATE events SET accepted=?,last_http=?,lease=NULL,due=?,revision=?
                WHERE event_id=? AND lease=?""",
                (now if accepted else None, http_status, now + delay, self.revision(db), *params),
            )

    def next_delay(self):
        with self.db() as db:
            due = db.execute("SELECT MIN(due) FROM events WHERE accepted IS NULL").fetchone()[0]
        return 15 if due is None else max(0.05, min(15, due - time.time()))

    @staticmethod
    def public(row):
        severity = {"failed": "error", "interrupted": "warning", "waiting_for_approval": "attention"}.get(
            row["status"], "info"
        )
        return dict(
            event_id=row["event_id"],
            revision=row["revision"],
            source="hermes.run",
            severity=severity,
            run_id=row["run_id"],
            session_id=row["session_id"],
            status=row["status"],
            approval_request_id=row["approval"] or None,
            occurred_at=row["occurred"],
            stored_at=row["stored"],
            attempts=row["attempts"],
            accepted_at=row["accepted"],
            last_http_status=row["last_http"],
            dispatch="transport_accepted" if row["accepted"] is not None else "pending",
        )

    def lookup(self, topic, event_id):
        with self.db() as db:
            row = db.execute(
                "SELECT * FROM events WHERE topic=? AND event_id=?", (topic, event_id)
            ).fetchone()
            return self.public(row) if row else None

    def history(self, topic, after, limit):
        with self.db() as db:
            rows = db.execute(
                "SELECT * FROM events WHERE topic=? AND revision>? ORDER BY revision LIMIT ?",
                (topic, after, limit + 1),
            ).fetchall()
        page = [self.public(row) for row in rows[:limit]]
        return dict(
            events=page, next_cursor=page[-1]["revision"] if page else after, has_more=len(rows) > limit
        )
