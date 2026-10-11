"""Real SQLite/SessionDB folder persistence and native authenticated HTTP behavior."""
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
from plugins.bellpull.http import wire
from plugins.bellpull.projects import ProjectStore, ProjectError


class ProjectTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.home = Path(self.temp.name)
        self.native = SessionDB(self.home / "state.db")
        self.native.create_session("root", "cli")
        self.native.append_message("root", "user", "Keep my history")
        self.store = ProjectStore(self.home, "a" * 64, self.native)
        self.one, self.two = "1" * 32, "2" * 32

    def tearDown(self):
        self.native.close()
        self.temp.cleanup()

    def change(self, operation, **fields):
        body = {"operation": operation, "operation_id": uuid.uuid4().hex,
                "expected_revision": self.store.snapshot()["revision"], **fields}
        self.store.mutate(body)
        return body

    def create(self, project=None, name="Costumes"):
        return self.change("create", project_id=project or self.one, name=name,
                           description="Finish WALL·E and EVE", color="#59091e", icon="craft")

    def member(self, session):
        return next(row for row in self.store.snapshot()["memberships"] if session in row["session_ids"])

    def compress(self, parent, child, config=None):
        self.native.end_session(parent, "compression")
        self.native.create_session(child, "cli", parent_session_id=parent, model_config=config)
        self.native.append_message(child, "assistant", "Compacted summary")

    def test_create_rename_and_reopen_preserve_display_fields(self):
        self.create()
        self.change("update", project_id=self.one, name="Halloween", description="Ready by October 31",
                    color=None, icon="star", archived=False)
        reopened = ProjectStore(self.home, "a" * 64, self.native).snapshot()
        self.assertEqual("Halloween", reopened["projects"][0]["name"])
        self.assertEqual("Ready by October 31", reopened["projects"][0]["description"])
        self.assertIsNone(reopened["projects"][0]["color"])
        self.assertFalse(reopened["projects"][0]["archived"])
        self.assertEqual("star", reopened["projects"][0]["icon"])

    def test_move_pin_delete_and_retry_never_delete_the_native_conversation(self):
        self.create()
        self.create(self.two, "Reference")
        self.change("move", session_id="root", project_id=self.one)
        self.change("pin", session_id="root", pinned=True)
        self.assertTrue(self.member("root")["pinned"])
        move = self.change("move", session_id="root", project_id=self.two)
        self.store.mutate(move)  # Lost response, identical request.
        self.assertFalse(self.member("root")["pinned"])
        self.assertEqual(self.two, self.member("root")["project_id"])
        self.change("delete", project_id=self.two)
        self.assertIsNone(self.member("root")["project_id"])
        self.assertEqual("Keep my history", self.native.get_messages("root")[0]["content"])

    def test_revisions_and_operation_fingerprints_prevent_lost_updates(self):
        original = self.create()
        self.store.mutate(original)
        self.assertEqual(1, self.store.snapshot()["revision"])
        with self.assertRaises(ProjectError) as error:
            self.store.mutate({**original, "name": "Changed retry"})
        self.assertEqual(409, error.exception.status)
        with self.assertRaises(ProjectError) as error:
            self.store.mutate({"operation": "delete", "project_id": self.one,
                "operation_id": uuid.uuid4().hex, "expected_revision": 0})
        self.assertEqual("project_revision_conflict", error.exception.code)
        self.assertEqual(1, len(self.store.snapshot()["projects"]))

    def test_compaction_multiple_generations_preserve_membership_and_pins(self):
        self.create()
        self.create(self.two, "Reference")
        self.change("move", session_id="root", project_id=self.one)
        self.change("pin", session_id="root", pinned=True)
        self.compress("root", "middle")
        self.compress("middle", "tip")
        self.assertEqual({"root", "middle", "tip"}, set(self.member("tip")["session_ids"]))
        self.assertTrue(self.member("tip")["pinned"])
        self.change("move", session_id="tip", project_id=self.two)
        self.assertEqual(self.two, self.member("root")["project_id"])
        self.change("move", session_id="root", project_id=None)
        self.assertIsNone(self.member("tip")["project_id"])
        self.assertEqual(1, len(self.store.snapshot()["memberships"]))

    def test_branch_inherits_once_is_not_pinned_and_can_be_moved_independently(self):
        self.create()
        self.change("move", session_id="root", project_id=self.one)
        self.change("pin", session_id="root", pinned=True)
        self.native.create_session("branch", "cli", parent_session_id="root", model_config={"_branched_from": "root"})
        self.assertEqual(self.one, self.member("branch")["project_id"])
        self.assertFalse(self.member("branch")["pinned"])
        self.change("move", session_id="branch", project_id=None)
        self.store.inherit("root", "branch")
        self.compress("branch", "branch_tip", {"_branched_from": "root"})
        self.assertIsNone(self.member("branch_tip")["project_id"])
        self.assertEqual(self.one, self.member("root")["project_id"])
        self.assertTrue(self.member("root")["pinned"])

    def test_move_during_continuation_transition_keeps_latest_single_membership(self):
        self.create()
        self.create(self.two, "Reference")
        self.change("move", session_id="root", project_id=self.one)
        self.native.create_session("tip", "cli", parent_session_id="root")
        self.change("move", session_id="tip", project_id=self.two)
        self.native.end_session("root", "compression")
        self.assertEqual(self.two, self.member("tip")["project_id"])
        self.assertEqual(1, len(self.store.snapshot()["memberships"]))
        self.native.create_session("new_tip", "cli", parent_session_id="tip")
        self.change("move", session_id="new_tip", project_id=None)
        self.native.end_session("tip", "compression")
        self.assertIsNone(self.member("root")["project_id"])
        self.assertEqual(1, len(self.store.snapshot()["memberships"]))

    def test_plain_children_delegates_and_separate_profiles_do_not_inherit(self):
        self.create()
        self.change("move", session_id="root", project_id=self.one)
        for child, config in (("ordinary", {}), ("delegate", {"_delegate_from": "root"}),
                              ("reset", {"_reset_from": "root"})):
            self.native.create_session(child, "cli", parent_session_id="root", model_config=config)
        self.assertEqual(["root"], [row["session_id"] for row in self.store.snapshot()["memberships"]])
        self.assertEqual([], ProjectStore(self.home, "b" * 64, self.native).snapshot()["projects"])
        other = self.home / "work"
        other.mkdir()
        db = SessionDB(other / "state.db")
        try:
            db.create_session("root", "cli")
            self.assertEqual([], ProjectStore(other, "a" * 64, db).snapshot()["memberships"])
        finally:
            db.close()

    def test_archive_retains_membership_but_rejects_new_members_until_restored(self):
        self.create()
        self.change("move", session_id="root", project_id=self.one)
        self.change("update", project_id=self.one, name="Costumes", archived=True)
        self.assertEqual(self.one, self.member("root")["project_id"])
        self.native.create_session("other", "api_server")
        with self.assertRaises(ProjectError):
            self.change("move", session_id="other", project_id=self.one)
        with self.assertRaises(ProjectError):
            self.store.reserve_creation(self.one, {"id": "new", "expected_revision": self.store.snapshot()["revision"]})
        self.change("update", project_id=self.one, name="Costumes", archived=False)
        self.change("move", session_id="other", project_id=self.one)
        self.assertEqual(self.one, self.member("other")["project_id"])

    def test_creation_intent_survives_interruption_without_duplicate_chat(self):
        self.create()
        body = {"id": "new_chat", "expected_revision": self.store.snapshot()["revision"], "title": "New chat"}
        self.store.reserve_creation(self.one, body)
        self.assertEqual([], self.store.snapshot()["memberships"])
        self.store = ProjectStore(self.home, "a" * 64, self.native)
        self.store.reserve_creation(self.one, body)
        self.native.create_session("new_chat", "api_server")
        self.store.reserve_creation(self.one, body)
        self.assertEqual(self.one, self.member("new_chat")["project_id"])
        with self.assertRaises(ProjectError):
            self.store.reserve_creation(self.one, {**body, "title": "Changed"})
        self.assertEqual(1, len([row for row in self.store.snapshot()["memberships"] if row["session_id"] == "new_chat"]))

    def test_pending_creation_cannot_create_into_deleted_or_archived_project(self):
        self.create()
        body = {"id": "pending_chat", "expected_revision": self.store.snapshot()["revision"]}
        self.store.reserve_creation(self.one, body)
        self.change("update", project_id=self.one, name="Costumes", archived=True)
        with self.assertRaises(ProjectError):
            self.store.reserve_creation(self.one, body)
        self.change("update", project_id=self.one, name="Costumes", archived=False)
        self.store.reserve_creation(self.one, body)
        self.change("delete", project_id=self.one)
        with self.assertRaises(ProjectError):
            self.store.reserve_creation(self.one, body)
        self.assertIsNone(self.native.get_session("pending_chat"))

    def test_invalid_fields_and_unknown_sessions_fail_without_mutation(self):
        for field, value in (("name", ""), ("name", "bad\x00name"), ("description", "x" * 1001),
                             ("color", "../red"), ("icon", "unknown")):
            with self.assertRaises(ProjectError):
                self.change("create", **{"project_id": self.one, "name": "Valid", field: value})
        self.assertEqual([], self.store.snapshot()["projects"])
        self.create()
        with self.assertRaises(ProjectError):
            self.change("move", session_id="missing", project_id=self.one)


class ProjectHttpTests(unittest.IsolatedAsyncioTestCase):
    async def test_primary_workflow_survives_adapter_restart_compaction_and_continuation(self):
        with tempfile.TemporaryDirectory() as home, patch.dict(os.environ, {"HERMES_HOME": home, "BELLPULL_NTFY_URL": ""}):
            key = secrets.token_hex(32)
            headers = {"Authorization": "Bearer " + key}
            project_id = "c" * 32
            async def open_adapter():
                adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={"key": key}))
                app = web.Application()
                app.router.add_post("/api/sessions/{session_id}/fork", adapter._handle_fork_session)
                wire(app, adapter)
                client = TestClient(TestServer(app))
                await client.start_server()
                return adapter, client
            async def close_adapter(adapter, client):
                await client.close()
                for database in adapter._session_dbs.values(): database.close()
                run_store = getattr(adapter, "_run_idempotency_store", None)
                if run_store is not None: run_store.close()
            adapter, client = await open_adapter()
            path = "/api/bellpull/projects"
            try:
                database = await adapter._ensure_session_db_async()
                for session in ("root", "second"):
                    database.create_session(session, "cli")
                    database.append_message(session, "user", "Archived prompt")
                    database.append_message(session, "assistant", "Reference answer")
                async def change(operation, **fields):
                    snapshot = await (await client.get(path, headers=headers)).json()
                    response = await client.post(path, headers=headers, json={"operation": operation,
                        "operation_id": uuid.uuid4().hex, "expected_revision": snapshot["revision"], **fields})
                    self.assertEqual(200, response.status, await response.text())
                    return await response.json()
                await change("create", project_id=project_id, name="Costumes", description="Before October 31")
                for session in ("root", "second"):
                    await change("move", session_id=session, project_id=project_id)
                snapshot = await change("pin", session_id="root", pinned=True)
                response = await client.post(path + f"/{project_id}/sessions", headers=headers,
                    json={"id": "project_chat", "expected_revision": snapshot["revision"]})
                self.assertEqual(201, response.status, await response.text())
                capabilities = await (await client.get("/api/bellpull/capabilities", headers=headers)).json()
                if capabilities["features"]["session_fork_at_message"]:
                    # Exercise the actual Bellpull selected-message route on supported
                    # pinned adapters; old native full forks have no branch marker.
                    cutoff = database.get_messages("root")[-1]["id"]
                    response = await client.post("/api/sessions/root/fork-at-message", headers=headers,
                        json={"id": "branch", "through_message_id": cutoff})
                else:
                    # Current stock native forks record explicit branch identity.
                    response = await client.post("/api/sessions/root/fork", headers=headers, json={"id": "branch"})
                self.assertEqual(201, response.status, await response.text())
                # Both in-place compaction and continuation identity changes are real DB operations.
                database.archive_and_compact("second", [{"role": "assistant", "content": "Summary"}])
                database.end_session("second", "compression")
                database.create_session("tip", "cli", parent_session_id="second")
                database.append_message("tip", "assistant", "Final answer")
                snapshot = await (await client.get(path, headers=headers)).json()
                before = {row["session_id"]: row for row in snapshot["memberships"]}
                self.assertEqual({"root", "second", "project_chat", "branch"}, set(before))
                self.assertTrue(before["root"]["pinned"])
                self.assertFalse(before["branch"]["pinned"])
                self.assertEqual({"second", "tip"}, set(before["second"]["session_ids"]))
            finally:
                await close_adapter(adapter, client)
            adapter, client = await open_adapter()
            try:
                snapshot = await (await client.get(path, headers=headers)).json()
                self.assertEqual("Before October 31", snapshot["projects"][0]["description"])
                after = {row["session_id"]: row for row in snapshot["memberships"]}
                self.assertEqual(before, after)
                self.assertEqual({project_id}, {row["project_id"] for row in after.values()})
            finally:
                await close_adapter(adapter, client)

    async def test_native_auth_creation_retry_and_project_snapshot(self):
        with tempfile.TemporaryDirectory() as home, patch.dict(os.environ, {"HERMES_HOME": home, "BELLPULL_NTFY_URL": ""}):
            key = secrets.token_hex(32)
            headers = {"Authorization": "Bearer " + key}
            adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={"key": key}))
            native_create = adapter._handle_create_session
            creates = []
            async def counted_create(request):
                creates.append(1)
                return await native_create(request)
            adapter._handle_create_session = counted_create
            app = web.Application()
            wire(app, adapter)
            try:
                async with TestClient(TestServer(app)) as client:
                    path = "/api/bellpull/projects"
                    self.assertEqual(401, (await client.get(path)).status)
                    response = await client.get("/api/bellpull/capabilities", headers=headers)
                    self.assertTrue((await response.json())["features"]["bellpull_projects"])
                    project_id = "a" * 32
                    response = await client.post(path, headers=headers, json={"operation": "create", "operation_id": uuid.uuid4().hex,
                        "expected_revision": 0, "project_id": project_id, "name": "Work"})
                    self.assertEqual(200, response.status, await response.text())
                    revision = (await response.json())["revision"]
                    body = {"id": "new_chat", "expected_revision": revision, "title": "Planning"}
                    for _ in range(2):
                        response = await client.post(path + f"/{project_id}/sessions", headers=headers, json=body)
                        self.assertIn(response.status, (200, 201), await response.text())
                        self.assertEqual("new_chat", (await response.json())["session"]["id"])
                    self.assertEqual(1, len(creates))
                    response = await client.get(path, headers=headers)
                    self.assertEqual("no-store", response.headers["Cache-Control"])
                    snapshot = await response.json()
                    self.assertEqual(project_id, snapshot["memberships"][0]["project_id"])
                    self.assertEqual("Planning", adapter._ensure_session_db().get_session("new_chat")["title"])
            finally:
                for database in adapter._session_dbs.values():
                    database.close()
