"""Run only against disposable databases inside the isolated test container."""
import asyncio
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import AsyncMock, patch

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from gateway.platforms.api_server import APIServerAdapter, _STATIC_FEATURE_FLAGS
from hermes_state import SessionDB
from plugins.bellpull import register
from hermes_cli.plugins import PluginContext, PluginManifest, PluginManager


class SelectedReplyForkTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="hermes-fork-test-")
        self.home_patch = patch.dict(os.environ, {"HERMES_HOME": self.temp.name})
        self.home_patch.start()
        self.db = SessionDB(Path(self.temp.name) / "state.db")
        self.db.create_session("source", "api_server", model="test-model",
            system_prompt="Keep this system prompt.",
            model_config={"browser_model_lock": {"model": "test-model", "confirmed": True}})
        self.db.set_session_title("source", "Original conversation")
        self.user = self.db.append_message("source", "user", "First question")
        self.answer = self.db.append_message("source", "assistant", "First answer 👋")
        self.later = self.db.append_message("source", "user", "Later question")
        self.db.append_message("source", "assistant", "Later answer — exclude me")
        self.before = self.db.get_session("source")
        self.before_messages = self.db.get_messages("source")
        self.adapter = APIServerAdapter.__new__(APIServerAdapter)
        self.adapter._api_key = "disposable-test-key"
        self.adapter._ensure_session_db_async = AsyncMock(return_value=self.db)
        app = web.Application()
        app.router.add_post("/api/sessions/{session_id}/fork", self.adapter._handle_fork_session)
        manager = PluginManager()
        register(PluginContext(PluginManifest(name="bellpull"), manager))
        for factory, _name in manager.get_platform_handler_factories("api_server"):
            factory(app, self.adapter)
        self.client = TestClient(TestServer(app))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        self.db.close()
        self.temp.cleanup()
        self.home_patch.stop()

    async def test_branch_inherits_project_and_can_be_unfiled_independently(self):
        import uuid
        headers = {"Authorization": "Bearer disposable-test-key"}
        async def change(operation, **fields):
            snapshot = await (await self.client.get("/api/bellpull/projects", headers=headers)).json()
            response = await self.client.post("/api/bellpull/projects", headers=headers, json={"operation": operation,
                "operation_id": uuid.uuid4().hex, "expected_revision": snapshot["revision"], **fields})
            self.assertEqual(200, response.status, await response.text())
            return await response.json()
        project = "a" * 32
        await change("create", project_id=project, name="Reference")
        await change("move", session_id="source", project_id=project)
        response = await self.fork(self.answer)
        self.assertEqual(201, response.status, await response.text())
        snapshot = await (await self.client.get("/api/bellpull/projects", headers=headers)).json()
        self.assertEqual({project}, {row["project_id"] for row in snapshot["memberships"]})
        self.assertEqual({"source", "branch"}, {row["session_id"] for row in snapshot["memberships"]})
        snapshot = await change("move", session_id="branch", project_id=None)
        members = {row["session_id"]: row["project_id"] for row in snapshot["memberships"]}
        self.assertEqual(project, members["source"])
        self.assertIsNone(members["branch"])

    async def fork(self, cutoff=None, *, body=None, session="source", legacy=False):
        suffix = "fork" if legacy else "fork-at-message"
        return await self.client.post(f"/api/sessions/{session}/{suffix}",
            headers={"Authorization": "Bearer disposable-test-key"},
            json=body if body is not None else {"id": "branch", "through_message_id": cutoff})

    async def before_message(self, message_id, auth=True):
        return await self.client.post("/api/sessions/source/fork-before-message",
            headers={"Authorization": "Bearer disposable-test-key"} if auth else {},
            json={"id": "branch", "before_message_id": message_id})

    async def test_edit_first_prompt_clones_empty_session_and_model(self):
        response = await self.before_message(self.user)
        self.assertEqual(201, response.status, await response.text())
        self.assertEqual([], self.db.get_messages("branch"))
        branch = self.db.get_session("branch")
        self.assertEqual(self.before["model"], branch["model"])
        self.assertEqual(self.before["system_prompt"], branch["system_prompt"])
        self.assertIn("browser_model_lock", branch["model_config"])
        self.assert_source_unchanged()

    async def test_edit_later_prompt_retains_only_prior_path(self):
        response = await self.before_message(self.later)
        self.assertEqual(201, response.status, await response.text())
        self.assertEqual(["First question", "First answer 👋"],
            [m["content"] for m in self.db.get_messages("branch")])
        self.assert_source_unchanged()

    async def test_edit_rejects_assistant_and_invalid_ids(self):
        for cutoff in [True, None, "2", 0, -1, 999999, self.answer]:
            response = await self.before_message(cutoff)
            self.assertEqual(400, response.status)
            self.assertIsNone(self.db.get_session("branch"))
            self.assert_source_unchanged()

    async def test_edit_requires_authentication(self):
        response = await self.before_message(self.user, auth=False)
        self.assertEqual(401, response.status)
        self.assertIsNone(self.db.get_session("branch"))

    def assert_source_unchanged(self):
        self.assertEqual(self.before, self.db.get_session("source"))
        self.assertEqual(self.before_messages, self.db.get_messages("source"))

    async def test_exact_prefix_preserves_original_model_and_visible_lineage(self):
        response = await self.fork(self.answer)
        self.assertEqual(201, response.status, await response.text())
        payload = await response.json()
        self.assertEqual("branch", payload["session"]["id"])
        self.assertEqual(2, payload["session"]["message_count"])
        self.assertTrue(payload["session"]["has_system_prompt"])
        self.assert_source_unchanged()
        branch = self.db.get_session("branch")
        self.assertEqual("source", branch["parent_session_id"])
        self.assertEqual(self.before["model"], branch["model"])
        self.assertEqual(self.before["system_prompt"], branch["system_prompt"])
        config = json.loads(branch["model_config"])
        self.assertEqual("source", config["_branched_from"])
        self.assertEqual(self.answer, config["_branched_at_message_id"])
        self.assertTrue(config["browser_model_lock"]["confirmed"])
        self.assertEqual(["First question", "First answer 👋"],
                         [m["content"] for m in self.db.get_messages("branch")])
        self.assertEqual("source", self.db.resolve_resume_session_id("source"))
        self.assertEqual({"source", "branch"},
                         {s["id"] for s in self.db.list_sessions_rich(source="api_server", include_children=False)})
        self.db.append_message("branch", "user", "Only in branch")
        self.assert_source_unchanged()

    async def test_invalid_cutoffs_do_not_create_or_change_anything(self):
        for cutoff in [True, None, "2", 0, -1, 999999, self.user]:
            response = await self.fork(cutoff)
            self.assertEqual(400, response.status, await response.text())
            self.assertIsNone(self.db.get_session("branch"))
            self.assert_source_unchanged()

    async def test_foreign_message_is_rejected(self):
        self.db.create_session("other", "api_server")
        foreign = self.db.append_message("other", "assistant", "Not from source")
        response = await self.fork(foreign)
        self.assertEqual(400, response.status)
        self.assertIsNone(self.db.get_session("branch"))
        self.assert_source_unchanged()

    async def test_branch_appears_first_even_when_selected_messages_are_old(self):
        response = await self.fork(self.answer)
        self.assertEqual(201, response.status)
        sessions = self.db.list_sessions_rich(source="api_server", include_children=False,
                                             order_by_last_active=True)
        self.assertEqual("branch", sessions[0]["id"])
        branch = self.db.get_session("branch")
        self.assertGreaterEqual(sessions[0]["last_active"], branch["started_at"])
        self.assert_source_unchanged()

    async def test_title_conflict_leaves_no_partial_branch(self):
        response = await self.fork(body={"id": "branch", "through_message_id": self.answer,
                                       "title": "Original conversation"})
        self.assertEqual(400, response.status)
        self.assertIsNone(self.db.get_session("branch"))
        self.assert_source_unchanged()

    async def test_copy_failure_rolls_back_session_and_messages(self):
        insert = self.db._insert_message_rows
        def fail_after_insert(*args):
            insert(*args)
            raise RuntimeError("Injected copy failure")
        with patch.object(self.db, "_insert_message_rows", side_effect=fail_after_insert):
            response = await self.fork(self.answer)
        self.assertEqual(500, response.status)
        self.assertIsNone(self.db.get_session("branch"))
        self.assertEqual([], self.db.get_messages("branch"))
        self.assert_source_unchanged()

    async def test_concurrent_same_id_creates_once(self):
        responses = await asyncio.gather(self.fork(self.answer), self.fork(self.answer))
        self.assertEqual([201, 409], sorted(r.status for r in responses))
        self.assertEqual(2, len(self.db.get_messages("branch")))
        self.assert_source_unchanged()

    async def test_tool_prefix_retains_completed_calls_and_drops_pending_selected_call(self):
        call = [{"id": "completed", "type": "function", "function": {"name": "test", "arguments": "{}"}}]
        self.db.append_message("source", "assistant", "Looking", tool_calls=call)
        self.db.append_message("source", "tool", "Result", tool_call_id="completed", tool_name="test")
        selected = self.db.append_message("source", "assistant", "Next step", tool_calls=[
            {"id": "pending", "type": "function", "function": {"name": "test", "arguments": "{}"}}])
        response = await self.fork(selected)
        self.assertEqual(201, response.status, await response.text())
        messages = self.db.get_messages("branch")
        self.assertEqual(call, messages[-3]["tool_calls"])
        self.assertEqual("completed", messages[-2]["tool_call_id"])
        self.assertFalse(messages[-1]["tool_calls"])
        self.assertEqual(1, self.db.get_session("branch")["tool_call_count"])

    async def test_legacy_full_fork_still_uses_existing_contract(self):
        response = await self.fork(body={"id": "legacy"}, legacy=True)
        self.assertEqual(201, response.status, await response.text())
        self.assertEqual(4, len(self.db.get_messages("legacy")))
        self.assertEqual("branched", self.db.get_session("source")["end_reason"])

    async def test_compressed_source_branches_the_transcript_shown_by_history(self):
        self.db.end_session("source", "compression")
        self.db.create_session("tip", "api_server", parent_session_id="source", model="test-model")
        self.db.append_message("tip", "user", "Current question")
        selected = self.db.append_message("tip", "assistant", "Current answer")
        response = await self.fork(selected)
        self.assertEqual(201, response.status, await response.text())
        self.assertEqual(["Current question", "Current answer"],
                         [m["content"] for m in self.db.get_messages("branch")])
        self.assertEqual("tip", self.db.resolve_resume_session_id("source"))
        self.assertEqual("compression", self.db.get_session("source")["end_reason"])

    async def test_authentication_is_required(self):
        response = await self.client.post("/api/sessions/source/fork-at-message", json={"through_message_id": self.answer})
        self.assertEqual(401, response.status)
        self.assert_source_unchanged()

    async def test_explicit_plugin_capability_is_advertised_without_mutating_native_flags(self):
        response = await self.client.get("/api/bellpull/capabilities", headers={"Authorization": "Bearer disposable-test-key"})
        self.assertTrue((await response.json())["features"]["session_fork_at_message"])
        self.assertNotIn("session_fork_at_message", _STATIC_FEATURE_FLAGS)


if __name__ == "__main__":
    unittest.main(verbosity=2)
