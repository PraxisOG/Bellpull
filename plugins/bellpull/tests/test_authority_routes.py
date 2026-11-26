import asyncio
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from plugins.bellpull import authority_routes


class AuthorityRouteTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.native = {"status": "running", "admission_id": "a", "execution_generation": 1}
        self.store = Mock()
        self.adapter = SimpleNamespace(
            _durable_run_status=lambda request, run_id: dict(self.native),
            _run_statuses={"r": {"status": "running"}}, _run_owners={"r": "owner"},
            _run_streams={"r": asyncio.Queue()}, _run_idempotency_store=self.store,
            _run_idempotency_ids={"r"}, _run_idempotency_scope=lambda request: "owner")
        self.active = True
        self.addCleanup(authority_routes.wire(self.adapter, asyncio.get_running_loop(), lambda: self.active))
        self.execution = {}
        self.agent = SimpleNamespace(model="primary", provider="custom")
        with patch.object(authority_routes, "admitted_agent", return_value=("r", "a", 1, self.agent)):
            self.event("pre_llm_call")

    def event(self, name, turn="original", **fields):
        self.adapter._bellpull_authority_route(self.execution, name, "session", turn, {
            "model": self.agent.model, "provider": self.agent.provider, **fields})

    def poll(self):
        return self.adapter._durable_run_status(None, "r")

    async def test_only_the_adopted_turn_reports_a_confirmed_transition(self):
        self.agent.model = "second"
        self.agent._provider_fallback_active = True
        self.agent._provider_fallback_route = ("second", "custom")
        self.event("pre_api_request", turn="nested")
        self.event("pre_api_request", provider="different")
        await asyncio.sleep(0)
        self.assertTrue(self.adapter._run_streams["r"].empty())
        self.event("pre_api_request")
        self.event("pre_api_request")
        await asyncio.sleep(0)
        stream = self.adapter._run_streams["r"]
        transition = stream.get_nowait()["bellpull_model_fallback"]
        self.assertTrue(stream.empty())
        self.assertEqual("second", transition["to"]["model"])
        self.assertEqual(transition, self.poll()["bellpull_model_fallback"])
        self.native["execution_generation"] = 2
        self.assertNotIn("bellpull_model_fallback", self.poll())
        self.native.update(execution_generation=1, admission_id="other")
        self.assertNotIn("bellpull_model_fallback", self.poll())

    async def test_terminal_route_requires_a_final_text_response_on_the_last_attempt(self):
        final = dict(finish_reason="stop", assistant_content_chars=2, assistant_tool_call_count=0)
        self.event("post_api_request", **{**final, "assistant_tool_call_count": 1})
        self.native["status"] = "completed"
        await asyncio.sleep(0)
        self.assertNotIn("runtime", self.poll())
        self.event("post_api_request", **final)
        self.event("pre_api_request")
        await asyncio.sleep(0)
        self.assertNotIn("runtime", self.poll())
        self.event("post_api_request", **final)
        await asyncio.sleep(0)
        self.assertEqual({"provider": "custom", "model": "primary"}, self.poll()["runtime"])
        self.native["status"] = "failed"
        self.assertNotIn("runtime", self.poll())
        self.native.update(status="completed", runtime={"model": "native", "provider": "other"})
        self.assertEqual(self.native["runtime"], self.poll()["runtime"])

    async def test_reopened_run_uses_the_original_scoped_receipt_and_honors_disable(self):
        self.event("post_api_request", finish_reason="stop", assistant_content_chars=2, assistant_tool_call_count=0)
        await asyncio.sleep(0)
        self.native["status"] = "completed"
        self.store.status_for_run.return_value = {"status": self.adapter._run_statuses.pop("r")}
        self.assertEqual("primary", self.poll()["runtime"]["model"])
        self.store.status_for_run.assert_called_once_with("owner", "r")
        self.active = False
        self.assertNotIn("runtime", self.poll())
