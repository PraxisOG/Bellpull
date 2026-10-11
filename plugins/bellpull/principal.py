"""Stable profile-owner identity, independent of the rotating bearer credential."""
import hashlib
import fcntl
import os
from pathlib import Path
import re
import uuid


def artifact_scope(home):
    folder = Path(home) / "bellpull"
    if folder.is_symlink():
        raise ValueError("Bellpull identity folder cannot be a symlink")
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = folder / "principal-id"
    if path.is_symlink():
        raise ValueError("Bellpull principal identity cannot be a symlink")
    fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "r+") as file:
        if os.fstat(file.fileno()).st_nlink != 1:
            raise ValueError("Bellpull principal identity cannot be hardlinked")
        fcntl.flock(file, fcntl.LOCK_EX)
        value = file.read().strip()
        if not value:
            value = uuid.uuid4().hex
            file.write(value)
            file.flush()
            os.fsync(file.fileno())
    if not re.fullmatch(r"[a-f0-9]{32}", value):
        raise ValueError("Invalid Bellpull principal identity; restore its backup")
    return hashlib.sha256(("bellpull-profile-owner-v1:" + value).encode()).hexdigest()
