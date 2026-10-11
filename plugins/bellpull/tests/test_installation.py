"""Actual plugin discovery and profile middleware on a disposable installation."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from aiohttp import ClientSession
import secrets
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from gateway.config import PlatformConfig
from gateway.platforms.api_server import APIServerAdapter
from hermes_cli.plugins import PluginManager, get_plugin_manager


class InstallationTest(unittest.IsolatedAsyncioTestCase):
    async def test_unreviewed_host_source_disables_features(self):
        from plugins.bellpull.http import wire
        with patch("plugins.bellpull.http.source_mode", return_value=None):
            adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={"key": "unsupported-fixture-key"}))
            app = web.Application()
            wire(app, adapter)
            async with TestClient(TestServer(app)) as client:
                response = await client.get("/api/bellpull/capabilities", headers={"Authorization": "Bearer unsupported-fixture-key"})
                self.assertEqual(200, response.status)
                self.assertFalse(any((await response.json())["features"].values()))
                self.assertEqual(404, (await client.get("/api/bellpull/sessions", headers={"Authorization": "Bearer unsupported-fixture-key"})).status)
            adapter._run_idempotency_store.close()

    async def test_native_adapter_startup_discovers_plugin_and_shutdown_closes_it(self):
        with tempfile.TemporaryDirectory() as home, patch.dict(os.environ, {"HERMES_HOME": home}):
            Path(home, "config.yaml").write_text("plugins:\n  enabled:\n    - bellpull\n")
            # Normal gateway bootstrap discovers plugins before adapters connect.
            manager = get_plugin_manager()
            manager.discover_and_load()
            key = secrets.token_hex(32)
            adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={"key": key, "host": "127.0.0.1", "port": 18642}))
            try:
                self.assertTrue(await adapter.connect())
                async with ClientSession() as client:
                    response = await client.get("http://127.0.0.1:18642/api/bellpull/capabilities", headers={"Authorization": "Bearer " + key})
                    self.assertEqual(200, response.status, await response.text())
                    self.assertTrue((await response.json())["features"]["bellpull_snapshot_paging"])
            finally:
                await adapter.disconnect()
                manager.unload()

    async def test_clean_manifest_discovery_and_native_profile_rejection(self):
        with tempfile.TemporaryDirectory() as home, patch.dict(os.environ, {"HERMES_HOME": home}):
            Path(home, "config.yaml").write_text("plugins:\n  enabled:\n    - bellpull\n")
            manager = PluginManager(scope_key=home)
            manager.discover_and_load()
            info = next(row for row in manager.list_plugins() if row["name"] == "bellpull")
            self.assertTrue(info["enabled"], info)
            self.assertFalse(info["error"], info)
            factories = [(factory, name) for factory, name in manager.get_platform_handler_factories("api_server") if name == "bellpull"]
            self.assertEqual(1, len(factories))
            adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={"key": "clean-install-fixture"}))
            app = web.Application(middlewares=[adapter._make_profile_prefix_middleware()])
            factories[0][0](app, adapter)
            async with TestClient(TestServer(app)) as client:
                response = await client.get("/api/bellpull/capabilities", headers={"Authorization": "Bearer clean-install-fixture"})
                self.assertEqual(200, response.status, await response.text())
                features = (await response.json())["features"]
                self.assertTrue(features["bellpull_persistent_artifacts"])
                self.assertEqual(404, (await client.get("/p/unserved/api/bellpull/capabilities",
                    headers={"Authorization": "Bearer clean-install-fixture"})).status)
            adapter._run_idempotency_store.close()
            manager.unload()
