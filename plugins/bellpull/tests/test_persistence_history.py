"""Stock Hermes history projection and completed-worker identity without model inference."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from aiohttp import ClientSession
from gateway.config import PlatformConfig
from gateway.platforms.api_server import APIServerAdapter
from hermes_cli.plugins import get_plugin_manager

from plugins.bellpull.pagination import persistence_history_supported


@unittest.skipUnless(persistence_history_supported(APIServerAdapter), "Host uses legacy session storage")
class PersistenceHistoryTest(unittest.IsolatedAsyncioTestCase):
    async def test_delivered_results_keep_native_identity_in_an_immutable_page(self):
        from gateway.run_notifications import GatewayNotificationsMixin

        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
            "HERMES_HOME": directory, "BELLPULL_NTFY_URL": "",
        }):
            Path(directory, "config.yaml").write_text("plugins:\n  enabled: [bellpull]\n")
            manager = get_plugin_manager()
            manager.discover_and_load()
            adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={
                "key": "history-fixture-only", "host": "127.0.0.1", "port": 0,
            }))
            try:
                self.assertTrue(await adapter.connect())
                port = adapter._site._server.sockets[0].getsockname()[1]
                api = f"http://127.0.0.1:{port}/api"
                async with ClientSession(headers={"Authorization": "Bearer history-fixture-only"}) as client:
                    async def read(path, **query):
                        async with client.get(api + path, params=query) as response:
                            self.assertEqual(200, response.status, await response.text())
                            return await response.json()

                    features = (await read("/bellpull/capabilities"))["features"]
                    self.assertTrue(features["bellpull_snapshot_paging"])
                    self.assertTrue(features["bellpull_delegation_delivery"])
                    async with client.post(api + "/sessions", json={}) as response:
                        self.assertIn(response.status, (200, 201))
                        sid = (await response.json())["session"]["id"]
                    chats = await read("/bellpull/sessions", source="api_server")
                    self.assertIn(sid, [chat["id"] for chat in chats["data"]])

                    async def deliver(unit):
                        event = {"type": "async_delegation", "origin_session_id": sid,
                                 "delegation_id": unit, "completion_reason": "completed"}
                        self.assertTrue(await GatewayNotificationsMixin()._self_post_api_server(
                            adapter, "Finished " + unit, sid, event))

                    path = f"/bellpull/sessions/{sid}/messages"
                    await deliver("deleg_first")
                    await deliver("deleg_second")
                    first = await read(path, limit=1)
                    await deliver("deleg_first")
                    await deliver("deleg_third")
                    following = await read(path, cursor=first["pagination"]["next_cursor"])
                    self.assertIsNone(following["pagination"]["next_cursor"])
                    self.assertEqual(["deleg_first", "deleg_second"],
                                     [r["delegation_id"] for r in first["data"] + following["data"]])
                    fresh = await read(path)
                    native = await read(f"/sessions/{sid}/messages")
                    self.assertEqual(sid, fresh["session_id"])
                    self.assertEqual(3, len(fresh["data"]))
                    self.assertEqual([r["id"] for r in native["data"]], [r["id"] for r in fresh["data"]])
                    self.assertEqual(["deleg_first", "deleg_second", "deleg_third"],
                                     [r["delegation_id"] for r in fresh["data"]])
                    for row in fresh["data"]:
                        self.assertNotIn("display_metadata", row)
            finally:
                await adapter.disconnect()
                manager.unload()
