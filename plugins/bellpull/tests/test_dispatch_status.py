"""Exercise plugin discovery and the manual dispatch check through the installed host API."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from aiohttp import ClientSession
from gateway.config import PlatformConfig
from gateway.platforms.api_server import APIServerAdapter
from hermes_cli.plugins import get_plugin_manager

from plugins.bellpull.version import VERSION


class DispatchStatusHostTest(unittest.IsolatedAsyncioTestCase):
    async def test_loaded_plugin_reports_its_own_version_and_run_receipt_support(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
            "HERMES_HOME": directory, "BELLPULL_NTFY_URL": "",
        }):
            Path(directory, "config.yaml").write_text("plugins:\n  enabled: [bellpull]\n")
            manager = get_plugin_manager()
            manager.discover_and_load()
            adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={
                "key": "dispatch-fixture-only", "host": "127.0.0.1", "port": 0,
            }))
            try:
                self.assertTrue(await adapter.connect())
                port = adapter._site._server.sockets[0].getsockname()[1]
                base = f"http://127.0.0.1:{port}/api/bellpull"
                async with ClientSession(headers={"Authorization": "Bearer dispatch-fixture-only"}) as client:
                    async with client.get(base + "/capabilities") as response:
                        self.assertEqual(response.status, 200)
                        capabilities = await response.json()
                    self.assertEqual(VERSION, capabilities["plugin_version"])
                    async with client.get(base + "/notifications/status") as response:
                        self.assertEqual(response.status, 200)
                        status = await response.json()
                    self.assertEqual({"state": "unconfigured"}, status["transport"])
                    if capabilities["features"]["bellpull_notification_history"]:
                        self.assertEqual({"state": "available", "pending": 0,
                                          "oldest_pending_at": None, "last_accepted_at": None}, status["backlog"])
                    else:
                        self.assertEqual({"state": "unsupported"}, status["backlog"])
            finally:
                await adapter.disconnect()
                manager.unload()
