"""Explicit offline artifact migration. Legacy namespaces remain for rollback.

Dry-run is the default and never creates a principal or changes a database.
Use --apply --offline only after stopping every writer to this Hermes profile.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import stat
import tempfile
import uuid

from .artifacts import ArtifactStore, ArtifactError, checked_name, checked_session, checked_type
from .principal import artifact_scope


def rows_at(folder):
    database = folder / "metadata.sqlite"
    if database.is_symlink() or not database.is_file():
        raise ValueError("Legacy artifact metadata is missing or unsafe")
    connection = sqlite3.connect(f"file:{database}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    try:
        return [dict(row) for row in connection.execute("SELECT * FROM artifacts")]
    finally:
        connection.close()


def migrate(home, scopes, *, apply=False, offline=False):
    home = Path(home)
    if not scopes or any(not re.fullmatch(r"[a-f0-9]{64}", scope) for scope in scopes):
        raise ValueError("Supply explicit legacy scope hashes from this authorized profile")
    parent = home / "bellpull-artifacts"
    sources = [(parent / scope, rows_at(parent / scope)) for scope in scopes]
    report = {"legacy_scopes": scopes, "records": sum(len(rows) for _, rows in sources), "applied": False}
    if not apply:
        return report
    if not offline:
        raise ValueError("Stop all profile writers and supply --offline before applying")
    target_scope = artifact_scope(home)
    target = parent / target_scope
    if target_scope in scopes:
        raise ValueError("The stable target cannot be a legacy source")
    with tempfile.TemporaryDirectory(dir=parent, prefix=".migration-") as temporary:
        staging_home = Path(temporary)
        staging_root = staging_home / "bellpull-artifacts" / target_scope
        if target.exists():
            if target.is_symlink():
                raise ValueError("Stable artifact namespace cannot be a symlink")
            # No copying links out of an artifact namespace, even for an admin migration.
            for path in target.rglob("*"):
                info = path.lstat()
                if stat.S_ISLNK(info.st_mode) or (stat.S_ISREG(info.st_mode) and info.st_nlink != 1):
                    raise ValueError("Stable artifact tree contains unsafe links")
            shutil.copytree(target, staging_root)
        store = ArtifactStore(staging_home, target_scope)
        for folder, rows in sources:
            if folder.is_symlink() or (folder / "content").is_symlink():
                raise ValueError("Legacy namespace cannot be a symlink")
            for row in rows:
                if not re.fullmatch(r"[a-f0-9]{32}", row["id"]):
                    raise ValueError("Invalid legacy artifact identity")
                checked_name(row["name"]); checked_session(row["session_id"]); checked_type(row["mime"])
                if not re.fullmatch(re.escape(row["id"]) + r"\.[a-z0-9]{1,8}", row["blob"]):
                    raise ValueError("Unsafe legacy artifact blob path")
                with store._db() as database:
                    old = database.execute("SELECT * FROM artifacts WHERE id = ?", (row["id"],)).fetchone()
                    if old:
                        if any(old[key] != row.get(key) for key in ("session_id", "name", "mime", "sha256", "source", "request_id", "message_id")):
                            raise ValueError("Artifact identity conflict; migration did not change the target")
                        continue
                path = folder / "content" / row["blob"]
                if path.exists() and not row.get("deleted_at"):
                    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
                    with os.fdopen(fd, "rb") as file:
                        info = os.fstat(file.fileno())
                        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                            raise ValueError("Legacy artifact is not a safe regular file")
                        copied = store.store(iter(lambda: file.read(65536), b""), session_id=row["session_id"],
                            name=row["name"], mime=row["mime"], artifact_id=row["id"], source=row["source"])
                    if copied["sha256"] != row["sha256"] or copied["size"] != row["size"]:
                        raise ValueError("Legacy content verification failed")
                with store._db() as database:
                    columns = [item[1] for item in database.execute("PRAGMA table_info(artifacts)")]
                    database.execute("INSERT OR REPLACE INTO artifacts (" + ",".join(columns) + ") VALUES (" + ",".join("?" for _ in columns) + ")",
                        [row.get(key) for key in columns])
        backup = None
        if target.exists():
            backup = parent / (".backup-" + uuid.uuid4().hex)
            os.replace(target, backup)
        try:
            os.replace(staging_root, target)
        except BaseException:
            if backup is not None:
                os.replace(backup, target)
            raise
        report.update(applied=True, stable_scope=target_scope, backup=str(backup) if backup else None)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--home", required=True, type=Path)
    parser.add_argument("--legacy-scope", required=True, action="append")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    print(json.dumps(migrate(args.home, args.legacy_scope, apply=args.apply, offline=args.offline), indent=2))


if __name__ == "__main__":
    main()
