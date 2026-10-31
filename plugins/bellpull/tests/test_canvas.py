import asyncio
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import secrets
import tempfile
import unittest
import uuid
from unittest.mock import patch

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from hermes_state import SessionDB
from gateway.config import PlatformConfig
from gateway.platforms.api_server import APIServerAdapter
from plugins.bellpull.canvas_store import CanvasStore, CanvasError
from plugins.bellpull.canvas import capture_call, execute
from plugins.bellpull.http import wire

NODE = {"id": "one", "shape": "rectangle", "label": "A", "x": 0, "y": 0, "width": 180, "height": 100, "color": "rose"}
ADD = {"type": "add-node", "node": NODE}


class CanvasTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.home = Path(self.temp.name)
        self.db = SessionDB(self.home / "state.db")
        self.db.create_session("chat", "cli"); self.db.create_session("other", "cli")
        self.store = CanvasStore(self.home, "a" * 64, self.db)
        self.store.mutate("chat", {"action": "create", "canvas_id": "drawing", "operation_id": "create", "name": "Example"})

    def tearDown(self):
        self.db.close(); self.temp.cleanup()

    def body(self, action="edit", **fields):
        return {"action": action, "canvas_id": "drawing", "operation_id": uuid.uuid4().hex,
                "base_revision": self.store.snapshot("chat")["document"]["revision"], **fields}

    def test_two_devices_share_committed_steps_and_reconnect_without_reexecution(self):
        cursor = self.store.snapshot("chat")["cursor"]
        body = self.body(operations=[ADD, {"type": "update-node", "id": "one", "changes": {"x": 250}}])
        self.store.mutate("chat", body, turn="turn", tool_call="call")
        mobile = CanvasStore(self.home, "a" * 64, self.db)
        events = mobile.events("chat", cursor)
        self.assertEqual([0, 250], [e["snapshot"]["document"]["content"]["nodes"][0]["x"] for e in events])
        self.assertEqual([1, 2], [e["snapshot"]["document"]["revision"] for e in events])
        before = mobile.snapshot("chat")
        self.assertEqual(before, mobile.mutate("chat", body, turn="turn", tool_call="call"))
        self.assertEqual([], mobile.events("chat", before["cursor"]))
        with self.assertRaises(CanvasError): mobile.mutate("chat", {**body, "operations": [ADD]}, turn="turn", tool_call="call")

    def test_model_turn_groups_undo_and_manual_edit_fences_remaining_calls(self):
        self.store.mutate("chat", self.body(operations=[ADD]), turn="turn", tool_call="first")
        self.store.mutate("chat", self.body(operations=[{"type": "update-node", "id": "one", "changes": {"x": 250}}]), turn="turn", tool_call="second")
        self.store.mutate("chat", self.body("undo"))
        self.assertEqual([], self.store.snapshot("chat")["document"]["content"]["nodes"])
        self.store.mutate("chat", self.body("redo"))
        self.assertEqual(250, self.store.snapshot("chat")["document"]["content"]["nodes"][0]["x"])
        with self.assertRaises(CanvasError):
            self.store.mutate("chat", self.body(operations=[{"type": "rename", "name": "Overwrite"}]), turn="turn", tool_call="late")
        self.store.mutate("chat", self.body(operations=[{"type": "rename", "name": "New turn"}]), turn="next", tool_call="first")

    def test_concurrent_edits_have_one_winner_and_no_lost_update(self):
        self.store.mutate("chat", self.body(operations=[ADD]))
        bodies = [self.body(operations=[{"type": "update-node", "id": "one", "changes": {"x": x}}]) for x in (100, 200)]
        def edit(body):
            try: self.store.mutate("chat", body); return "ok"
            except CanvasError as exc: return exc.status
        with ThreadPoolExecutor(2) as pool: self.assertCountEqual(["ok", 409], list(pool.map(edit, bodies)))
        self.assertEqual(2, self.store.snapshot("chat")["document"]["revision"])

    def test_invalid_batches_stop_and_foreign_session_do_not_change_state(self):
        before = self.store.snapshot("chat")
        for operation in ({"type": "execute", "code": "anything"}, {"type": "add-edge", "edge": {}},
                          {"type": "add-node", "node": {**NODE, "width": 0}}):
            with self.assertRaises(CanvasError): self.store.mutate("chat", self.body(operations=[ADD, operation]))
        with self.assertRaises(CanvasError): self.store.mutate("chat", self.body(operations=[ADD]), interrupted=lambda: True)
        with self.assertRaises(CanvasError): self.store.mutate("other", self.body(operations=[ADD]))
        self.assertEqual(before, self.store.snapshot("chat"))
        self.assertEqual([], self.store.snapshot("other")["documents"])
        self.assertEqual([], CanvasStore(self.home, "b" * 64, self.db).snapshot("chat")["documents"])

    def test_compaction_preserves_canvas_but_ordinary_fork_does_not(self):
        self.db.end_session("chat", "compression")
        self.db.create_session("tip", "cli", parent_session_id="chat")
        self.db.create_session("fork", "cli", parent_session_id="chat", model_config={"_branched_from": "chat"})
        self.assertEqual("drawing", self.store.snapshot("tip")["document"]["id"])
        self.assertEqual(["chat", "tip"], self.store.snapshot("tip")["session_ids"])
        self.assertEqual([], self.store.snapshot("fork")["documents"])

    def test_gap_resets_from_authoritative_snapshot(self):
        for i in range(4): self.store.mutate("chat", self.body(operations=[{"type": "rename", "name": str(i)}]))
        with self.store.db() as db: db.execute("DELETE FROM events WHERE seq<4")
        events = self.store.events("chat", 1)
        self.assertEqual("canvas.reset", events[0]["event"])
        self.assertEqual(self.store.snapshot("chat"), events[0]["snapshot"])

    def test_tool_uses_native_identity_and_current_session_only(self):
        with patch.dict(os.environ, {"HERMES_HOME": str(self.home)}):
            args = {"version": 1, "action": "create", "name": "Native tool"}
            self.assertIn("error", execute(args, session_id="chat"))
            capture_call(tool_name="bellpull_canvas", args=args, session_id="chat", turn_id="turn", tool_call_id="call")
            created = execute(args, session_id="chat")
            self.assertEqual("Native tool", created["document"]["name"])
            self.assertEqual(created, execute(args, session_id="chat"))
            self.assertIn("error", execute(args, session_id="other"))
            self.assertEqual(created, execute({"version": 1, "action": "read"}, session_id="chat"))

    def test_real_native_tool_dispatch_supplies_identity_even_when_pre_hook_was_already_called(self):
        from hermes_cli.plugins import PluginManager
        from tools.registry import registry
        from model_tools import handle_function_call
        from plugins.bellpull.canvas import register
        manager = PluginManager()
        class Context:
            def register_middleware(self, name, callback): manager._middleware.setdefault(name, []).append(callback)
            def register_tool(self, **kwargs): registry.register(**kwargs)
        register(Context())
        try:
            with patch.dict(os.environ, {"HERMES_HOME": str(self.home)}), patch("hermes_cli.plugins._delivery_manager", return_value=manager):
                args = {"version": 1, "action": "create", "name": "Dispatched"}
                result = json.loads(handle_function_call("bellpull_canvas", args, session_id="chat", turn_id="real-turn", tool_call_id="real-call", skip_pre_tool_call_hook=True))
                self.assertNotIn("error", result, result)
                self.assertEqual("Dispatched", result["document"]["name"])
                duplicate = json.loads(handle_function_call("bellpull_canvas", args, session_id="chat", turn_id="real-turn", tool_call_id="real-call", skip_pre_tool_call_hook=True))
                self.assertEqual(result, duplicate)
                id = result["document"]["id"]
                for index in range(2):
                    edit = {"version": 1, "action": "edit", "canvas_id": id, "base_revision": index,
                            "operations": [{"type": "rename", "name": "Step " + str(index)}]}
                    result = json.loads(handle_function_call("bellpull_canvas", edit, session_id="chat", turn_id="real-turn",
                        api_request_id="different-model-request-" + str(index), tool_call_id="edit-" + str(index), skip_pre_tool_call_hook=True))
                    self.assertNotIn("error", result, result)
                self.assertEqual(2, len(result["document"]["history"]), "One model turn must be one undo frame across API requests")
                undo = {"version": 1, "action": "undo", "canvas_id": id, "base_revision": 2}
                result = json.loads(handle_function_call("bellpull_canvas", undo, session_id="chat", turn_id="undo-turn",
                    tool_call_id="undo-call", skip_pre_tool_call_hook=True))
                self.assertEqual("Dispatched", result["document"]["name"])
        finally: registry.deregister("bellpull_canvas")


class CanvasHttpTests(unittest.IsolatedAsyncioTestCase):
    async def test_native_auth_two_clients_live_updates_and_reconnect(self):
        with tempfile.TemporaryDirectory() as home, patch.dict(os.environ, {"HERMES_HOME": home, "BELLPULL_NTFY_URL": ""}):
            key = secrets.token_hex(32); headers = {"Authorization": "Bearer " + key}
            adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={"key": key}))
            app = web.Application(); stop = wire(app, adapter)
            client = TestClient(TestServer(app)); await client.start_server()
            stream = None
            try:
                database = await adapter._ensure_session_db_async(); database.create_session("chat", "cli")
                base = "/api/bellpull/sessions/chat/canvas"
                self.assertEqual(401, (await client.get(base)).status)
                self.assertEqual(404, (await client.get(base.replace("chat", "missing"), headers=headers)).status)
                capabilities = await (await client.get("/api/bellpull/capabilities", headers=headers)).json()
                self.assertTrue(capabilities["features"]["bellpull_canvas"])
                stream = await client.get(base + "/events", headers=headers)
                async def event():
                    while True:
                        line = await asyncio.wait_for(stream.content.readline(), 4)
                        if line.startswith(b"data: "): return json.loads(line[6:])
                self.assertEqual("canvas.reset", (await event())["event"])
                body = {"action": "create", "canvas_id": "drawing", "operation_id": "create", "name": "Shared"}
                self.assertEqual(200, (await client.post(base, headers=headers, json=body)).status)
                self.assertEqual("Shared", (await event())["snapshot"]["document"]["name"])
                body = {"action": "edit", "canvas_id": "drawing", "operation_id": "edit", "base_revision": 0, "operations": [ADD]}
                self.assertEqual(200, (await client.post(base, headers=headers, json=body)).status)
                self.assertEqual(1, (await event())["snapshot"]["document"]["revision"])
                self.assertEqual(200, (await client.post(base, headers=headers, json=body)).status)
                stream.close(); stream = await client.get(base + "/events", headers=headers)
                self.assertEqual(1, (await event())["snapshot"]["document"]["revision"])
                response = await client.post(base, headers=headers, json={**body, "operation_id": "stale"})
                self.assertEqual(409, response.status)
            finally:
                if stream: stream.close()
                stop(); await client.close()
                for db in adapter._session_dbs.values(): db.close()
