"""Paging uses real SessionDB rows and authenticated HTTP, without inference."""
import asyncio
from pathlib import Path
import tempfile
import unittest
from unittest.mock import AsyncMock
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from gateway.platforms.api_server import APIServerAdapter
from hermes_state import SessionDB
from plugins.bellpull.http import wire


class PagingTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db = SessionDB(Path(self.temp.name) / "state.db")
        self.adapter = APIServerAdapter.__new__(APIServerAdapter)
        self.adapter._api_key = "paging-fixture"
        self.adapter._ensure_session_db_async = AsyncMock(return_value=self.db)
        self.headers = {"Authorization": "Bearer paging-fixture"}
        app = web.Application()
        wire(app, self.adapter)
        self.client = TestClient(TestServer(app))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        self.db.close()
        self.temp.cleanup()

    async def test_snapshot_keeps_450_rows_exactly_once_during_new_activity(self):
        for n in range(450):
            self.db.create_session(f"chat_{n}", "cli")
        path = "/api/bellpull/sessions"
        response = await self.client.get(path, headers=self.headers, params={"source": "cli"})
        self.assertEqual(200, response.status, await response.text())
        page = await response.json()
        ids = [row["id"] for row in page["data"]]
        self.db.create_session("arrived_after_snapshot", "cli")
        self.db.append_message("chat_0", "user", "Changes ordering")
        while page["next_cursor"]:
            page = await (await self.client.get(path, headers=self.headers,
                params={"source": "cli", "cursor": page["next_cursor"]})).json()
            ids += [row["id"] for row in page["data"]]
        self.assertEqual(450, len(ids))
        self.assertEqual({f"chat_{n}" for n in range(450)}, set(ids))
        refreshed = await (await self.client.get(path, headers=self.headers, params={"source": "cli"})).json()
        self.assertIn("arrived_after_snapshot", [row["id"] for row in refreshed["data"][:2]])

    async def test_621_messages_keep_snapshot_when_new_message_arrives(self):
        self.db.create_session("chat", "api_server")
        expected = [self.db.append_message("chat", "user", str(n)) for n in range(621)]
        path = "/api/bellpull/sessions/chat/messages"
        first = await (await self.client.get(path, headers=self.headers)).json()
        self.db.append_message("chat", "assistant", "Later")
        second = await (await self.client.get(path, headers=self.headers,
            params={"cursor": first["pagination"]["next_cursor"]})).json()
        self.assertEqual(expected, [row["id"] for row in first["data"] + second["data"]])
        self.assertEqual(621, second["pagination"]["total"])

    async def test_compressed_conversation_snapshot_resolves_saved_tip(self):
        self.db.create_session("original", "api_server")
        self.db.append_message("original", "user", "Old")
        self.db.end_session("original", "compression")
        self.db.create_session("tip", "api_server", parent_session_id="original")
        expected = self.db.append_message("tip", "assistant", "Summary")
        response = await self.client.get("/api/bellpull/sessions/original/messages", headers=self.headers)
        self.assertEqual(200, response.status, await response.text())
        value = await response.json()
        self.assertEqual("tip", value["session_id"])
        self.assertEqual([expected], [row["id"] for row in value["data"]])

    async def test_cursors_are_authenticated_scoped_and_explicitly_expire(self):
        for n in range(3):
            self.db.create_session(f"chat_{n}", "cli")
        path = "/api/bellpull/sessions"
        self.assertEqual(401, (await self.client.get(path)).status)
        page = await (await self.client.get(path, headers=self.headers, params={"source": "cli", "limit": 1})).json()
        query = {"source": "cli", "cursor": page["next_cursor"]}
        self.assertEqual(404, (await self.client.get(path, headers=self.headers,
            params={**query, "source": "tui"})).status)
        self.adapter._api_key = "rotated-fixture"
        self.assertEqual(401, (await self.client.get(path, headers=self.headers, params=query)).status)
        self.assertEqual(404, (await self.client.get(path, headers={"Authorization": "Bearer rotated-fixture"}, params=query)).status)
        from unittest.mock import patch
        from plugins.bellpull.pagination import Snapshots
        with patch.object(Snapshots, "TTL", -1):
            self.assertEqual(410, (await self.client.get(path, headers={"Authorization": "Bearer rotated-fixture"}, params=query)).status)

    async def test_compacted_prompt_is_archived_but_final_reply_remains_in_fresh_snapshot(self):
        self.db.create_session("chat", "api_server")
        prompt = self.db.append_message("chat", "user", "Original admitted prompt")
        self.db.append_message("chat", "assistant", "Intermediate tool work")
        self.db.archive_and_compact("chat", [{"role": "assistant", "content": "Context summary"}])
        path = "/api/bellpull/sessions/chat/messages"
        before = await (await self.client.get(path, headers=self.headers)).json()
        self.assertNotIn(prompt, [row["id"] for row in before["data"]])
        final = self.db.append_message("chat", "assistant", "Authoritative final output")
        after = await (await self.client.get(path, headers=self.headers)).json()
        self.assertEqual(final, after["data"][-1]["id"])
        self.assertEqual("Authoritative final output", after["data"][-1]["content"])
        self.assertIn(prompt, [row["id"] for row in self.db.get_messages("chat", include_compacted=True)])

    async def test_unconfigured_auth_is_closed(self):
        self.adapter._api_key = ""
        self.assertEqual(401, (await self.client.get("/api/bellpull/capabilities")).status)
