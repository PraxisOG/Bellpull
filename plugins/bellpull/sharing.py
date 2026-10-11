"""Stock Hermes output sharing through the public plugin tool registry.

The agent explicitly stages outputs in this profile/session's outbox. No core
module replacement, MEDIA parsing, directory-wide host scan or public URL.
"""
from pathlib import Path
import json

from .artifacts import ArtifactError, ArtifactStore, checked_session
from .principal import artifact_scope


def share_files(args, *, session_id="", **kwargs):
    from hermes_constants import get_hermes_home
    from hermes_state import SessionDB

    try:
        # Session identity comes from Hermes tool dispatch, never model arguments.
        session_id = checked_session(session_id)
        home = Path(get_hermes_home())
        database = SessionDB(home / "state.db", read_only=True)
        try:
            if database.get_session(session_id) is None:
                raise ArtifactError("The active conversation is not saved yet.", 409)
        finally:
            database.close()
        store = ArtifactStore(home, artifact_scope(home))
        folder = store.output_directory(session_id, "shared-outbox")
        if args.get("action") == "prepare":
            return {"outbox": str(folder), "instructions":
                "Write or copy the files requested by the user directly into this outbox, "
                "then call bellpull_share_files with action publish. Only regular files "
                "are accepted. Do not claim delivery before publish succeeds."}
        if args.get("action") != "publish":
            raise ArtifactError("Choose prepare or publish.")
        # Serialize stock publication across tool workers. A lost response can retry
        # deterministic IDs; only successfully committed, unchanged staging copies retire.
        import fcntl
        import os
        lock = store.root / (".publish-" + session_id)
        fd = os.open(lock, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "a") as locked:
            fcntl.flock(locked, fcntl.LOCK_EX)
            warning_id = "share-" + session_id
            store.clear_warnings(warning_id)
            records = store.collect_outputs(session_id, "shared-outbox", folder=folder, retire=True, warning_id=warning_id)
            remaining = sum(1 for path in folder.iterdir() if path.name not in (".active", ".complete"))
            warnings = store.warnings(warning_id)
        status = "partial" if remaining else "published" if records else "empty"
        return {"artifacts": records, "warnings": warnings, "status": status,
                "published_count": len(records), "remaining_count": remaining,
                "message": (f"Partial publication: {len(records)} files published; {remaining} staged files remain. Check warnings and retry."
                    if remaining else "Files are available in Bellpull." if records else
                    "No files are staged. Previously published files are managed in Bellpull.")}
    except (ArtifactError, OSError, ValueError) as exc:
        return {"error": str(exc)}


def register(ctx):
    def handle(args, **kwargs):
        return json.dumps(share_files(args, **kwargs))
    ctx.register_tool(name="bellpull_share_files", toolset="bellpull",
        description="Share generated files with the user in Bellpull",
        schema={"name": "bellpull_share_files", "description":
            "Publish generated images, documents and other files to Bellpull's persistent "
            "authenticated file browser. First use prepare to obtain the active conversation's "
            "outbox. Write or copy the user-requested output files there using existing tools, "
            "then use publish. This works on stock Hermes; MEDIA tags and browser upload URLs "
            "do not deliver files to Bellpull. Maximum 25 MiB per file and 512 MiB per profile.",
            "parameters": {"type": "object", "properties": {
                "action": {"type": "string", "enum": ["prepare", "publish"]}},
                "required": ["action"], "additionalProperties": False}},
        handler=handle)
