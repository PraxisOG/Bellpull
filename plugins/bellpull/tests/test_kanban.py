import concurrent.futures
import os
from pathlib import Path
import secrets
import tempfile
import unittest
import uuid
from unittest.mock import patch

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from gateway.config import PlatformConfig
from gateway.platforms.api_server import APIServerAdapter
from plugins.bellpull.http import wire
from plugins.bellpull.kanban import Boards, native
from plugins.bellpull.artifacts import ArtifactError


class KanbanTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {"HERMES_HOME": self.temp.name, "HERMES_KANBAN_HOME": self.temp.name,
            "HERMES_KANBAN_DB": "", "BELLPULL_DEVICE_NAME": "Fixture desktop", "BELLPULL_NTFY_URL": ""})
        self.env.start()
        self.store = Boards()
        self.kb = native()
        conn = self.kb.connect(board="default")
        try:
            self.tid = self.kb.create_task(conn, title="Ship the phone board", body="Keep existing work", triage=True, board="default")
        finally:
            conn.close()

    def tearDown(self):
        self.env.stop()
        self.temp.cleanup()

    def operation(self, **values):
        snapshot = self.store.snapshot("default")
        return dict(operation_id=uuid.uuid4().hex, owner_id=snapshot["board"]["owner_id"],
                    board_id=snapshot["board"]["id"], task_id=self.tid,
                    expected_revision=snapshot["tasks"][0]["revision"], action="comment", comment="From the phone", **values)

    def test_native_snapshot_and_details_preserve_installation_and_board_identity(self):
        snapshot = self.store.snapshot("default")
        self.assertEqual("Fixture desktop", snapshot["board"]["owner_name"])
        self.assertEqual("installation", snapshot["board"]["scope"])
        self.assertEqual(["blocked"], snapshot["tasks"][0]["allowed_moves"])
        self.assertEqual(snapshot["board"], Boards().snapshot("default")["board"])
        self.kb.create_board("second", name="Second board")
        other = self.store.snapshot("second")
        self.assertEqual(snapshot["board"]["owner_id"], other["board"]["owner_id"])
        self.assertNotEqual(snapshot["board"]["id"], other["board"]["id"])
        self.assertEqual([], other["tasks"])
        self.assertEqual(self.tid, self.store.detail("default", self.tid)["task"]["id"])

    def test_lost_response_restart_and_concurrent_retry_create_one_native_comment(self):
        body = self.operation()
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            receipts = list(pool.map(lambda _: Boards().mutate("default", body), range(4)))
        self.assertTrue(all(r == receipts[0] for r in receipts))
        self.assertEqual("applied", receipts[0]["state"])
        self.assertEqual(receipts[0], Boards().receipt("default", body["operation_id"]))
        self.assertEqual(1, len(self.store.detail("default", self.tid)["task"]["comments"]))
        with self.assertRaises(ArtifactError):
            self.store.mutate("default", {**body, "comment": "Different retry"})

    def test_native_concurrent_edit_rejects_stale_request_and_persists_receipt(self):
        body = self.operation()
        conn = self.kb.connect(board="default")
        try: self.kb.add_comment(conn, self.tid, "Desktop", "Changed independently")
        finally: conn.close()
        result = self.store.mutate("default", body)
        self.assertEqual("conflict", result["state"])
        self.assertEqual(result, self.store.receipt("default", body["operation_id"]))
        self.assertEqual(1, len(result["task"]["comments"]))

    def test_atomic_receipt_failure_rolls_back_native_comment(self):
        body = self.operation()
        with self.store.connection("default") as conn:
            conn.execute("CREATE TRIGGER fail_receipt BEFORE INSERT ON bellpull_kanban_operations BEGIN SELECT RAISE(ABORT, 'fixture crash'); END")
        with self.assertRaises(Exception): self.store.mutate("default", body)
        self.assertEqual([], self.store.detail("default", self.tid)["task"]["comments"])
        self.assertEqual("not_found", self.store.receipt("default", body["operation_id"])["state"])
        with self.store.connection("default") as conn: conn.execute("DROP TRIGGER fail_receipt")
        self.assertEqual("applied", self.store.mutate("default", body)["state"])

    def test_nonexecuting_move_and_rejected_execution_transitions(self):
        body = self.operation()
        body.pop("comment")
        body.update(action="move", status="blocked")
        result = self.store.mutate("default", body)
        self.assertEqual("blocked", result["task"]["status"])
        self.assertEqual([], result["task"]["runs"])
        for status in ("ready", "running", "done", "archived", "review"):
            request = self.operation()
            request.pop("comment"); request.update(action="move", status=status)
            self.assertEqual("conflict", self.store.mutate("default", request)["state"])
        self.assertEqual("blocked", self.store.detail("default", self.tid)["task"]["status"])

    def test_active_claim_and_replaced_board_are_never_mutated(self):
        body = self.operation()
        with self.store.connection("default") as conn:
            conn.execute("UPDATE tasks SET status='running',claim_lock='fixture-executor' WHERE id=?", (self.tid,))
        self.assertEqual([], self.store.snapshot("default")["tasks"][0]["allowed_moves"])
        with self.store.connection("default") as conn:
            conn.execute("UPDATE bellpull_kanban_meta SET value=? WHERE key='board_id'", (uuid.uuid4().hex,))
        with self.assertRaises(ArtifactError): self.store.mutate("default", body)

    def test_bounded_snapshot_and_path_rejection(self):
        with patch("plugins.bellpull.kanban.LIMIT", 0):
            snapshot = self.store.snapshot("default")
            self.assertFalse(snapshot["complete"]); self.assertEqual(1, snapshot["total"])
        for name in ("../default", "ROOT", "", "default/other"):
            with self.assertRaises(ArtifactError): self.store.snapshot(name)
        with patch.dict(os.environ, {"HERMES_KANBAN_DB": "/tmp/worker.sqlite"}):
            with self.assertRaises(ArtifactError): self.store.snapshot("default")


class KanbanHttpTests(unittest.IsolatedAsyncioTestCase):
    async def test_gateway_auth_profile_prefix_discovery_and_no_inference(self):
        with tempfile.TemporaryDirectory() as home, patch.dict(os.environ, {"HERMES_HOME": home,
                "HERMES_KANBAN_HOME": home, "HERMES_KANBAN_DB": "", "BELLPULL_NTFY_URL": ""}):
            key = secrets.token_hex(32)
            adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={"key": key}))
            adapter._create_agent = lambda **kwargs: self.fail("Board access must not run inference")
            app = web.Application()
            wire(app, adapter)
            client = TestClient(TestServer(app)); await client.start_server()
            try:
                path = "/api/bellpull/kanban/boards"
                self.assertEqual(401, (await client.get(path)).status)
                headers = {"Authorization": "Bearer " + key}
                caps = await (await client.get("/api/bellpull/capabilities", headers=headers)).json()
                self.assertTrue(caps["features"]["bellpull_kanban"])
                for prefix in ("", "/p/work"):
                    response = await client.get(prefix + path, headers=headers)
                    self.assertEqual(200, response.status, await response.text())
                    self.assertEqual("default", (await response.json())["boards"][0]["slug"])
            finally:
                await client.close()
                for database in adapter._session_dbs.values(): database.close()
                adapter._run_idempotency_store.close()
