import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

from plugins.bellpull.artifacts import ArtifactError
from plugins.bellpull.coding import Observer

try:
    from persistence.conversation.sessions import NewSession, SessionLineageKind
    from persistence_assembly import open_persistence, profile_scope
except ImportError:
    open_persistence = None


class CodingPersistenceTest(unittest.IsolatedAsyncioTestCase):
    @unittest.skipUnless(open_persistence, "Host uses the legacy session database")
    async def test_coding_uses_the_selected_profiles_compression_lineage(self):
        home = self.enterContext(tempfile.TemporaryDirectory())
        store = open_persistence(profile_scope(home=Path(home)))
        self.addCleanup(store.close)
        adapter = SimpleNamespace(_ensure_store_async=AsyncMock(return_value=store),
                                  _store_beside=lambda db: db, _room_grant_token=lambda request: "")
        observer = Observer(adapter)
        self.addCleanup(observer.stop)
        self.assertTrue(observer.can_read_sessions)
        store.sessions.create(NewSession("root", "api_server"))
        for parent, child in (("root", "middle"), ("middle", "tip")):
            store.sessions.end(parent, "compression")
            store.sessions.create(NewSession(child, "api_server", parent_session_id=parent))
        for kind in (SessionLineageKind.BRANCH, SessionLineageKind.DELEGATE, SessionLineageKind.RESET):
            store.sessions.create(NewSession(kind.value, "api_server", parent_session_id="root", lineage_kind=kind))
            self.assertEqual([kind.value], list(await observer.session_aliases(kind.value)))
        for session in ("root", "tip"):
            self.assertEqual(["root", "middle", "tip"], list(await observer.session_aliases(session)))

    async def test_coding_reads_require_profile_credentials_on_room_scoped_hosts(self):
        observer = Observer(SimpleNamespace(_room_grant_token=lambda request: "room"))
        self.addCleanup(observer.stop)
        for read in (observer.snapshot, observer.workspace):
            with self.assertRaises(ArtifactError) as error:
                await read(object())
            self.assertEqual(403, error.exception.status)
