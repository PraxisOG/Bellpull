"""Exercise the real manager and frozen native router, with no live inference."""
import asyncio
from functools import wraps
import inspect
import os
from pathlib import Path
import secrets
import tempfile
import threading
import unittest
from unittest.mock import patch

from aiohttp import ClientSession, web
from aiohttp.test_utils import TestServer
from gateway.config import PlatformConfig
from gateway.platforms.api_server import APIServerAdapter
from hermes_cli.plugins import get_plugin_manager


class RediscoveryTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.environment = patch.dict(os.environ, {"HERMES_HOME": self.temp.name, "BELLPULL_NTFY_URL": ""})
        self.environment.start()
        helper = web.Application()
        async def health(request):
            return web.json_response({"healthy": True})
        helper.router.add_get("/health", health)
        self.speech = TestServer(helper)
        await self.speech.start_server()
        os.environ["BELLPULL_SPEECH_URL"] = str(self.speech.make_url("")).rstrip("/")
        Path(self.temp.name, "config.yaml").write_text("plugins:\n  enabled:\n    - bellpull\n")
        self.manager = get_plugin_manager()
        self.manager.discover_and_load()
        self.key = secrets.token_hex(32)
        self.adapter = APIServerAdapter(PlatformConfig(enabled=True,
            extra={"key": self.key, "host": "127.0.0.1", "port": 18659}))
        self.release = threading.Event()
        self.release.set()
        self.executions = []
        owner = self
        signature = inspect.signature(self.adapter._create_agent)
        @wraps(self.adapter._create_agent)
        def create_agent(*args, **kwargs):
            kwargs = signature.bind_partial(*args, **kwargs).arguments
            class Agent:
                session_id = kwargs.get("session_id")
                reasoning_callback = kwargs.get("reasoning_callback")
                def run_conversation(self, **arguments):
                    owner.executions.append(arguments)
                    if not owner.release.wait(10):
                        raise RuntimeError("Fixture did not release native run")
                    return {"final_response": "Reload preserved this run", "completed": True}
            return Agent()
        self.adapter._create_agent = create_agent
        self.assertTrue(await self.adapter.connect())
        self.client = ClientSession(headers={"Authorization": "Bearer " + self.key})
        self.origin = "http://127.0.0.1:18659"

    async def asyncTearDown(self):
        self.release.set()
        await self.client.close()
        await self.adapter.disconnect()
        await self.speech.close()
        self.manager.unload()
        from gateway import status
        flush = getattr(status, "flush_runtime_status", None)
        if callable(flush):
            await asyncio.to_thread(flush)
        self.environment.stop()
        self.temp.cleanup()

    def rewire(self):
        # Older supported Hermes wires only at connect; newer versions also
        # invoke this method after discovery, deduplicating the existing factory.
        rewire = getattr(self.adapter, "rewire_plugin_handlers", None)
        if callable(rewire):
            rewire()

    async def capabilities(self, expected=200):
        # Re-registration can arrive from a discovery worker. Let asynchronous
        # resource cleanup/restart finish, without retrying a user action.
        for _ in range(100):
            async with self.client.get(self.origin + "/api/bellpull/capabilities") as response:
                body = await response.json()
                if response.status == expected:
                    return body
                status = response.status
            await asyncio.sleep(.02)
        self.fail(f"Expected {expected}, got {status}: {body}")

    async def test_repeated_rediscovery_preserves_routes_auth_and_saved_session(self):
        await self.capabilities()
        app = self.adapter._app
        routes = tuple(app.router.routes())
        response = await self.client.post(self.origin + "/api/sessions", json={})
        self.assertEqual(201, response.status, await response.text())
        session = (await response.json())["session"]["id"]
        # An unrelated plugin installation forces the same global discovery sweep.
        other = Path(self.temp.name, "plugins", "reload-fixture")
        other.mkdir(parents=True)
        (other / "plugin.yaml").write_text("name: reload-fixture\nversion: 1.0.0\n")
        (other / "__init__.py").write_text("def register(ctx):\n    pass\n")
        Path(self.temp.name, "config.yaml").write_text("plugins:\n  enabled:\n    - bellpull\n    - reload-fixture\n")
        for _ in range(3):
            self.manager.discover_and_load(force=True)
            self.rewire()
            await self.capabilities()
            self.assertIs(app, self.adapter._app)
            self.assertEqual(routes, tuple(app.router.routes()))
            response = await self.client.get(self.origin + "/api/sessions/" + session)
            self.assertEqual(200, response.status, await response.text())
            response = await self.client.get(self.origin + "/api/bellpull/capabilities",
                headers={"Authorization": "Bearer wrong"})
            self.assertEqual(401, response.status)
            self.assertEqual(1, len(self.manager.get_platform_handler_factories("api_server")))
            response = await self.client.get(self.origin + "/api/bellpull/speech/health")
            self.assertEqual(200, response.status, await response.text())
            self.assertTrue((await response.json())["healthy"])

    async def test_explicit_disable_stays_revoked_after_rewire(self):
        await self.capabilities()
        self.manager.unload("bellpull")
        self.rewire()
        await self.capabilities(503)
        await asyncio.sleep(.1)
        await self.capabilities(503)
        for stop in getattr(self.manager, "_bellpull_http_bindings", ()):
            self.assertFalse(stop.coding.active)

    async def test_disable_wins_over_pending_reload_recovery(self):
        await self.capabilities()
        for _ in range(3):
            self.manager.discover_and_load(force=True)
        # Resume tasks are queued on this loop but have not run yet.
        self.manager.unload("bellpull")
        await self.capabilities(503)
        await asyncio.sleep(.1)
        await self.capabilities(503)
        response = await self.client.get(self.origin + "/health/detailed")
        self.assertEqual(200, response.status)
        stop, = tuple(self.manager._bellpull_http_bindings)
        self.assertFalse(stop.coding.active)
        self.assertTrue(stop.coding.task.done())

    async def test_worker_rediscovery_and_config_disable_do_not_leave_live_observers(self):
        await self.capabilities()
        for _ in range(3):
            await asyncio.to_thread(self.manager.discover_and_load, force=True)
            self.rewire()
            await self.capabilities()
            bindings = tuple(self.manager._bellpull_http_bindings)
            self.assertEqual(1, len(bindings))
            coding = bindings[0].coding
            self.assertTrue(coding.active)
            self.assertFalse(coding.task.done())
            if coding.registry is not None:
                observers = [item for item in coding.registry._bellpull_coding_observers
                             if item.adapter is self.adapter]
                self.assertEqual([coding], observers)
        Path(self.temp.name, "config.yaml").write_text("plugins:\n  disabled:\n    - bellpull\n")
        await asyncio.to_thread(self.manager.discover_and_load, force=True)
        self.rewire()
        await self.capabilities(503)
        await asyncio.sleep(.1)
        self.assertFalse(coding.active)
        self.assertTrue(coding.task.done())

    async def test_changed_bundle_requires_restart_and_other_manager_cannot_reactivate(self):
        from hermes_cli.plugins import PluginManager, PluginContext, PluginManifest
        from plugins.bellpull import register
        await self.capabilities()
        stop, = tuple(self.manager._bellpull_http_bindings)
        self.manager.unload("bellpull")
        other = PluginManager(scope_key=str(Path(self.temp.name) / "other"))
        try:
            register(PluginContext(PluginManifest(name="bellpull"), other))
            await self.capabilities(503)
            self.assertEqual([], list(other._bellpull_http_bindings))
        finally:
            other.unload()
        stop.bundle_identity = "different-bundle"
        self.manager.discover_and_load(force=True)
        self.rewire()
        result = await self.capabilities(503)
        self.assertTrue(result["error"]["restart_required"])
        self.assertEqual("bellpull_restart_required", result["error"]["code"])
        # Rebuilding the native HTTP app is the safe recovery path for changed
        # code; the old frozen router is never mutated or falsely advertised.
        await self.adapter.disconnect()
        self.assertTrue(await self.adapter.connect())
        await self.capabilities()

    async def test_reload_preserves_active_run_and_idempotent_replay(self):
        self.release.clear()
        headers = {"Idempotency-Key": "reload-active-run"}
        body = {"input": "Wait through plugin rediscovery"}
        response = await self.client.post(self.origin + "/v1/runs", json=body, headers=headers)
        self.assertEqual(202, response.status, await response.text())
        run = (await response.json())["run_id"]
        for _ in range(100):
            if self.executions:
                break
            await asyncio.sleep(.02)
        self.assertEqual(1, len(self.executions))
        await asyncio.to_thread(self.manager.discover_and_load, force=True)
        self.rewire()
        await self.capabilities()
        response = await self.client.get(self.origin + "/v1/runs/" + run)
        self.assertEqual("running", (await response.json())["status"])
        repeated = await self.client.post(self.origin + "/v1/runs", json=body, headers=headers)
        self.assertEqual(run, (await repeated.json())["run_id"])
        self.assertEqual(1, len(self.executions))
        self.release.set()
        for _ in range(100):
            response = await self.client.get(self.origin + "/v1/runs/" + run)
            state = (await response.json())["status"]
            if state == "completed":
                break
            await asyncio.sleep(.02)
        self.assertEqual("completed", state)
        repeated = await self.client.post(self.origin + "/v1/runs", json=body, headers=headers)
        self.assertEqual(run, (await repeated.json())["run_id"])
        self.assertEqual(1, len(self.executions))
