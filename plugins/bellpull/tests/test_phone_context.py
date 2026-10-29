import json
import os
from pathlib import Path
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from plugins.bellpull.phone_context import ContextStore, prompt, bounded, wire, BUDGET
from plugins.bellpull.artifacts import ArtifactError


class PhoneContextTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.home = Path(self.temp.name)
        self.env = patch.dict(os.environ, {"HERMES_HOME": str(self.home)})
        self.env.start()
        self.now = int(time.time()*1000)
        self.device = "a"*32
        self.store = ContextStore(self.home)

    def tearDown(self):
        self.env.stop(); self.temp.cleanup()

    def body(self, sequence=1, policy="allow", **changes):
        result = dict(sequence=sequence, policy=policy, timezone="America/Los_Angeles", notifications_enabled=True,
                      calendar_enabled=True, listener_connected=True, calendar_read_at_ms=self.now, truncated=False,
                      notifications=[dict(id="notification-one", app="Messages", title="Rowan", text="Meet at the west gate.",
                                          posted_at_ms=self.now-1000, updated_at_ms=self.now, removed_at_ms=None)],
                      calendar=[dict(id="event-one", title="Museum visit", calendar="Personal", start_ms=self.now+60000,
                                     end_ms=self.now+3600000, all_day=False)])
        result.update(changes); return result

    def test_upload_is_idempotent_and_reordering_cannot_restore_revoked_data(self):
        first = self.body()
        reply = self.store.update(self.device, first)
        self.assertEqual(reply, self.store.update(self.device, first))
        self.store.update(self.device, self.body(2, "revoke", notifications_enabled=False, calendar_enabled=False, notifications=[], calendar=[]))
        with self.assertRaises(ArtifactError): self.store.update(self.device, first)
        self.assertEqual([], self.store.search(""))
        self.assertEqual([], ContextStore(self.home).search(""))

    def test_first_snapshot_is_stable_on_retries_and_followups(self):
        self.store.update(self.device, self.body())
        first = self.store.for_session("chat", True)
        self.store.update(self.device, self.body(2, notifications=[]))
        self.assertEqual(first, self.store.for_session("chat", True))
        self.assertEqual(first, ContextStore(self.home).for_session("chat", False))
        self.assertEqual([], self.store.for_session("old-chat", False))
        self.assertEqual([], self.store.for_session("new-chat", True)[0]["notifications"])

    def test_revocation_and_exclusion_scrub_frozen_snapshots_without_reseeding_chat(self):
        self.store.update(self.device, self.body())
        self.store.for_session("chat", True)
        self.store.update(self.device, self.body(2, "exclude-messages", notifications=[]))
        self.assertEqual([], self.store.for_session("chat", False))
        self.store.update(self.device, self.body(3, "reshare"))
        self.assertEqual([], self.store.for_session("chat", True))
        self.assertTrue(self.store.for_session("later-chat", True))
        with self.store.db() as db:
            self.assertNotIn("west gate", db.execute("SELECT payload FROM sessions WHERE id='chat'").fetchone()[0])

    def test_context_is_profile_scoped_and_does_not_create_hermes_sessions(self):
        self.store.update(self.device, self.body())
        self.assertEqual([], ContextStore(self.home / "other-profile").search(""))
        self.assertFalse((self.home / "state.db").exists())
        self.assertTrue(self.store.search("west gate"))
        self.assertEqual([], self.store.search("unmatched")[0]["notifications"])
        body = self.body(2)
        body["notifications"][0]["text"] = "Meet at Café 東京"
        self.store.update(self.device, body)
        self.assertEqual(1, len(self.store.search("café 東京")[0]["notifications"]))

    def test_expired_payloads_are_scrubbed_but_revision_tombstones_remain(self):
        self.store.update(self.device, self.body())
        self.store.for_session("chat", True)
        with patch("plugins.bellpull.phone_context.time.time", return_value=time.time()+86401):
            self.assertEqual([], self.store.search(""))
            self.assertEqual([], self.store.for_session("chat", False))
        with self.store.db() as db:
            self.assertEqual("{}", db.execute("SELECT payload FROM devices").fetchone()[0])
            self.assertEqual(1, db.execute("SELECT sequence FROM devices").fetchone()[0])

    def test_bounds_and_calendar_window_and_removed_state(self):
        body = self.body()
        body["notifications"] = [dict(body["notifications"][0], id=str(i), text="long "*400) for i in range(100)]
        body["calendar"] += [dict(body["calendar"][0], id="past", start_ms=self.now-5000, end_ms=self.now-1000),
                             dict(body["calendar"][0], id="future", start_ms=self.now+9*86400000, end_ms=self.now+10*86400000)]
        self.store.update(self.device, body)
        parts = self.store.for_session("chat", True)
        self.assertLessEqual(len(prompt(parts)), BUDGET)
        self.assertTrue(parts[0]["truncated"])
        self.assertEqual(["event-one"], [e["id"] for e in parts[0]["calendar"]])
        self.assertIn("untrusted", prompt(parts))
        self.assertIn("explicit user request", prompt(parts))

    def test_late_conversation_cannot_extend_the_device_cache_expiry(self):
        self.store.update(self.device, self.body())
        clock = time.time()
        with patch("plugins.bellpull.phone_context.time.time", return_value=clock+86000):
            self.assertTrue(self.store.for_session("late-chat", True))
        with patch("plugins.bellpull.phone_context.time.time", return_value=clock+86401):
            self.assertEqual([], self.store.for_session("late-chat", False))

    def test_disabling_a_source_scrubs_frozen_data_even_with_unchanged_policy_key(self):
        self.store.update(self.device, self.body())
        self.store.for_session("chat", True)
        self.store.update(self.device, self.body(2, notifications_enabled=False, notifications=[]))
        self.assertEqual([], self.store.for_session("chat", False))

    def test_invalid_or_oversized_payloads_never_partially_commit(self):
        for changes in (dict(sequence=True), dict(notifications_enabled="yes"), dict(calendar_enabled=False),
                        dict(notifications=[dict(self.body()["notifications"][0], text="x"*2001)]),
                        dict(notifications=self.body()["notifications"]*2)):
            with self.assertRaises(ArtifactError): self.store.update(self.device, self.body(**changes))
        self.assertEqual([], self.store.search(""))

    def test_native_adapter_scope_and_prompt_tail_and_unload(self):
        from hermes_state import SessionDB
        database = SessionDB()
        database.create_session("new-chat", "api_server")
        database.create_session("old-chat", "api_server")
        database.append_message("old-chat", "user", "Earlier message")
        database.create_session("background", "cron")
        self.store.update(self.device, self.body())
        seen = []
        def create(ephemeral_system_prompt=None, session_id=None):
            seen.append((session_id, ephemeral_system_prompt)); return "native-agent"
        adapter = SimpleNamespace(_create_agent=create, _ensure_session_db=lambda: database)
        supported, stop = wire(adapter)
        self.assertTrue(supported)
        self.assertEqual("native-agent", adapter._create_agent("Original instructions", "new-chat"))
        self.assertTrue(seen[-1][1].startswith("Original instructions"))
        self.assertIn("west gate", seen[-1][1])
        adapter._create_agent("Original instructions", "old-chat")
        self.assertEqual("Original instructions", seen[-1][1])
        adapter._create_agent("Original instructions", "background")
        self.assertEqual("Original instructions", seen[-1][1])
        with patch("plugins.bellpull.phone_context.current_store", side_effect=OSError("disk unavailable")):
            self.assertEqual("native-agent", adapter._create_agent("Original instructions", "new-chat"))
            self.assertIn("context is unavailable", seen[-1][1])
        stop(); self.assertIs(adapter._create_agent, create)
        database.close()

    def test_unknown_host_keeps_its_original_method(self):
        adapter = SimpleNamespace(_create_agent=lambda: None)
        original = adapter._create_agent
        supported, stop = wire(adapter)
        self.assertFalse(supported); stop(); self.assertIs(original, adapter._create_agent)


class PhoneContextHttpTest(unittest.IsolatedAsyncioTestCase):
    async def test_real_profile_auth_upload_and_native_run_prompt_lifecycle(self):
        import asyncio
        from aiohttp import web
        from aiohttp.test_utils import TestClient, TestServer
        from gateway.config import PlatformConfig
        from gateway.platforms.api_server import APIServerAdapter, body_limit_middleware
        from gateway.run import _profile_runtime_scope
        from plugins.bellpull.http import wire as wire_http
        from unittest.mock import AsyncMock
        with tempfile.TemporaryDirectory() as temporary:
            homes = {name: Path(temporary, name) for name in ("alpha", "beta")}
            for name, home in homes.items():
                home.mkdir()
                (home / ".env").write_text("API_SERVER_KEY=isolated-context-"+name+"\n")
                (home / "config.yaml").write_text("{}\n")
            with patch.dict(os.environ, {"HERMES_HOME": temporary}), \
                    patch("hermes_cli.profiles.profiles_to_serve", return_value=list(homes.items())), \
                    patch("hermes_cli.profiles.get_profile_dir", side_effect=homes.__getitem__):
                adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={"key": "isolated-context-root"}))
                adapter.gateway_runner = SimpleNamespace(config=SimpleNamespace(multiplex_profiles=True, multiplex_profile_allowlist=None))
                adapter._conversation_history_for_session = AsyncMock(return_value=[])
                adapter._request_route_conflict_error = lambda **kwargs: None
                seen, executed = [], []
                class Agent:
                    session_id = "fixture"
                    def run_conversation(self, **kwargs):
                        executed.append(kwargs); return {"final_response": "Synthetic response"}
                def create(session_id=None, ephemeral_system_prompt=None, **kwargs):
                    seen.append((session_id, ephemeral_system_prompt)); return Agent()
                adapter._create_agent = create
                app = web.Application(middlewares=[adapter._make_profile_prefix_middleware(), body_limit_middleware])
                for method, path, handler in adapter._http_route_table():
                    app.router.add_route(method, "/p/{profile}" + path, handler)
                wire_http(app, adapter)
                headers = {name: {"Authorization": "Bearer isolated-context-"+name} for name in homes}
                now = int(time.time()*1000)
                body = dict(sequence=1, policy="allow", notifications_enabled=True, notifications=[dict(
                    id="note", app="Messages", title="Rowan", text="Synthetic west gate", posted_at_ms=now, updated_at_ms=now)])
                path = "/api/bellpull/context/"+"a"*32
                async with TestClient(TestServer(app)) as client:
                    self.assertEqual(401, (await client.put("/p/alpha"+path, json=body)).status)
                    self.assertEqual(401, (await client.put("/p/alpha"+path, json=body, headers=headers["beta"])).status)
                    reply = await client.put("/p/alpha"+path, json=body, headers=headers["alpha"])
                    self.assertEqual(200, reply.status, await reply.text())
                    self.assertEqual("no-store", reply.headers["Cache-Control"])
                    self.assertEqual([], executed)  # Upload never creates a model run.
                    sessions = await (await client.get("/p/alpha/api/sessions", headers=headers["alpha"])).json()
                    self.assertEqual([], sessions["data"])
                    self.assertEqual([], ContextStore(homes["beta"]).search(""))
                    async def new_chat(name):
                        response = await client.post(f"/p/{name}/api/sessions", headers=headers[name], json={})
                        self.assertEqual(201, response.status, await response.text())
                        return (await response.json())["session"]["id"]
                    async def run(name, session, key):
                        response = await client.post(f"/p/{name}/v1/runs", headers={**headers[name], "Idempotency-Key": key},
                                                     json={"session_id": session, "input": "Where are we meeting?"})
                        self.assertEqual(202, response.status, await response.text())
                        identity = (await response.json())["run_id"]
                        for _ in range(300):
                            status = await (await client.get(f"/p/{name}/v1/runs/{identity}", headers=headers[name])).json()
                            if status["status"] in ("completed", "failed"):
                                self.assertEqual("completed", status["status"], status); return identity
                            await asyncio.sleep(.01)
                        self.fail("Native run did not settle")
                    alpha = await new_chat("alpha")
                    first = await run("alpha", alpha, "first")
                    self.assertIn("Synthetic west gate", seen[-1][1])
                    frozen = seen[-1][1]
                    self.assertEqual(first, await run("alpha", alpha, "first"))
                    self.assertEqual(1, len(executed))
                    with _profile_runtime_scope(homes["alpha"]):
                        adapter._ensure_session_db().append_message(alpha, "user", "Earlier message")
                    body.update(sequence=2, notifications=[])
                    self.assertEqual(200, (await client.put("/p/alpha"+path, headers=headers["alpha"], json=body)).status)
                    await run("alpha", alpha, "follow-up")
                    self.assertEqual(frozen, seen[-1][1])
                    beta = await new_chat("beta"); await run("beta", beta, "beta-run")
                    self.assertNotIn("Synthetic west gate", seen[-1][1] or "")
                    body.update(sequence=3, policy="revoked", notifications_enabled=False)
                    self.assertEqual(200, (await client.put("/p/alpha"+path, headers=headers["alpha"], json=body)).status)
                    await run("alpha", alpha, "after-revocation")
                    self.assertNotIn("Synthetic west gate", seen[-1][1] or "")
                    await asyncio.gather(*adapter._active_run_tasks.values(), return_exceptions=True)
                adapter._run_idempotency_store.close()
                for database in adapter._session_dbs.values(): database.close()
