"""Read retained native cron output within the authenticated profile."""
import codecs
from contextlib import contextmanager
import errno
from functools import cached_property
import hashlib
import heapq
import os
from pathlib import Path
import re
import stat
import time

from .artifacts import ArtifactError


PAGE_SIZE = 50
CHUNK_BYTES = 32 * 1024
STAMP = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}")
DIR_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW


def receipt_status(value):
    known = {"queued", "claimed", "settled", "failed", "ambiguous", "suppressed", "transferred", "cancelled"}
    return value if isinstance(value, str) and value in known else "unknown"


class BotChatOwners:
    """One lazy, profile-scoped observation for a receipt page or routine listing."""

    def __init__(self, home):
        self.home = Path(home).resolve()

    @cached_property
    def snapshot(self):
        try:
            from hermes_cli.active_sessions import active_session_registry_snapshot
            before_read = time.time_ns()
            return before_read, active_session_registry_snapshot(registry_home=self.home, strict=True)
        except (ImportError, OSError, RuntimeError, TypeError, ValueError):
            return None

    def evidence(self, receipt):
        if receipt.get("status") not in ("queued", "claimed"):
            return {}
        owner = receipt.get("owner")
        if not isinstance(owner, dict) or owner.get("profile_home") != str(self.home):
            return {}
        if not all(isinstance(owner.get(k), str) and owner[k] for k in ("session_id", "lease_id", "live_session_id")):
            return {}
        if self.snapshot is None:
            return {}
        sampled_at, leases = self.snapshot
        admitted_at = receipt.get("claimed_at") or receipt.get("created_at")
        # Later rows on the page can have changed since the registry was sampled.
        if not isinstance(admitted_at, int) or not 0 < admitted_at <= sampled_at:
            return {}
        for entry in leases:
            if entry["lease_id"] == owner["lease_id"]:
                # A live owner can keep the same lease when its chat is compressed.
                metadata = entry.get("metadata") or {}
                if metadata.get("live_session_id") == owner["live_session_id"]:
                    return {"owner_state": "registered"}
                return {}
        if any(entry["session_id"] == owner["session_id"] for entry in leases):
            return {"owner_state": "replaced"}
        return {"owner_state": "absent"}


def bot_chat_receipts(job, owners=None):
    """Project only producer-referenced receipts. Never enter another target profile."""
    queued = job.get("last_delivery_queued")
    if not isinstance(queued, dict):
        return []
    from hermes_constants import get_hermes_home
    owners = owners or BotChatOwners(get_hermes_home())
    records = []
    for target, hint in queued.items():
        if not isinstance(target, str) or not target.startswith("bot-chat:") or not isinstance(hint, dict):
            continue
        key = hint.get("delivery_id")
        if not isinstance(key, str) or not re.fullmatch("[0-9a-f]{32,64}", key):
            continue
        state = hint.get("status")
        source = "scheduler"
        evidence = {}
        try:
            from tools.bot_live_delivery import read_delivery_result
            live = read_delivery_result(get_hermes_home(), key) if target == "bot-chat:(own)" else None
            if live and live.get("delivery_id") == key and live.get("profile_home") == str(owners.home):
                state, source = live.get("status"), "owner"
                evidence = owners.evidence(live)
            else:
                from . import deferred_receipts
                read = deferred_receipts.reader()
                pending = read(owners.home, key) if read else None
                if (pending and pending.get("id") == key and isinstance(pending.get("job"), dict)
                        and pending["job"].get("id") == job["id"]
                        and (target != "bot-chat:(own)" or pending.get("home") == str(owners.home))):
                    state, source = pending.get("status"), "deferred"
        except (ImportError, OSError, ValueError, TypeError):
            pass
        records.append(dict(target=target, id=key, status=receipt_status(state),
                            source=source, **evidence))
    return records


def valid_job(job):
    return isinstance(job, str) and 0 < len(job) <= 255 and job not in {".", ".."} and all(
        c.isprintable() and c not in "/\\" for c in job)


@contextmanager
def directory(name, parent=None):
    fd = os.open(name, DIR_FLAGS, dir_fd=parent)
    try:
        yield fd
    finally:
        os.close(fd)


@contextmanager
def output_root(home):
    with directory(home) as profile, directory("cron", profile) as cron, directory("output", cron) as output:
        yield output


def revision(info):
    # A second native write in the same second can replace a timestamp-named file.
    fields = (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)
    return hashlib.sha256(":".join(map(str, fields)).encode()).hexdigest()


def retained(output, before, job_filter):
    with os.scandir(output) as jobs:
        for job in jobs:
            if job_filter is not None and job.name != job_filter:
                continue
            if not valid_job(job.name) or not job.is_dir(follow_symlinks=False):
                continue
            try:
                with directory(job.name, output) as folder, os.scandir(folder) as entries:
                    for entry in entries:
                        stamp, suffix = os.path.splitext(entry.name)
                        if suffix != ".md" or not STAMP.fullmatch(stamp):
                            continue
                        cursor = stamp + "/" + job.name
                        if before and cursor >= before:
                            continue
                        try:
                            info = entry.stat(follow_symlinks=False)
                        except FileNotFoundError:
                            continue
                        if stat.S_ISREG(info.st_mode) and info.st_nlink == 1:
                            yield cursor, dict(job=job.name, stamp=stamp, revision=revision(info), size=info.st_size)
            except (FileNotFoundError, NotADirectoryError):
                continue  # retention or a concurrent job removal


def list_results(home, before=None, job=None):
    if job is not None and not valid_job(job):
        raise ArtifactError("Invalid routine id.", 400)
    if before is not None:
        stamp, _, cursor_job = before.partition("/")
        if not STAMP.fullmatch(stamp) or not valid_job(cursor_job) or job is not None and job != cursor_job:
            raise ArtifactError("Invalid saved result cursor.", 400)
    try:
        with output_root(home) as root:
            page = heapq.nlargest(PAGE_SIZE + 1, retained(root, before, job), key=lambda row: row[0])
    except FileNotFoundError:
        page = []
    return dict(results=[row[1] for row in page[:PAGE_SIZE]],
                next=page[PAGE_SIZE - 1][0] if len(page) > PAGE_SIZE else None)


def read_result(home, job, stamp, expected, offset):
    if not valid_job(job) or not STAMP.fullmatch(stamp or "") or not re.fullmatch("[a-f0-9]{64}", expected or ""):
        raise ArtifactError("Invalid saved result reference.", 400)
    if not re.fullmatch(r"[0-9]{1,18}", offset):
        raise ArtifactError("Invalid result offset.", 400)
    start = int(offset)
    try:
        with output_root(home) as root, directory(job, root) as folder:
            fd = os.open(stamp + ".md", os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=folder)
            try:
                info = os.fstat(fd)
                if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                    raise ArtifactError("Saved result is unavailable.", 404)
                if revision(info) != expected:
                    raise ArtifactError("Saved result changed. Refresh the list.", 409)
                if start > info.st_size:
                    raise ArtifactError("Result offset exceeds its size.", 400)
                raw = os.pread(fd, CHUNK_BYTES, start)
                if revision(os.fstat(fd)) != expected:
                    raise ArtifactError("Saved result changed. Refresh the list.", 409)
            finally:
                os.close(fd)
    except OSError as exc:
        if exc.errno not in {errno.ENOENT, errno.ENOTDIR, errno.ELOOP}:
            raise
        raise ArtifactError("Saved result is no longer retained.", 404) from None
    decoder = codecs.getincrementaldecoder("utf-8")()
    try:
        content = decoder.decode(raw, final=start + len(raw) == info.st_size)
    except UnicodeDecodeError:
        raise ArtifactError("Saved result has invalid UTF-8 at this offset.", 422) from None
    end = start + len(raw) - len(decoder.getstate()[0])
    return dict(job=job, stamp=stamp, revision=expected, offset=start, text=content,
                next=end if end < info.st_size else None)
