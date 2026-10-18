"""Real native middleware and secret/data scopes, with disposable profile homes."""
import asyncio
import os
from pathlib import Path
import secrets
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from gateway.config import PlatformConfig
from gateway.platforms.api_server import APIServerAdapter, body_limit_middleware
from gateway.run import _profile_runtime_scope
from plugins.bellpull.http import wire


class ProfileTest(unittest.IsolatedAsyncioTestCase):
    async def test_native_profile_scopes_isolate_artifacts_branches_runs_and_approvals(self):
        with tempfile.TemporaryDirectory() as temporary:
            homes = {name: Path(temporary, name) for name in ("alpha", "beta")}
            keys = {name: secrets.token_hex(32) for name in homes}
            for name, home in homes.items():
                home.mkdir()
                (home / ".env").write_text("API_SERVER_KEY=" + keys[name] + "\n")
                (home / "config.yaml").write_text("{}\n")
            with patch.dict(os.environ, {"HERMES_HOME": temporary}), \
                    patch("hermes_cli.profiles.profiles_to_serve", return_value=list(homes.items())), \
                    patch("hermes_cli.profiles.get_profile_dir", side_effect=homes.__getitem__):
                adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={"key": secrets.token_hex(32)}))
                adapter.gateway_runner = SimpleNamespace(config=SimpleNamespace(multiplex_profiles=True, multiplex_profile_allowlist=None))
                class Agent:
                    session_id = "fixture"
                    def run_conversation(self, **kwargs): return {"final_response": "Fixture"}
                adapter._create_agent = lambda **kwargs: Agent()
                app = web.Application(middlewares=[adapter._make_profile_prefix_middleware(), body_limit_middleware])
                for method, path, handler in adapter._http_route_table():
                    app.router.add_route(method, "/p/{profile}" + path, handler)
                wire(app, adapter)
                async with TestClient(TestServer(app)) as client:
                    headers = {name: {"Authorization": "Bearer " + key} for name, key in keys.items()}
                    a, b = "/p/alpha", "/p/beta"
                    self.assertEqual(401, (await client.get(a + "/api/bellpull/capabilities", headers=headers["beta"])).status)
                    self.assertEqual(401, (await client.get(a + "/api/bellpull/offline/memory", headers=headers["beta"])).status)
                    for name, home in homes.items():
                        (home / "memories").mkdir(exist_ok=True)
                        (home / "memories" / "USER.md").write_text("User belongs to " + name)
                    for name, prefix in (("alpha", a), ("beta", b)):
                        response = await client.get(prefix + "/api/bellpull/offline/memory", headers=headers[name])
                        self.assertEqual(200, response.status, await response.text())
                        self.assertIn("belongs to " + name, (await response.json())["text"])
                    response = await client.post(a + "/api/sessions", headers=headers["alpha"], json={})
                    session = (await response.json())["session"]["id"]
                    project = "b" * 32
                    import uuid
                    response = await client.post(a + "/api/bellpull/projects", headers=headers["alpha"], json={
                        "operation": "create", "operation_id": uuid.uuid4().hex, "expected_revision": 0,
                        "project_id": project, "name": "Private work"})
                    self.assertEqual(200, response.status, await response.text())
                    self.assertEqual([], (await (await client.get(b + "/api/bellpull/projects", headers=headers["beta"])).json())["projects"])
                    self.assertEqual(401, (await client.get(a + "/api/bellpull/projects", headers=headers["beta"])).status)
                    response = await client.post(a + "/api/bellpull/projects", headers=headers["alpha"], json={
                        "operation": "move", "operation_id": uuid.uuid4().hex, "expected_revision": 1,
                        "session_id": session, "project_id": project})
                    self.assertEqual(200, response.status, await response.text())
                    with _profile_runtime_scope(homes["alpha"]):
                        database = adapter._ensure_session_db()
                        database.append_message(session, "user", "First")
                        answer = database.append_message(session, "assistant", "Saved")
                    identity = "a" * 32
                    path = "/api/bellpull/artifacts/" + identity
                    response = await client.put(a + path, headers={**headers["alpha"], "Content-Type": "text/plain"},
                        params={"session_id": session, "name": "private.txt"}, data=b"alpha bytes")
                    self.assertEqual(201, response.status, await response.text())
                    self.assertEqual(404, (await client.get(b + path + "/content", headers=headers["beta"])).status)
                    self.assertEqual([], (await (await client.get(b + "/api/bellpull/artifacts", headers=headers["beta"])).json())["data"])
                    response = await client.post(b + f"/api/sessions/{session}/fork-at-message", headers=headers["beta"],
                        json={"through_message_id": answer})
                    self.assertEqual(404, response.status, await response.text())
                    response = await client.post(a + "/v1/runs", headers={**headers["alpha"], "Idempotency-Key": "profile-fixture"},
                        json={"session_id": session, "input": "Fixture"})
                    self.assertEqual(202, response.status, await response.text())
                    run = (await response.json())["run_id"]
                    self.assertEqual(404, (await client.get(b + "/v1/runs/" + run, headers=headers["beta"])).status)
                    response = await client.post(b + "/v1/runs/" + run + "/approval", headers=headers["beta"],
                        json={"request_id": "foreign", "choice": "once"})
                    self.assertEqual(404, response.status, await response.text())
                    transfer = {"operation_id": "c" * 32, "source_id": session, "title": "Local fork",
                                "messages": [{"role": "user", "content": "Offline question"}]}
                    self.assertEqual(404, (await client.post(b + "/api/bellpull/offline/handoff", headers=headers["beta"], json=transfer)).status)
                    self.assertEqual(401, (await client.post(a + "/api/bellpull/offline/handoff", headers=headers["beta"], json=transfer)).status)
                    response = await client.post(a + "/api/bellpull/offline/handoff", headers=headers["alpha"], json=transfer)
                    self.assertEqual(200, response.status, await response.text())
                    self.assertTrue((await response.json())["created"])
                    response = await client.post(a + "/api/bellpull/offline/handoff", headers=headers["alpha"], json=transfer)
                    self.assertFalse((await response.json())["created"])
                    await asyncio.gather(*adapter._active_run_tasks.values(), return_exceptions=True)
                adapter._run_idempotency_store.close()
                for database in adapter._session_dbs.values(): database.close()
