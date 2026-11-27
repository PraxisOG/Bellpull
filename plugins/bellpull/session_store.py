"""Profile-scoped session access for Bellpull's standalone and gateway callers."""

from contextlib import contextmanager

from .artifacts import ArtifactError


class ProfileSessions:
    def __init__(self, store):
        self.store = store

    def get_session(self, session_id):
        row = self.store.sessions.get(session_id) if session_id else None
        return row.as_session_row() if row is not None else None

    def branch_rows(self):
        ids = self.store.sessions.session_ids_with_model_config_key("_branched_from")
        if len(ids) > 10000:
            raise ArtifactError("Too many branch records to reconcile safely.", 413)
        return [row for sid in ids if (row := self.get_session(sid)) is not None]

    def children(self, session_id):
        # descendants excludes rows carrying inherited branch markers, even when
        # the marker refers to an earlier parent. Include those for the explicit
        # per-edge check in compression_ids.
        sessions = self.store.sessions
        ids = {
            edge.session_id
            for edge in sessions.descendants(session_id)
            if edge.parent_session_id == session_id
        }
        for marker in ("_branched_from", "_delegate_from", "_reset_from"):
            for sid in sessions.session_ids_with_model_config_key(marker):
                row = sessions.get(sid)
                if row and row.parent_session_id == session_id:
                    ids.add(sid)
        return [row for sid in ids if (row := self.get_session(sid)) is not None]


def uses_persistence(adapter):
    return all(
        callable(getattr(adapter, k, None))
        for k in ("_ensure_store_async", "_store_beside", "_room_grant_token")
    )


async def for_adapter(adapter):
    if uses_persistence(adapter):
        store = adapter._store_beside(await adapter._ensure_store_async())
        if store is not None:
            return ProfileSessions(store)
    else:
        db = await adapter._ensure_session_db_async()
        if db is not None:
            return db
    raise ArtifactError("Hermes session storage is unavailable.", 503)


@contextmanager
def reader(home):
    try:
        from persistence_assembly import open_persistence_reader
    except ImportError:
        from hermes_state import SessionDB

        db = SessionDB(home / "state.db", read_only=True)
        try:
            yield db
        finally:
            db.close()
    else:
        with open_persistence_reader(home) as store:
            yield ProfileSessions(store)
