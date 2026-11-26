"""Read one native scheduler receipt without asking Hermes to drain the queue."""
import json
import os
import re
import stat

from .routine_results import directory


def reader():
    try:
        from cron.bot_chat_delivery import read_pending
    except ImportError:
        try:
            from cron.bot_chat_legacy import defer
        except ImportError:
            return None
        return read_retained if callable(defer) else None
    return lambda home, key: read_pending(key)


def read_retained(home, key):
    if not re.fullmatch(r"[0-9a-f]{32,64}", key):
        raise ValueError("Invalid deferred receipt id.")
    try:
        with directory(home) as profile, directory("cron", profile) as cron, directory("bot_chat_pending", cron) as folder:
            fd = os.open(key + ".json", os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=folder)
            with os.fdopen(fd, encoding="utf-8") as file:
                info = os.fstat(file.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                    raise ValueError("Invalid deferred receipt file.")
                record = json.load(file)
    except FileNotFoundError:
        return None
    if not isinstance(record, dict):
        raise ValueError("Invalid deferred receipt.")
    return record
