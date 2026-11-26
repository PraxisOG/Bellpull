"""Verify dispatch recovery through the native run hook and authenticated HTTP surface."""

import asyncio
import importlib.util
import os
from pathlib import Path
import secrets
import tempfile
import unittest
from unittest.mock import patch

from aiohttp import ClientSession, web
from aiohttp.test_utils import TestServer
from gateway.config import PlatformConfig
from gateway.platforms.api_server import APIServerAdapter
from hermes_cli.plugins import get_plugin_manager


@unittest.skipUnless(
    importlib.util.find_spec("gateway.platforms.api_server_extensions"),
    "Host supplies no native Bellpull run-status hook",
)
class NativeNotificationTest(unittest.IsolatedAsyncioTestCase):
    async def test_native_completion_resumes_dispatch_after_a_same_profile_restart(self):
        hints, admissions = [], []
        online = False

        async def publish(request):
            hints.append(await request.json())
            return web.Response(status=200 if online else 503)

        async def health(request):
            self.assertNotIn("Authorization", request.headers)
            return web.json_response({"healthy": online}, status=200 if online else 503)

        broker = web.Application()
        broker.router.add_get("/v1/health", health)
        broker.router.add_post("/{topic}", publish)
        key = secrets.token_hex(32)
        origin = "http://127.0.0.1:18689"
        history = origin + "/api/bellpull/notifications/history"
        dispatch = origin + "/api/bellpull/notifications/status"

        def gateway():
            manager.discover_and_load()
            adapter = APIServerAdapter(
                PlatformConfig(enabled=True, extra={"host": "127.0.0.1", "port": 18689, "key": key})
            )

            def create_agent(**kwargs):
                class Agent:
                    session_id = kwargs.get("session_id")

                    def run_conversation(self, **values):
                        admissions.append(values["task_id"])
                        return {"final_response": "Private fixture result", "completed": True}

                return Agent()

            adapter._create_agent = create_agent
            return adapter

        async def receipt(client, cursor, expected_http):
            for _ in range(200):
                response = await client.get(history, params={"after": cursor})
                self.assertEqual(200, response.status, await response.text())
                rows = (await response.json())["events"]
                if rows and rows[0]["last_http_status"] == expected_http:
                    return rows[0]
                await asyncio.sleep(0.025)
            self.fail("The native notification receipt did not reach the expected transport state")

        async with TestServer(broker) as server:
            with (
                tempfile.TemporaryDirectory() as directory,
                patch.dict(
                    os.environ,
                    {"HERMES_HOME": directory, "BELLPULL_NTFY_URL": str(server.make_url("")).rstrip("/")},
                ),
            ):
                Path(directory, "config.yaml").write_text("plugins:\n  enabled:\n    - bellpull\n")
                manager = get_plugin_manager()
                service = gateway()
                try:
                    self.assertTrue(await service.connect())
                    async with ClientSession(headers={"Authorization": "Bearer " + key}) as client:
                        self.assertEqual(
                            401, (await client.get(history, headers={"Authorization": "Bearer wrong"})).status
                        )
                        session = (await (await client.post(origin + "/api/sessions", json={})).json())[
                            "session"
                        ]["id"]
                        response = await client.post(
                            origin + "/v1/runs",
                            headers={"Idempotency-Key": "fixture-once"},
                            json={"session_id": session, "input": "Finish the synthetic fixture"},
                        )
                        self.assertEqual(202, response.status, await response.text())
                        run = (await response.json())["run_id"]
                        first = await receipt(client, 0, 503)
                        self.assertEqual(401, (await client.get(dispatch, headers={"Authorization": ""})).status)
                        checked = await client.get(dispatch)
                        self.assertEqual("no-store", checked.headers["Cache-Control"])
                        observed = await checked.json()
                        self.assertEqual({"state": "unavailable", "http_status": 503}, observed["transport"])
                        self.assertEqual({"state": "available", "pending": 1,
                                          "oldest_pending_at": first["stored_at"], "last_accepted_at": None}, observed["backlog"])
                    await service.disconnect()
                    manager.unload()
                    online = True
                    service = gateway()
                    self.assertTrue(await service.connect())
                    async with ClientSession(headers={"Authorization": "Bearer " + key}) as client:
                        final = await receipt(client, first["revision"], 200)
                        self.assertEqual("transport_accepted", final["dispatch"])
                        self.assertEqual(
                            (first["event_id"], session, run),
                            (final["event_id"], final["session_id"], final["run_id"]),
                        )
                        observed = await (await client.get(dispatch)).json()
                        self.assertEqual({"state": "healthy", "http_status": 200}, observed["transport"])
                        self.assertEqual({"state": "available", "pending": 0,
                                          "oldest_pending_at": None, "last_accepted_at": final["accepted_at"]}, observed["backlog"])
                        again = await (await client.get(history, params={"after": final["revision"]})).json()
                        self.assertEqual([], again["events"])
                    self.assertEqual(1, len(admissions))
                    self.assertEqual([{"event_id": first["event_id"]}] * 2, hints)
                finally:
                    await service.disconnect()
                    manager.unload()
                    from gateway import status

                    if callable(getattr(status, "flush_runtime_status", None)):
                        await asyncio.to_thread(status.flush_runtime_status)
