"""Real aiohttp adapter/SQLite, fake inference and hardware. No network or live data."""
import asyncio
import base64
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import AsyncMock, patch

os.makedirs(os.environ["HERMES_HOME"], exist_ok=True)
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from gateway.config import PlatformConfig
from gateway.platforms.api_server import APIServerAdapter, body_limit_middleware
from plugins.bellpull import models_files as phase3
from plugins.bellpull import register
from hermes_cli.plugins import PluginContext, PluginManifest, PluginManager

CATALOG = {"version": 1, "default": {"model": "fixture-vision", "provider": "fixture", "model_options": {}},
    "models": [{"model": name, "provider": "fixture", "configured": True, "vision": vision,
                "reasoning": {"efforts": ["low", "high"], "can_disable": vision}}
               for name, vision in (("fixture-vision", True), ("fixture-text", False))]}


class HttpTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.patch = patch.object(phase3, "catalog", return_value=CATALOG)
        self.patch.start()
        self.adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={"key": "isolated-phase3-fixture-key"}))  # gitleaks:allow
        self.adapter._conversation_history_for_session = AsyncMock(return_value=[])
        self.adapter._request_route_conflict_error = lambda **kwargs: None
        self.created, self.executed = [], []
        owner = self
        class Agent:
            session_id = "fixture"
            def run_conversation(self, **kwargs):
                owner.executed.append(kwargs)
                # Pretend the agent used the scoped output directory instructions.
                prompt = owner.created[-1].get("ephemeral_system_prompt", "")
                if prompt:
                    metadata = json.loads(prompt.split("\n")[-1])
                    Path(metadata["output_directory"], "report.txt").write_text("Synthetic generated report")
                return {"final_response": "Fixture complete"}
        def create(**kwargs):
            self.created.append(kwargs)
            return Agent()
        self.adapter._create_agent = create
        app = web.Application(middlewares=[body_limit_middleware])
        for method, path, handler in self.adapter._http_route_table():
            app.router.add_route(method, path, handler)
        manager = PluginManager()
        register(PluginContext(PluginManifest(name="bellpull"), manager))
        for factory, _name in manager.get_platform_handler_factories("api_server"):
            factory(app, self.adapter)
        self.client = TestClient(TestServer(app))
        await self.client.start_server()
        self.headers = {"Authorization": "Bearer isolated-phase3-fixture-key"}
        reply = await self.client.post("/api/sessions", headers=self.headers, json={})
        self.session = (await reply.json())["session"]["id"]

    async def asyncTearDown(self):
        tasks = list(self.adapter._active_run_tasks.values())
        if tasks:
            await asyncio.wait_for(asyncio.gather(*tasks, return_exceptions=True), 5)
        await self.client.close()
        self.adapter._run_idempotency_store.close()
        self.patch.stop()

    async def upload(self, identity, data=b"sample", mime="application/pdf", name="sample.pdf"):
        return await self.client.put("/api/bellpull/artifacts/" + identity,
            headers={**self.headers, "Content-Type": mime}, params={"session_id": self.session, "name": name}, data=data)

    async def settle(self, run_id):
        for _ in range(250):
            response = await self.client.get("/v1/runs/" + run_id, headers=self.headers)
            value = await response.json()
            if value["status"] in ("completed", "failed"):
                return value
            await asyncio.sleep(.01)
        self.fail("Run did not finish")

    async def test_auth_repeatable_download_unicode_filename_and_missing_content(self):
        identity = "1" * 32
        response = await self.upload(identity, name="résumé.pdf")
        self.assertEqual(201, response.status, await response.text())
        metadata = await response.json()
        self.assertEqual("résumé.pdf", metadata["name"])
        path = "/api/bellpull/artifacts/" + identity + "/content"
        self.assertEqual(401, (await self.client.get(path)).status)
        for _ in range(2):
            response = await self.client.get(path, headers=self.headers)
            self.assertEqual(b"sample", await response.read())
            self.assertEqual("no-store", response.headers["Cache-Control"])
        for blob in Path(os.environ["HERMES_HOME"]).glob("bellpull-artifacts/*/content/" + identity + ".pdf"):
            blob.unlink()
        self.assertEqual(410, (await self.client.get(path, headers=self.headers)).status)

    async def test_bearer_rotation_keeps_files_but_revokes_old_credentials(self):
        identity = "9" * 32
        self.assertEqual(201, (await self.upload(identity)).status)
        path = "/api/bellpull/artifacts/" + identity + "/content"
        self.adapter._api_key = "rotated-fixture-key"
        self.assertEqual(401, (await self.client.get(path, headers=self.headers)).status)
        response = await self.client.get(path, headers={"Authorization": "Bearer rotated-fixture-key"})
        self.assertEqual(200, response.status)
        self.assertEqual(b"sample", await response.read())

    async def test_native_multipart_images_durable_replay_and_generated_artifact(self):
        identity = "2" * 32
        response = await self.upload(identity, b"fixture-image-original", "image/png", "sample.png")
        self.assertEqual(201, response.status, await response.text())
        payload = {"session_id": self.session, "input": "Look at the attachment", "bellpull": {
            "version": 1, "selection": CATALOG["default"], "artifact_ids": [identity]}}
        headers = {**self.headers, "Idempotency-Key": "phase3-" + self.session}
        response = await self.client.post("/v1/runs", headers=headers, json=payload)
        self.assertEqual(202, response.status, await response.text())
        accepted = await response.json()
        self.assertEqual("completed", (await self.settle(accepted["run_id"]))["status"])
        self.assertTrue(self.created[-1]["confirmed_runtime_lock"])
        self.assertEqual("fixture-vision", self.created[-1]["requested_model"])
        self.assertEqual(payload["input"], self.executed[-1]["persist_user_message"])
        url = self.executed[-1]["user_message"][1]["image_url"]["url"]
        self.assertEqual(b"fixture-image-original", base64.b64decode(url.split(",")[1]))
        # Retrying is recovered before fresh catalog/file checks or new inference.
        self.patch.stop()
        with patch.object(phase3, "catalog", side_effect=AssertionError("Must replay before discovery")):
            # Full native admission slots and unavailable files must not block replay.
            self.adapter._max_concurrent_runs = 1
            with patch.object(self.adapter, "active_agent_work_count", return_value=1):
                repeated = await self.client.post("/v1/runs", headers=headers, json=payload)
            self.assertEqual(accepted["run_id"], (await repeated.json())["run_id"])
        self.patch.start()
        self.assertEqual(1, len(self.executed))
        records = await self.client.get("/api/bellpull/artifacts", headers=self.headers, params={"session_id": self.session})
        records = (await records.json())["data"]
        self.assertEqual({"upload", "agent"}, {row["source"] for row in records})

    async def test_concurrent_retries_admit_one_executor_and_one_output_directory(self):
        headers = {**self.headers, "Idempotency-Key": "racing-" + self.session}
        payload = {"session_id": self.session, "input": "One execution", "bellpull": {
            "version": 1, "selection": CATALOG["default"], "artifact_ids": []}}
        responses = await asyncio.gather(*(self.client.post("/v1/runs", headers=headers, json=payload) for _ in range(8)))
        values = [await response.json() for response in responses]
        self.assertTrue(all(response.status == 202 for response in responses), values)
        self.assertEqual(1, len({value["run_id"] for value in values}))
        await self.settle(values[0]["run_id"])
        self.assertEqual(1, len(self.executed))
        changed = await self.client.post("/v1/runs", headers=headers, json={**payload, "input": "Changed"})
        self.assertEqual(409, changed.status)
        self.assertEqual(1, len(self.executed))

    async def test_new_run_rejected_at_full_capacity_creates_no_run_outputs(self):
        self.adapter._max_concurrent_runs = 1
        with patch.object(self.adapter, "active_agent_work_count", return_value=2):
            response = await self.client.post("/v1/runs", headers={**self.headers, "Idempotency-Key": "full-" + self.session},
                json={"session_id": self.session, "input": "No admission", "bellpull": {
                    "version": 1, "selection": CATALOG["default"], "artifact_ids": []}})
        self.assertEqual(429, response.status)
        self.assertEqual([], self.executed)

    async def test_unsupported_vision_and_reasoning_rejected_without_inference(self):
        identity = "3" * 32
        await self.upload(identity, b"fixture-image", "image/png", "sample.png")
        choice = dict(CATALOG["default"], model="fixture-text")
        payload = {"session_id": self.session, "input": "Look", "bellpull": {
            "version": 1, "selection": choice, "artifact_ids": [identity]}}
        response = await self.client.post("/v1/runs", headers=self.headers, json=payload)
        self.assertEqual(409, response.status, await response.text())
        choice["model_options"] = {"reasoning": {"enabled": False}}
        response = await self.client.put(f"/api/bellpull/sessions/{self.session}/model", headers=self.headers, json=choice)
        self.assertEqual(409, response.status, await response.text())
        self.assertEqual([], self.executed)

    async def test_model_choice_is_session_scoped_and_legacy_durable_run_obeys_lock(self):
        choice = dict(CATALOG["default"], model_options={"reasoning": {"enabled": True, "effort": "high"}})
        url = f"/api/bellpull/sessions/{self.session}/model"
        result = await self.client.put(url, headers=self.headers, json=choice)
        self.assertEqual(200, result.status, await result.text())
        self.assertEqual(choice, (await (await self.client.get(url, headers=self.headers)).json())["selection"])
        response = await self.client.post("/v1/runs", headers=self.headers, json={"session_id": self.session, "input": "fixture"})
        await self.settle((await response.json())["run_id"])
        self.assertEqual(choice["model_options"], self.created[-1]["model_options"])
        self.assertTrue(self.created[-1]["confirmed_runtime_lock"])
        other = (await (await self.client.post("/api/sessions", headers=self.headers, json={})).json())["session"]["id"]
        result = await self.client.get(f"/api/bellpull/sessions/{other}/model", headers=self.headers)
        self.assertEqual({}, (await result.json())["selection"]["model_options"])

    async def test_binary_upload_over_json_limit_and_oversize_rejection(self):
        response = await self.upload("4" * 32, b"x" * 10_000_001)
        self.assertEqual(201, response.status, await response.text())
        response = await self.upload("5" * 32, b"x" * (25 * 1024 * 1024 + 1))
        self.assertEqual(413, response.status, await response.text())

    def test_reasoning_controls_reach_real_hermes_transport(self):
        from agent.transports.chat_completions import ChatCompletionsTransport
        from providers import get_provider_profile
        transport = ChatCompletionsTransport()
        for provider, model, options, expected in (
            ("fireworks", "accounts/fireworks/models/deepseek-v4p1-flash", {"reasoning": {"enabled": False}}, "none"),
            ("fireworks", "accounts/fireworks/models/deepseek-v4p1-flash", {"reasoning": {"enabled": True, "effort": "max"}}, "max"),
            ("custom", "qwen38-flash-next", {"reasoning": {"enabled": False}}, False),
            ("custom", "qwen38-flash-next", {"reasoning": {"enabled": True}}, True)):
            wire = transport.build_kwargs(model, [{"role": "user", "content": "Fixture"}],
                provider_profile=get_provider_profile(provider), reasoning_config=options["reasoning"],
                request_overrides=phase3.request_overrides(provider, model, options))
            actual = wire.get("reasoning_effort") if provider == "fireworks" else wire["extra_body"]["chat_template_kwargs"]["enable_thinking"]
            self.assertEqual(expected, actual)
        self.assertFalse(phase3.reasoning_capabilities("fireworks", "accounts/fireworks/models/gpt-oss-120b")["can_disable"])
        self.assertEqual({}, phase3.request_overrides("custom", "unverified-qwen", {"reasoning": {"enabled": False}}))


class CatalogTest(unittest.TestCase):
    def test_native_capability_dataclass_and_unconfigured_routes(self):
        from agent.models_dev import ModelCapabilities
        model = "accounts/fireworks/models/deepseek-v4p1-flash"
        inventory = {"providers": [
            {"slug": "fireworks", "authenticated": True, "models": [model]},
            {"slug": "custom", "authenticated": False, "models": ["qwen38-flash-next"]}]}
        with patch("hermes_cli.inventory.load_picker_context", return_value={}), \
             patch("hermes_cli.inventory.build_model_options_payload", return_value=inventory), \
             patch("gateway.run._resolve_runtime_agent_kwargs", return_value={"provider": "fireworks"}), \
             patch("gateway.run._resolve_gateway_model", return_value=model), \
             patch("agent.models_dev.get_model_capabilities", return_value=ModelCapabilities(supports_vision=True)):
            result = phase3.catalog(None)
        self.assertTrue(result["models"][0]["vision"])
        self.assertTrue(result["models"][0]["configured"])
        self.assertFalse(result["models"][1]["configured"])
        self.assertTrue(result["models"][1]["reasoning"]["can_enable"])


if __name__ == "__main__":
    unittest.main()
