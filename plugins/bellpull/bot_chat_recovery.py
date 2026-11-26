"""Operator-reviewed reprocessing of retained same-profile Bot Chat deliveries."""
import hashlib
import json
import re
from io import StringIO
from sqlite3 import Row

from .artifacts import ArtifactError
from . import routine_history, routine_results

FINAL_FOR_REVIEW = {"failed", "ambiguous", "cancelled"}
RECEIPT_ID = re.compile(r"[0-9a-f]{64}")
PAGE = 25


def available():
    try:
        from cron.executions import get_execution
        from cron.scheduler_prompt import _archive_answer, _clip_to_context_budget
        from cron.scheduler_delivery import _redact_cron_payload
        from tools.bot_live_delivery import find_canonical_live_owner, deliver_to_live_owner
    except ImportError:
        return False
    return routine_history.available() and all(callable(f) for f in (
        get_execution, _archive_answer, _clip_to_context_budget, _redact_cron_payload, find_canonical_live_owner, deliver_to_live_owner))


def root_receipt(store, params):
    home = store.home
    if not available():
        raise ArtifactError("This Hermes host does not support reviewed Bot Chat attempts.", 501)
    from cron.executions import get_execution
    job, execution, root = (params.get(k) for k in ("job", "execution_id", "receipt"))
    if not routine_results.valid_job(job) or not isinstance(execution, str) or not routine_history.EXECUTION_ID.fullmatch(execution):
        raise ArtifactError("Invalid routine execution.", 400)
    keys = routine_history.delivery_keys(home, job, execution)
    if root not in keys:
        raise ArtifactError("Receipt does not belong to this execution.", 404)
    original = get_execution(execution)
    if original and original.get("job_id") == job:
        receipt = routine_history.observed(home, root, job, execution, root == keys[1], routine_results.BotChatOwners(home))
        if receipt:
            return job, execution, receipt
    with store.db() as db:
        retained = db.execute("SELECT reviewed_status FROM bot_chat_attempts WHERE parent=? AND job=? AND execution=? AND root=?",
                              (root, job, execution, root)).fetchone()
    if not retained:
        raise ArtifactError("The original delivery receipt is no longer available.", 404)
    return job, execution, dict(id=root, execution_id=execution, target="bot-chat:(own)",
                                source="review", status=retained[0], degraded_notice=root == keys[1])


def native_attempt(home, key):
    from tools.bot_live_delivery import read_delivery_result
    result = read_delivery_result(home, key)
    if result is None:
        return None
    if result.get("profile_home") != str(home) or result.get("delivery_id") != key:
        raise ArtifactError("Bot Chat attempt identity could not be verified.", 409)
    return result


def fingerprint(category, message):
    return hashlib.sha256((category + "\0" + message).encode()).hexdigest()


def attempt_view(home, record, owners):
    actual = native_attempt(home, record["id"])
    evidence = {}
    if actual is None:
        status = "unknown"
        admission = "record_missing" if record["admitted"] else "unconfirmed"
    else:
        if (actual.get("owner") != json.loads(record["owner"]) or actual.get("author")
                or fingerprint(actual.get("notification_category", "result"), actual["message"]) != record["payload_digest"]):
            raise ArtifactError("The existing attempt does not match the saved decision.", 409)
        status = routine_results.receipt_status(actual["status"])
        admission = "recorded"
        evidence = owners.evidence(actual)
    return dict(id=record["id"], parent=record["parent"], root=record["root"], job=record["job"],
                execution_id=record["execution"], reviewed_status=record["reviewed_status"],
                selection=json.loads(record["selection"]), status=status, admission=admission, **evidence)


def recent(store):
    with store.db() as db:
        db.row_factory = Row
        rows = db.execute("SELECT * FROM bot_chat_attempts ORDER BY rowid DESC LIMIT 50").fetchall()
    owners = routine_results.BotChatOwners(store.home)
    return [attempt_view(store.home, row, owners) for row in rows]


def history(store, params):
    _, _, root = root_receipt(store, params)
    before = params.get("before")
    if before is not None and (not isinstance(before, str) or len(before) > 19 or not before.isascii() or not before.isdecimal()
                               or not 0 < int(before) < 2**63):
        raise ArtifactError("Invalid attempt history cursor.", 400)
    with store.db() as db:
        db.row_factory = Row
        rows = db.execute("SELECT rowid, * FROM bot_chat_attempts WHERE root=? AND rowid<? ORDER BY rowid DESC LIMIT ?",
                          (root["id"], int(before) if before else 2**63 - 1, PAGE + 1)).fetchall()
    owners = routine_results.BotChatOwners(store.home)
    return dict(original=root, attempts=[attempt_view(store.home, row, owners) for row in rows[:PAGE]],
                next=str(rows[PAGE - 1]["rowid"]) if len(rows) > PAGE else None)


def selected_answer(home, job, stamp, revision):
    from cron.scheduler_prompt import _archive_answer
    from cron.scheduler_delivery import _redact_cron_payload
    from cron.scheduler_prompt import _clip_to_context_budget
    # Locate the writer's last response heading by byte position. In particular,
    # a large scheduler prompt never enters the redactor or the new Bot Chat turn.
    marker = "\n## Response\n"
    prompt_marker = "\n## Prompt\n"
    native_answer_format = True
    tail = ""
    start = None
    has_prompt = False
    offset = 0
    while offset is not None:
        page = routine_results.read_result(home, job, stamp, revision, str(offset))
        if offset == 0:
            text = page["text"]
            title = text.partition("\n")[0]
            if title.startswith("# Cron Job: ") and title.endswith(" (FAILED)"):
                marker = "\n## Error\n"
                native_answer_format = False
            script = re.match(r"^# Cron Job: [^\n]*\n\n\*\*Job ID:\*\* " + re.escape(job) +
                              r"\n\*\*Run Time:\*\* [^\n]+\n\*\*Mode:\*\* no_agent \(script\)\n", text)
            if script:
                body = text[script.end():]
                if body.startswith("\n---\n\n"):
                    start = len(text[:script.end()].encode()) + len("\n---\n\n")
                elif body.startswith("**Status:** script failed\n"):
                    start = len(text[:script.end()].encode())
                else:
                    raise ArtifactError("This script archive has no saved output to process.", 409)
                native_answer_format = False
                break
        chunk = tail + page["text"]
        index = chunk.rfind(marker)
        if index >= 0:
            start = offset - len(tail.encode()) + len(chunk[:index + len(marker)].encode())
        has_prompt = has_prompt or prompt_marker in chunk
        tail = chunk[-(max(len(marker), len(prompt_marker)) - 1):]
        offset = page["next"]
    if start is None and has_prompt:
        raise ArtifactError("This archive has no saved answer to process.", 409)
    # Redact the entire answer before clipping so patterns spanning pages stay private.
    with StringIO() as retained:
        if start is not None and native_answer_format:
            retained.write("## Response\n")
        offset = start or 0
        while offset is not None:
            page = routine_results.read_result(home, job, stamp, revision, str(offset))
            retained.write(page["text"])
            offset = page["next"]
        raw = retained.getvalue()
        answer = _archive_answer(raw) if native_answer_format else raw.strip()
    if not answer or not answer.strip() or answer.strip() == "(No response generated)":
        raise ArtifactError("This archive has no saved answer to process.", 409)
    return "Summarize this saved routine result for Bot Chat.\n\n" + _clip_to_context_budget(_redact_cron_payload(answer, "reviewed result"))


def source_message(home, job, execution, parent, notice, selection):
    if selection is not None:
        if not notice:
            raise ArtifactError("This delivery already identifies its source result.", 400)
        return selected_answer(home, job, selection["stamp"], selection["revision"]), "result"
    if notice:
        raise ArtifactError("Select the retained routine answer to process.", 409)
    live = native_attempt(home, parent)
    if live:
        return live["message"], live.get("notification_category", "result")
    from cron.bot_chat_delivery import read_pending
    from cron.scheduler_delivery import _redact_cron_payload
    pending = read_pending(parent)
    producer = (pending or {}).get("job")
    if (not pending or pending.get("id") != parent or not isinstance(producer, dict) or
            producer.get("id") != job or producer.get("execution_id") != execution or
            pending.get("home") != str(home) or pending.get("degraded") or
            not isinstance(pending.get("content"), str) or not pending["content"].strip()):
        raise ArtifactError("The original delivery contents are unavailable.", 409)
    return ("Summarize this saved routine result for Bot Chat.\n\n" +
            _redact_cron_payload(pending["content"], "reviewed result"),
            "diagnostic" if pending.get("for_failure") else "result")


def retry(store, params):
    home = store.home
    job, execution, root = root_receipt(store, params)
    from tools.bot_live_delivery import deliver_to_live_owner, find_canonical_live_owner
    parent, reviewed = params.get("parent"), params.get("reviewed_status")
    if (params.get("decision") != "retry_saved_result" or not isinstance(parent, str)
            or not RECEIPT_ID.fullmatch(parent) or not isinstance(reviewed, str) or reviewed not in FINAL_FOR_REVIEW):
        raise ArtifactError("Review the previous terminal outcome before requesting another attempt.", 409)
    selection = params.get("selection")
    if selection is not None and (not isinstance(selection, dict) or set(selection) != {"stamp", "revision"}
            or not isinstance(selection["stamp"], str) or not routine_results.STAMP.fullmatch(selection["stamp"])
            or not isinstance(selection["revision"], str) or not RECEIPT_ID.fullmatch(selection["revision"])):
        raise ArtifactError("Invalid saved result selection.", 400)
    choice = json.dumps(selection, sort_keys=True, separators=(",", ":"))
    rid = hashlib.sha256(("bellpull:reviewed-bot-chat:1:" + parent).encode()).hexdigest()

    owners = routine_results.BotChatOwners(home)
    # Only the writer that creates the intent may submit it. After a crash a
    # missing native receipt cannot distinguish pre-admission from later pruning.
    with store.db() as db:
        db.row_factory = Row
        existing = db.execute("SELECT * FROM bot_chat_attempts WHERE parent=?", (parent,)).fetchone()
        if existing:
            if (existing["root"], existing["reviewed_status"], existing["selection"]) != (root["id"], reviewed, choice):
                raise ArtifactError("This delivery already has a differently reviewed attempt.", 409)
        else:
            notice = parent == root["id"] and root["degraded_notice"]
            if parent == root["id"]:
                status = root["status"] if root["source"] != "review" else None
            else:
                prior = db.execute("SELECT * FROM bot_chat_attempts WHERE root=? AND id=?", (root["id"], parent)).fetchone()
                if prior is None:
                    raise ArtifactError("This attempt does not belong to the original receipt.", 404)
                status = attempt_view(home, prior, owners)["status"]
            if status != reviewed:
                raise ArtifactError("The previous delivery is unresolved or its status changed. Refresh and review it.", 409)
            message, category = source_message(home, job, execution, parent, notice, selection)
            owner = find_canonical_live_owner(home)
            if not owner:
                raise ArtifactError("Open the owning Bot Chat before retrying this result.", 409)
            db.execute("INSERT INTO bot_chat_attempts(parent,id,root,job,execution,reviewed_status,selection,owner,payload_digest) "
                       "VALUES(?,?,?,?,?,?,?,?,?)", (parent, rid, root["id"], job, execution, reviewed, choice,
                                                   json.dumps(owner), fingerprint(category, message)))

    with store.db() as db:
        db.row_factory = Row
        row = db.execute("SELECT * FROM bot_chat_attempts WHERE parent=?", (parent,)).fetchone()
        if existing is None:
            deliver_to_live_owner(home, owner, message, delivery_id=rid, notification_category=category)
        view = attempt_view(home, row, owners)
        if view["admission"] == "recorded" and not row["admitted"]:
            db.execute("UPDATE bot_chat_attempts SET admitted=1 WHERE parent=?", (parent,))
        return view
