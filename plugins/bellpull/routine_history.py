"""Read native Bot Chat receipts for executions in the current Hermes profile."""
from contextlib import closing
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import sqlite3

from .artifacts import ArtifactError
from .routine_results import BotChatOwners, receipt_status, valid_job

PAGE_SIZE = 50
EXECUTION_ID = re.compile(r"[0-9a-f]{32}")


def available():
    try:
        from cron.executions import list_executions
        from cron.bot_chat_delivery import read_pending
        from tools.bot_live_delivery import read_delivery_result
    except ImportError:
        return False
    return all(callable(reader) for reader in (list_executions, read_pending, read_delivery_result))


def execution_page(home, before):
    cursor = ()
    if before is not None:
        stamp, separator, execution = before.partition("/")
        try:
            if len(stamp) > 48 or not separator or not EXECUTION_ID.fullmatch(execution):
                raise ValueError
            datetime.fromisoformat(stamp)
        except ValueError:
            raise ArtifactError("Invalid Bot Chat history cursor.", 400) from None
        cursor = (stamp, execution)
    ledger = home / "cron" / "executions.db"
    if ledger.parent.is_symlink() or ledger.is_symlink():
        raise ArtifactError("Bot Chat execution history is unavailable.", 503)
    if not ledger.exists():
        return []
    # Hermes' list_executions cursor takes only a timestamp. Use the native
    # (claimed_at, id) ordering so simultaneous claims survive page boundaries.
    predicate = "WHERE (claimed_at, id) < (?, ?)" if cursor else ""
    sql = f"SELECT job_id, id, claimed_at FROM executions {predicate} ORDER BY claimed_at DESC, id DESC LIMIT ?"
    try:
        with closing(sqlite3.connect(ledger.as_uri() + "?mode=ro", uri=True, timeout=1)) as connection:
            return connection.execute(sql, (*cursor, PAGE_SIZE + 1)).fetchall()
    except sqlite3.Error as exc:
        raise ArtifactError("Could not read the native Bot Chat execution history.", 503) from exc


def observed(home, key, job, execution, notice, owners):
    from cron.bot_chat_delivery import read_pending
    from tools.bot_live_delivery import read_delivery_result
    evidence = {}
    live = read_delivery_result(home, key)
    if isinstance(live, dict) and live.get("delivery_id") == key and live.get("profile_home") == str(home):
        status, source = live.get("status"), "owner"
        evidence = owners.evidence(live)
    else:
        pending = read_pending(key)
        if not isinstance(pending, dict) or pending.get("id") != key or pending.get("home") != str(home):
            return None
        producer = pending.get("job")
        if (not isinstance(producer, dict) or producer.get("id") != job or producer.get("execution_id") != execution
                or (pending.get("degraded") is True) != notice):
            return None
        status, source = pending.get("status"), "deferred"
    return dict(id=key, target="bot-chat:(own)", status=receipt_status(status), source=source,
                execution_id=execution, degraded_notice=notice, **evidence)


def delivery_keys(home, job, execution):
    # Native scheduler_delivery._deliver_to_bot_chat derives these two keys.
    payload = json.dumps([str(home), job, execution, str(home)], ensure_ascii=False, separators=(",", ":"))
    primary = hashlib.sha256(payload.encode()).hexdigest()
    return primary, hashlib.sha256((primary + ":degraded").encode()).hexdigest()


def page(before=None):
    from hermes_constants import get_hermes_home
    if not available():
        raise ArtifactError("This Hermes host does not support Bot Chat receipt history.", 501)
    home = Path(get_hermes_home()).resolve()
    rows = execution_page(home, before)
    owners = BotChatOwners(home)
    deliveries = []
    unreadable = 0
    for job, execution, claimed_at in rows[:PAGE_SIZE]:
        if not valid_job(job) or not isinstance(execution, str) or not EXECUTION_ID.fullmatch(execution):
            continue
        key, notice_key = delivery_keys(home, job, execution)
        for delivery_id, notice in ((key, False), (notice_key, True)):
            try:
                receipt = observed(home, delivery_id, job, execution, notice, owners)
            except (OSError, ValueError, TypeError):
                unreadable += 1
                continue
            if receipt is not None:
                deliveries.append(dict(job=job, claimed_at=claimed_at, receipt=receipt))
    next_cursor = None
    if len(rows) > PAGE_SIZE:
        _, execution, stamp = rows[PAGE_SIZE - 1]
        next_cursor = f"{stamp}/{execution}"
    return dict(deliveries=deliveries, next=next_cursor, unreadable=unreadable)
