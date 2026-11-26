from contextlib import ExitStack, nullcontext
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
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.env = patch.dict(os.environ, {"HERMES_HOME": str(self.home)})
        self.env.start()
        self.now = int(time.time()*1000)
        self.device = "a"*32
        self.store = ContextStore(self.home)

    def tearDown(self):
        self.env.stop()

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

    def test_recent_removal_stays_in_the_bounded_snapshot(self):
        body = self.body()
        original = body["notifications"][0]
        body["notifications"] = [dict(original, id=str(i), updated_at_ms=self.now - i - 1) for i in range(12)]
        body["notifications"].append(dict(original, id="just-removed", updated_at_ms=self.now - 1000,
                                          removed_at_ms=self.now))
        self.store.update(self.device, body)
        for part in (self.store.for_session("new-chat", True)[0], self.store.search("")[0]):
            self.assertTrue(part["truncated"])
            self.assertEqual(12, len(part["notifications"]))
            latest = part["notifications"][0]
            self.assertEqual(("just-removed", self.now), (latest["id"], latest["removed_at_ms"]))

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

    def test_native_conversation_store_preserves_compression_and_isolates_other_sessions(self):
        try:
            from persistence.conversation.messages import NewMessage
            from persistence.conversation.sessions import NewSession, SessionLineageKind
            from persistence_assembly import open_persistence, profile_scope
        except ImportError:
            self.skipTest("Host uses the legacy session database")
        database = open_persistence(profile_scope(home=self.home))
        self.addCleanup(database.close)
        def create(session_id, ephemeral_system_prompt="", room_dispatch=None):
            return ephemeral_system_prompt
        adapter = SimpleNamespace(_create_agent=create, _ensure_store=lambda: database,
                                  _store_beside=lambda db: db, _room_grant_token=lambda request: "")
        self.store.update(self.device, self.body())
        supported, stop = wire(adapter)
        self.assertTrue(supported)
        self.addCleanup(stop)
        database.sessions.create(NewSession("original", "api_server"))
        first = adapter._create_agent("original")
        self.assertIn("west gate", first)
        database.messages.append("original", NewMessage("user", "First turn"))
        database.sessions.end("original", "compression")
        database.sessions.create(NewSession("continuation", "api_server", parent_session_id="original"))
        self.store.update(self.device, self.body(2, notifications=[]))
        self.assertEqual(first, adapter._create_agent("continuation"))
        database.sessions.create(NewSession("fork", "api_server", parent_session_id="original",
                                            lineage_kind=SessionLineageKind.BRANCH))
        self.assertNotIn("west gate", adapter._create_agent("fork"))
        for session, source in (("personal", "api_server"), ("room", "bot_room"), ("job", "cron")):
            database.sessions.create(NewSession(session, source))
        database.messages.append("personal", NewMessage("assistant", "Existing transcript"))
        for session in ("personal", "room", "job", "unknown"):
            self.assertEqual("original prompt", adapter._create_agent(session, "original prompt"))
        self.assertEqual("original prompt", adapter._create_agent("original", "original prompt", room_dispatch={}))
        adapter._ensure_store = lambda: None
        self.assertIn("context is unavailable", adapter._create_agent("original"))
        stop()
        self.assertIs(create, adapter._create_agent)

    def test_unknown_host_keeps_its_original_method(self):
        adapter = SimpleNamespace(_create_agent=lambda: None)
        original = adapter._create_agent
        supported, stop = wire(adapter)
        self.assertFalse(supported); stop(); self.assertIs(original, adapter._create_agent)


class AdmittedContextTest(unittest.IsolatedAsyncioTestCase):
    async def test_snapshot_lifetime_and_admission_identity(self):
        try:
            from gateway.session_api_turn import api_execution
        except ImportError:
            self.skipTest("This host runs directly on the API adapter.")
        from unittest.mock import Mock
        from plugins.bellpull.phone_context import _wire_admitted

        source = object()
        receipt = {"admission_id": "a", "execution_generation": 2}
        live = SimpleNamespace(source=source, event_stream=SimpleNamespace(lock=nullcontext(), execution=receipt))
        authority = SimpleNamespace(sessions={"original": live})
        prompts = []

        async def handle(event):
            prompts.append(api_execution.get()["settings"]["ephemeral_system_prompt"])
            if event.message_id == "failure":
                raise RuntimeError("worker stopped")
            return "reply"

        adapter = SimpleNamespace(gateway_runner=SimpleNamespace(_handle_message=handle))
        settings = {"ephemeral_system_prompt": "request"}
        original = {"adapter": adapter, "settings": settings}
        row = SimpleNamespace(generation=2, principal_id="api", status="started", target_session_id="original",
                              payload={"api_turn_v1": {"run_owner_scope": "owner", "settings": settings}})
        store = Mock()
        store.admissions.get.return_value = row
        snapshot = Mock(return_value=["snapshot"])
        with (patch("gateway.session_authorities.active_authority", return_value=authority),
              patch("gateway.session_persistence.authority_persistence", return_value=store),
              patch("plugins.bellpull.phone_context.prompt", return_value=" snapshot")):
            stop = _wire_admitted(adapter, snapshot, ())
            self.addCleanup(stop)
            token = api_execution.set(original)
            try:
                dispatch = adapter.gateway_runner._handle_message
                self.assertEqual("reply", await dispatch(SimpleNamespace(source=source, message_id="a")))
                snapshot.assert_called_once_with("original")
                self.assertEqual("request snapshot", prompts[-1])
                receipt["admission_id"] = "failure"
                with self.assertRaisesRegex(RuntimeError, "worker stopped"):
                    await dispatch(SimpleNamespace(source=source, message_id="failure"))
                self.assertIs(original, api_execution.get())
                self.assertEqual({"ephemeral_system_prompt": "request"}, settings)

                snapshot.reset_mock()
                await dispatch(SimpleNamespace(source=object(), message_id="a"))
                await dispatch(SimpleNamespace(source=source, message_id="stale"))
                settings["room_dispatch"] = {}
                await dispatch(SimpleNamespace(source=source, message_id="room"))
                settings.pop("room_dispatch")
                row.payload["api_turn_v1"]["run_owner_scope"] = None
                await dispatch(SimpleNamespace(source=source, message_id="other"))
                store.admissions.get.return_value = None
                await dispatch(SimpleNamespace(source=source, message_id="missing"))
                self.assertEqual(["request"] * 5, prompts[-5:])
                snapshot.assert_not_called()
                stop()
                self.assertIs(handle, adapter.gateway_runner._handle_message)
            finally:
                api_execution.reset(token)


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
        with ExitStack() as cleanup:
            temporary = cleanup.enter_context(tempfile.TemporaryDirectory())
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
                uses_persistence = callable(getattr(adapter, "_ensure_store", None))
                if uses_persistence:
                    from gateway.session_authorities import SessionAuthorities
                    from persistence_assembly import open_persistence, profile_scope
                    authorities = SessionAuthorities(homes["alpha"], multiplexed=True)
                    for name, home in homes.items():
                        store = open_persistence(profile_scope(home=home))
                        cleanup.callback(store.close)
                        authorities.add(home, SimpleNamespace(persistence=store), name=name)
                    adapter.gateway_runner.session_authorities = authorities
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
                    response = await client.get("/p/alpha/api/bellpull/capabilities", headers=headers["alpha"])
                    self.assertTrue((await response.json())["features"]["bellpull_phone_context"])
                    reply = await client.put("/p/alpha"+path, json=body, headers=headers["alpha"])
                    self.assertEqual(200, reply.status, await reply.text())
                    self.assertEqual("no-store", reply.headers["Cache-Control"])
                    self.assertEqual([], executed)  # Upload never creates a model run.
                    session_list = await client.get("/p/alpha/api/sessions", headers=headers["alpha"])
                    self.assertEqual(200, session_list.status, await session_list.text())
                    sessions = await session_list.json()
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
                        if uses_persistence:
                            from persistence.conversation.messages import NewMessage
                            store = adapter._store_beside(adapter._ensure_store())
                            store.messages.append(alpha, NewMessage("user", "Earlier message"))
                        else:
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
                if not uses_persistence:
                    for database in adapter._session_dbs.values(): database.close()
