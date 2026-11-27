"""Real storage fixtures across the supported Hermes releases."""

from pathlib import Path

from gateway.config import PlatformConfig
from gateway.platforms.api_server import APIServerAdapter
from plugins.bellpull.session_store import ProfileSessions

try:
    from persistence_assembly import open_persistence, profile_scope
except ImportError:
    open_persistence = None


def storage(test, home, legacy):
    if open_persistence is None:
        return legacy
    store = open_persistence(profile_scope(home=Path(home)))
    test.addCleanup(store.close)
    return ProfileSessions(store)


def compact(db, home, session, rows):
    if open_persistence is None:
        return db.archive_and_compact(session, rows)
    from persistence.conversation.messages import NewMessage

    with open_persistence(profile_scope(home=Path(home))) as store:
        return store.messages.compact(
            session, [NewMessage.from_live(row) for row in rows]
        )


def adapter_for(test, home, key):
    from unittest.mock import patch

    environment = patch.dict("os.environ", {"HERMES_HOME": str(home)})
    environment.start()
    test.addCleanup(environment.stop)
    adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={"key": key}))
    test.addCleanup(adapter._close_cached_session_dbs)
    return adapter
