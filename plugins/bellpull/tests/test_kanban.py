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
from plugins.bellpull.kanban import Boards, native, database_api, EMPTY_REVISION
from plugins.bellpull.artifacts import ArtifactError


class KanbanTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {"HERMES_HOME": self.temp.name, "HERMES_KANBAN_HOME": self.temp.name,
            "HERMES_KANBAN_DB": "", "BELLPULL_DEVICE_NAME": "Fixture desktop", "BELLPULL_NTFY_URL": ""})
        self.env.start()
        self.store = Boards()
        self.kb = native()
        conn = database_api().connect(db_path=self.kb.kanban_db_path(board="default"))
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
        conn = database_api().connect(db_path=self.kb.kanban_db_path(board="default"))
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

    def field_operation(self, action="create"):
        body = self.operation()
        body.pop("comment")
        body.update(action=action, fields=dict(title="Created from mobile", body="Complete description", assignee="work", priority=4))
        if action == "create": body.update(task_id="draft_" + uuid.uuid4().hex, expected_revision=EMPTY_REVISION)
        return body

    def test_concurrent_create_and_restart_receipt_have_one_native_triage_task(self):
        body = self.field_operation()
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(lambda _: Boards().mutate("default", body), range(4)))
        self.assertTrue(all(r == results[0] for r in results))
        result = results[0]
        self.assertEqual("applied", result["state"])
        self.assertEqual("triage", result["task"]["status"])
        self.assertEqual("work", result["task"]["assignee"])
        self.assertEqual(4, result["task"]["priority"])
        self.assertNotEqual(body["task_id"], result["task"]["id"])
        self.assertEqual(2, self.store.snapshot("default")["total"])
        self.assertEqual([], result["task"]["runs"])
        self.assertEqual(result, Boards().receipt("default", body["operation_id"]))

    def test_create_receipt_failure_rolls_back_task_and_retries_once(self):
        body = self.field_operation()
        with self.store.connection("default") as conn:
            conn.execute("CREATE TRIGGER fail_create_receipt BEFORE INSERT ON bellpull_kanban_operations BEGIN SELECT RAISE(ABORT, 'fixture crash'); END")
        with self.assertRaises(Exception): self.store.mutate("default", body)
        self.assertEqual(1, self.store.snapshot("default")["total"])
        self.assertEqual("not_found", self.store.receipt("default", body["operation_id"])["state"])
        with self.store.connection("default") as conn: conn.execute("DROP TRIGGER fail_create_receipt")
        self.assertEqual("applied", self.store.mutate("default", body)["state"])

    def test_field_edit_is_atomic_preserves_lifecycle_and_rejects_stale_or_claimed_work(self):
        body = self.field_operation("edit")
        with self.store.connection("default") as conn:
            conn.execute("UPDATE tasks SET consecutive_failures=3,last_failure_error='old' WHERE id=?", (self.tid,))
        body["expected_revision"] = self.store.detail("default", self.tid)["task"]["revision"]
        result = self.store.mutate("default", body)
        self.assertEqual("applied", result["state"])
        self.assertEqual("triage", result["task"]["status"])
        self.assertEqual("Complete description", result["task"]["body"])
        with self.store.connection("default") as conn:
            self.assertEqual((0, None), tuple(conn.execute("SELECT consecutive_failures,last_failure_error FROM tasks WHERE id=?", (self.tid,)).fetchone()))
        self.assertEqual("conflict", self.store.mutate("default", {**body, "operation_id": uuid.uuid4().hex})["state"])
        with self.store.connection("default") as conn:
            conn.execute("UPDATE tasks SET claim_lock='worker' WHERE id=?", (self.tid,))
        claimed = self.field_operation("edit")
        self.assertEqual("conflict", self.store.mutate("default", claimed)["state"])
        self.assertFalse(self.store.detail("default", self.tid)["task"]["editable"])

    def test_invalid_fields_and_unresolved_project_create_do_not_change_native_data(self):
        body = self.field_operation()
        for field, value in [("title", " "), ("body", "x" * 16385), ("assignee", "../work"), ("priority", True)]:
            with self.assertRaises(ArtifactError): self.store.mutate("default", {**body, "fields": {**body["fields"], field: value}})
        original = self.store.metadata
        with patch.object(self.store, "metadata", side_effect=lambda name: {**original(name), "project_id": "missing-project"}):
            result = self.store.mutate("default", body)
        self.assertEqual("conflict", result["state"])
        self.assertIsNone(result["task"])
        self.assertEqual(1, self.store.snapshot("default")["total"])
        self.assertEqual(result, self.store.receipt("default", body["operation_id"]))

    def test_unicode_limits_and_full_width_native_priority_remain_readable(self):
        fields = dict(title="🔔" * 500, body="🔔" * 16384, assignee=None, priority=0)
        operation = {**self.field_operation(), "fields": fields}
        created = self.store.mutate("default", operation)
        self.assertEqual("applied", created["state"])
        self.assertEqual(fields["title"], created["task"]["title"])
        self.assertEqual(fields["body"], created["task"]["body"])
        with self.store.connection("default") as conn:
            tid = self.kb.create_task(conn, title="Native priority", priority=2**31, triage=True, board="default")
        detail = self.store.detail("default", tid)["task"]
        self.assertEqual(2**31, detail["priority"])
        self.assertFalse(detail["editable"])


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
