"""Real stock admission, journal and SQLite; deterministic agent, no inference."""
import asyncio
import base64
from functools import wraps
import inspect
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from gateway.config import PlatformConfig
from gateway.platforms.api_server import APIServerAdapter, body_limit_middleware
from plugins.bellpull import models_files, native_attachments
from plugins.bellpull.http import wire

CATALOG = {"version": 1, "default": {"model": "fixture-vision", "provider": "fixture", "model_options": {}},
    "models": [{"model": name, "provider": "fixture", "configured": True, "vision": vision,
                "reasoning": {"efforts": [], "can_disable": False}}
               for name, vision in (("fixture-vision", True), ("fixture-text", False))]}


class NativeAttachmentsTest(unittest.IsolatedAsyncioTestCase):
    port = 0
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {"HERMES_HOME": self.temp.name, "BELLPULL_NTFY_URL": ""})
        self.env.start()
        self.catalog = patch.object(models_files, "catalog", return_value=CATALOG)
        self.catalog.start()
        self.adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={"key": "native-attachment-fixture-key"}))
        self.adapter._request_route_conflict_error = lambda **kwargs: None
        self.executed, self.created = [], []
        signature = inspect.signature(self.adapter._create_agent)
        owner = self

        @wraps(self.adapter._create_agent)
        def create(*args, **kwargs):
            arguments = signature.bind_partial(*args, **kwargs).arguments
            owner.created.append(arguments)
            class Agent:
                status_callback = None
                session_id = arguments.get("session_id")
                model = arguments.get("requested_model")
                provider = arguments.get("requested_provider")
                def run_conversation(self, **values):
                    if values["user_message"] == "exercise compression":
                        self.status_callback("lifecycle", "🗜️ Compacting context — summarizing earlier conversation")
                        owner.compression_started.set()
                        if not owner.resume_agent.wait(5):
                            raise RuntimeError("fixture timed out")
                        self.status_callback("compacted", "Context compaction complete")
                    owner.executed.append(values)
                    database = owner.adapter._ensure_session_db()
                    database.append_message(self.session_id, "user", values.get("persist_user_message", values["user_message"]))
                    database.append_message(self.session_id, "assistant", "Attachment received")
                    prompt = arguments.get("ephemeral_system_prompt") or ""
                    if "output_directory" in prompt:
                        metadata = json.loads(prompt.split("\n")[-1])
                        Path(metadata["output_directory"], "result.txt").write_text("Synthetic output")
                    return {"final_response": "Attachment received", "completed": True}
            return Agent()
        self.adapter._create_agent = create
        app = web.Application(middlewares=[body_limit_middleware])
        for method, path, handler in self.adapter._http_route_table():
            app.router.add_route(method, path, handler)
        self.stop = wire(app, self.adapter)
        self.app = app
        self.client = TestClient(TestServer(app, host="127.0.0.1", port=self.port))
        await self.client.start_server()
        self.headers = {"Authorization": "Bearer native-attachment-fixture-key"}
        response = await self.client.post("/api/sessions", headers=self.headers, json={})
        self.session = (await response.json())["session"]["id"]

    async def asyncTearDown(self):
        tasks = list(self.adapter._active_run_tasks.values())
        if tasks:
            await asyncio.wait_for(asyncio.gather(*tasks, return_exceptions=True), 5)
        await self.client.close()
        self.adapter._run_idempotency_store.close()
        self.catalog.stop()
        self.env.stop()
        self.temp.cleanup()

    async def upload(self, identity="1" * 32, mime="text/plain", data=b"Attachment contents"):
        response = await self.client.put("/api/bellpull/artifacts/" + identity,
            headers={**self.headers, "Content-Type": mime}, params={"session_id": self.session, "name": "fixture.txt"}, data=data)
        self.assertEqual(201, response.status, await response.text())
        return identity

    def payload(self, identity):
        selection = CATALOG["default"]
        return {"session_id": self.session, "input": "Read this file", **selection,
            "require_model_lock": True,
            "bellpull": {"version": 1, "selection": selection, "artifact_ids": [identity]}}

    async def submit(self, body, key="fixture-attachment"):
        return await self.client.post("/v1/runs", headers={**self.headers, "Idempotency-Key": key}, json=body)

    async def settle(self, run_id):
        for _ in range(200):
            response = await self.client.get("/v1/runs/" + run_id, headers=self.headers)
            value = await response.json()
            if value["status"] in ("completed", "failed", "cancelled"):
                return value
            await asyncio.sleep(.01)
        self.fail("Run did not settle")

    async def test_mismatched_bellpull_lock_is_rejected_before_new_run(self):
        body = self.payload("unused")
        body["bellpull"]["artifact_ids"] = []
        body["model"] = "other"
        response = await self.submit(body, "invalid-selection")
        self.assertEqual(409, response.status)
        self.assertFalse(self.created)

    async def test_explicit_bellpull_model_lock_reaches_native_constructor_without_files(self):
        body = self.payload("unused")
        body["bellpull"]["artifact_ids"] = []
        response = await self.submit(body, "text-model-lock")
        self.assertLess(response.status, 300, await response.text())
        rid = (await response.json())["run_id"]
        for _ in range(200):
            if self.created:
                break
            await asyncio.sleep(.01)
        self.assertTrue(self.created[0]["confirmed_runtime_lock"])
        replay = await self.submit(body, "text-model-lock")
        self.assertEqual(rid, (await replay.json())["run_id"])
        self.assertEqual(1, len(self.created))

    async def test_stock_capability_upload_handoff_history_and_output(self):
        capabilities = await (await self.client.get("/api/bellpull/capabilities", headers=self.headers)).json()
        self.assertTrue(capabilities["features"]["bellpull_run_attachments"])
        identity = await self.upload()
        body = self.payload(identity)
        response = await self.submit(body)
        self.assertEqual(202, response.status, await response.text())
        run_id = (await response.json())["run_id"]
        result = await self.settle(run_id)
        self.assertEqual("completed", result["status"], result)
        self.assertEqual(body["input"], self.executed[0]["persist_user_message"])
        prompt = self.created[0]["ephemeral_system_prompt"]
        self.assertIn("untrusted metadata", prompt)
        path = json.loads(prompt.split("\n")[-1])["attachments"][0]["path"]
        self.assertEqual("Attachment contents", Path(path).read_text())
        records = await (await self.client.get("/api/bellpull/artifacts", headers=self.headers)).json()
        self.assertEqual({"upload", "agent"}, {r["source"] for r in records["data"]})
        self.assertTrue(all(r["message_id"] is not None for r in records["data"]))

    async def test_real_session_and_durable_transports_surface_only_the_active_run_phase(self):
        self.compression_started, self.resume_agent = threading.Event(), threading.Event()
        body = {"session_id": self.session, "input": "exercise compression"}
        response = await self.client.post("/v1/runs", headers={**self.headers, "Idempotency-Key": "compaction"}, json=body)
        self.assertLess(response.status, 300, await response.text())
        run_id = (await response.json())["run_id"]
        try:
            self.assertTrue(await asyncio.to_thread(self.compression_started.wait, 4))
            poll = await self.client.get("/v1/runs/" + run_id, headers=self.headers)
            self.assertEqual("compacting", (await poll.json()).get("bellpull_phase"))
        finally:
            self.resume_agent.set()
        await self.settle(run_id)
        poll = await self.client.get("/v1/runs/" + run_id, headers=self.headers)
        self.assertNotIn("bellpull_phase", await poll.json())
        self.compression_started.clear()
        response = await self.client.post(f"/api/sessions/{self.session}/chat/stream", headers=self.headers,
            json={"message": "exercise compression"})
        frames = await response.text()
        self.assertIn("event: run.compacting", frames)
        self.assertIn("event: run.compacted", frames)
        self.assertIn("event: run.completed", frames)

    async def test_replay_after_deleted_file_and_catalog_failure_has_one_execution(self):
        identity = await self.upload()
        body = self.payload(identity)
        first = await self.submit(body)
        self.assertEqual(202, first.status, await first.text())
        run_id = (await first.json())["run_id"]
        self.assertEqual("completed", (await self.settle(run_id))["status"])
        await self.client.delete("/api/bellpull/artifacts/" + identity, headers=self.headers)
        with patch.object(models_files, "catalog", side_effect=AssertionError("Replay must not rediscover models")):
            response = await self.submit(body)
            self.assertEqual(202, response.status, await response.text())
            self.assertEqual(run_id, (await response.json())["run_id"])
            conflict = await self.submit({**body, "input": "Different message"})
            self.assertEqual(409, conflict.status, await conflict.text())
        self.assertEqual(1, len(self.executed))

    async def test_image_reaches_selected_model_and_text_only_model_rejects_before_reservation(self):
        identity = await self.upload(mime="image/png", data=b"image fixture")
        body = self.payload(identity)
        selected = {**CATALOG["default"], "model": "fixture-text"}
        invalid = {**body, **selected, "bellpull": {**body["bellpull"], "selection": selected}}
        response = await self.submit(invalid)
        self.assertEqual(409, response.status, await response.text())
        self.assertEqual([], self.created)
        response = await self.submit(body)
        self.assertEqual(202, response.status, await response.text())
        result = await self.settle((await response.json())["run_id"])
        self.assertEqual("completed", result["status"], result)
        url = self.executed[0]["user_message"][1]["image_url"]["url"]
        self.assertEqual(b"image fixture", base64.b64decode(url.split(",")[1]))

    async def test_foreign_session_and_unauthenticated_requests_cannot_use_files(self):
        identity = await self.upload()
        response = await self.client.post("/api/sessions", headers=self.headers, json={})
        other = (await response.json())["session"]["id"]
        response = await self.submit({**self.payload(identity), "session_id": other})
        self.assertEqual(409, response.status, await response.text())
        response = await self.client.post("/v1/runs", json=self.payload(identity))
        self.assertEqual(401, response.status)
        self.assertEqual([], self.created)

    async def test_unload_rejects_new_attachments_and_reactivation_reuses_bridge(self):
        from plugins.bellpull.compatibility import source_mode
        if source_mode() == "after":
            self.skipTest("Patched hosts use the host bridge; native lifecycle is checked on stock Hermes")
        identity = await self.upload()
        bridge = self.adapter._bellpull_native_attachments
        bridge[1]()
        response = await self.submit(self.payload(identity))
        self.assertEqual(503, response.status, await response.text())
        self.assertEqual([], self.created)
        count = len(self.app.middlewares)
        self.assertTrue(native_attachments.wire(self.app, self.adapter)[0])
        self.assertEqual(count, len(self.app.middlewares))
        response = await self.submit(self.payload(identity))
        self.assertEqual(202, response.status, await response.text())
        result = await self.settle((await response.json())["run_id"])
        self.assertEqual("completed", result["status"], result)

    async def test_simultaneous_requests_keep_conversation_files_separate(self):
        first_id = await self.upload(data=b"First conversation")
        first = self.payload(first_id)
        response = await self.client.post("/api/sessions", headers=self.headers, json={})
        self.session = (await response.json())["session"]["id"]
        second_id = await self.upload("2" * 32, data=b"Second conversation")
        second = self.payload(second_id)
        responses = await asyncio.gather(self.submit(first, "first"), self.submit(second, "second"), self.submit(first, "first"))
        runs = []
        for response in responses:
            self.assertEqual(202, response.status, await response.text())
            runs.append((await response.json())["run_id"])
        self.assertEqual(runs[0], runs[2]); self.assertNotEqual(runs[0], runs[1])
        for run in set(runs):
            self.assertEqual("completed", (await self.settle(run))["status"])
        self.assertEqual(2, len(self.executed))
        by_session = {item["session_id"]: json.loads(item["ephemeral_system_prompt"].split("\n")[-1])["attachments"] for item in self.created}
        self.assertEqual("First conversation", Path(by_session[first["session_id"]][0]["path"]).read_text())
        self.assertEqual("Second conversation", Path(by_session[second["session_id"]][0]["path"]).read_text())

    async def test_live_desktop_owner_is_not_sent_a_text_only_attachment_turn(self):
        if not hasattr(self.adapter, "_admit_to_live_bot_chat"):
            self.skipTest("This host predates live Bot Chat ownership")
        identity = await self.upload()
        with patch("tools.bot_live_delivery.find_canonical_live_owner", return_value={"session_id": self.session}), \
                patch("tools.bot_live_delivery.deliver_to_live_owner", side_effect=AssertionError("Must not enqueue incomplete input")):
            response = await self.submit(self.payload(identity))
            self.assertEqual(409, response.status, await response.text())
        self.assertEqual([], self.executed)

    async def test_svg_is_a_document_for_text_models_without_silent_vision_delegation(self):
        identity = await self.upload(mime="image/svg+xml", data=b'<svg xmlns="http://www.w3.org/2000/svg"/>')
        body = self.payload(identity)
        selected = {**CATALOG["default"], "model": "fixture-text"}
        body.update(selected); body["bellpull"]["selection"] = selected
        response = await self.submit(body)
        self.assertEqual(202, response.status, await response.text())
        result = await self.settle((await response.json())["run_id"])
        self.assertEqual("completed", result["status"], result)
        self.assertIsInstance(self.executed[0]["user_message"], str)
        self.assertEqual("fixture-text", self.created[0]["requested_model"])
