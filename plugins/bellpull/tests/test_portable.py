"""Stock adapter discovery, persistent outputs and unchanged core sources."""
import base64
import asyncio
import hashlib
import inspect
import json
import os
from pathlib import Path
import secrets
import tempfile
import threading
import unittest
from unittest.mock import patch
from aiohttp import ClientSession
from gateway.config import PlatformConfig
from gateway.platforms import api_server
from hermes_cli.plugins import get_plugin_manager
from tools.registry import registry


def decoded(result):
    return json.loads(result) if isinstance(result, str) else result


class PortableTest(unittest.IsolatedAsyncioTestCase):
    async def flush_status(self):
        # Newer stock Hermes persists disconnect status on a background writer.
        # Drain it before deleting its disposable home; do not suppress errors.
        from gateway import status
        flush = getattr(status, "flush_runtime_status", None)
        if callable(flush):
            self.assertTrue(await asyncio.to_thread(flush))

    async def test_reasoning_arrives_before_completion_through_stock_native_stream(self):
        core = Path(api_server.__file__).resolve().parents[2]
        paths = [core / "gateway/platforms/api_server.py", core / "gateway/platforms/api_server_runs.py"]
        before = [hashlib.sha256(path.read_bytes()).hexdigest() for path in paths]
        release = threading.Event()
        with tempfile.TemporaryDirectory() as home, patch.dict(os.environ, {"HERMES_HOME": home, "BELLPULL_NTFY_URL": ""}):
            Path(home, "config.yaml").write_text("plugins:\n  enabled:\n    - bellpull\n")
            manager = get_plugin_manager()
            manager.discover_and_load()
            key = secrets.token_hex(32)
            headers = {"Authorization": "Bearer " + key}
            adapter = api_server.APIServerAdapter(PlatformConfig(enabled=True,
                extra={"key": key, "host": "127.0.0.1", "port": 18643}))
            text = "A reasoning token. " * 50
            def create_agent(reasoning_callback=None, tool_progress_callback=None, **kwargs):
                class Agent:
                    session_id = kwargs.get("session_id")
                    def __init__(self):
                        self.reasoning_callback = reasoning_callback
                    def run_conversation(self, **_kwargs):
                        self.reasoning_callback(text)
                        # A completed reasoning preview must not duplicate those tokens.
                        tool_progress_callback("reasoning.available", "_thinking", text[:500], None)
                        if not release.wait(10):
                            raise RuntimeError("Fixture reader did not release the run")
                        self.reasoning_callback("Second step.")
                        return {"final_response": "Saved answer", "completed": True}
                return Agent()
            accepts_reasoning = "reasoning_callback" in inspect.signature(adapter._create_agent).parameters
            def older_create_agent(tool_progress_callback=None, **kwargs):
                return create_agent(tool_progress_callback=tool_progress_callback, **kwargs)
            adapter._create_agent = create_agent if accepts_reasoning else older_create_agent
            try:
                self.assertTrue(await adapter.connect())
                async with ClientSession() as client:
                    origin = "http://127.0.0.1:18643"
                    response = await client.get(origin + "/api/bellpull/capabilities", headers=headers)
                    self.assertTrue((await response.json())["features"]["bellpull_reasoning_streaming"])
                    accepted = await client.post(origin + "/v1/runs", headers={**headers, "Idempotency-Key": "reasoning-fixture"}, json={"input": "Think"})
                    self.assertEqual(202, accepted.status, await accepted.text())
                    run_id = (await accepted.json())["run_id"]
                    response = await client.get(origin + f"/v1/runs/{run_id}/events", headers=headers)
                    self.assertEqual(200, response.status)
                    frames = []
                    async def read_reasoning():
                        while True:
                            line = await response.content.readline()
                            if line.startswith(b"data:"):
                                event = json.loads(line[5:])
                                frames.append(event)
                                if event.get("event") == "reasoning.delta":
                                    return event
                    first = await asyncio.wait_for(read_reasoning(), 5)
                    self.assertEqual(text, first["text"])
                    status = await client.get(origin + f"/v1/runs/{run_id}", headers=headers)
                    self.assertEqual("running", (await status.json())["status"])
                    release.set()
                    async def drain():
                        async for line in response.content:
                            if line.startswith(b"data:"):
                                frames.append(json.loads(line[5:]))
                    await asyncio.wait_for(drain(), 5)
                    self.assertEqual(text + "Second step.", "".join(event.get("text", "") for event in frames
                        if event.get("event") in ("reasoning.delta", "reasoning.available")))
                    repeated = await client.post(origin + "/v1/runs", headers={**headers, "Idempotency-Key": "reasoning-fixture"}, json={"input": "Think"})
                    self.assertEqual(run_id, (await repeated.json())["run_id"])
                    self.assertTrue(any(event.get("event") == "run.completed" for event in frames), frames)
            finally:
                release.set()
                await adapter.disconnect()
                manager.unload()
                await self.flush_status()
        self.assertEqual(before, [hashlib.sha256(path.read_bytes()).hexdigest() for path in paths])

    async def test_stock_outputs_auth_restart_and_unchanged_core(self):
        core = Path(api_server.__file__).resolve().parents[2]
        sources = [core / "gateway/platforms/api_server.py", core / "gateway/platforms/api_server_runs.py", core / "tools/approval_context.py"]
        before = [hashlib.sha256(path.read_bytes()).hexdigest() for path in sources]
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ,
                {"HERMES_HOME": temporary, "BELLPULL_NTFY_URL": ""}):
            Path(temporary, "config.yaml").write_text("plugins:\n  enabled:\n    - bellpull\n")
            manager = get_plugin_manager()
            manager.discover_and_load()
            key = secrets.token_hex(32)
            headers = {"Authorization": "Bearer " + key}
            adapter = api_server.APIServerAdapter(PlatformConfig(enabled=True,
                extra={"key": key, "host": "127.0.0.1", "port": 18642}))
            try:
                self.assertTrue(await adapter.connect())
                async with ClientSession() as client:
                    origin = "http://127.0.0.1:18642"
                    response = await client.get(origin + "/api/bellpull/capabilities", headers=headers)
                    self.assertEqual(200, response.status, await response.text())
                    features = (await response.json())["features"]
                    self.assertTrue(features["bellpull_persistent_artifacts"])
                    self.assertTrue(features["bellpull_artifact_lifecycle"])
                    session = await client.post(origin + "/api/sessions", headers=headers, json={})
                    session_id = (await session.json())["session"]["id"]
                    database = await adapter._ensure_session_db_async()
                    for source in ("cli", "tui", "telegram"):
                        database.create_session(session_id="external_" + source, source=source, model="fixture")
                    all_sessions = await client.get(origin + "/api/sessions", headers=headers)
                    self.assertTrue({"api_server", "cli", "tui", "telegram"}.issubset(
                        {row["source"] for row in (await all_sessions.json())["data"]}))
                    result = decoded(registry.dispatch("bellpull_share_files", {"action": "prepare"},
                        scope=manager.scope_key, session_id=session_id))
                    self.assertIn("outbox", result, result)
                    outbox = Path(result["outbox"])
                    png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
                    for index in range(4):
                        (outbox / f"verified-{index}.png").write_bytes(png)
                    published = decoded(registry.dispatch("bellpull_share_files", {"action": "publish"},
                        scope=manager.scope_key, session_id=session_id))
                    self.assertEqual(4, len(published["artifacts"]), published)
                    response = await client.get(origin + "/api/bellpull/artifacts", headers=headers)
                    records = (await response.json())["data"]
                    self.assertEqual(4, len(records))
                    content_url = origin + "/api/bellpull/artifacts/" + records[0]["id"] + "/content"
                    self.assertEqual(401, (await client.get(content_url)).status)
                    self.assertEqual(401, (await client.get(content_url, headers={"Authorization": "Bearer wrong"})).status)
                    response = await client.get(content_url, headers=headers)
                    self.assertEqual(200, response.status)
                    self.assertEqual(png, await response.read())
                    self.assertEqual(404, (await client.get(origin + "/p/unconfigured/api/bellpull/artifacts", headers=headers)).status)
                    # Stock Hermes keeps its 10 MB JSON limit. Plugin uploads use
                    # chunked streaming with their own 25 MiB bound.
                    async def chunks():
                        for _ in range(11):
                            yield b"x" * (1024 * 1024)
                    response = await client.put(origin + "/api/bellpull/artifacts/" + "a" * 32,
                        params={"session_id": session_id, "name": "large.bin"},
                        headers={**headers, "Content-Type": "application/octet-stream"}, data=chunks())
                    self.assertEqual(201, response.status, await response.text())
                    self.assertEqual(11 * 1024 * 1024, (await response.json())["size"])
                await adapter.disconnect()
                manager.unload()
                manager.discover_and_load()
                adapter = api_server.APIServerAdapter(PlatformConfig(enabled=True,
                    extra={"key": key, "host": "127.0.0.1", "port": 18642}))
                self.assertTrue(await adapter.connect())
                async with ClientSession() as client:
                    response = await client.get(content_url, headers=headers)
                    self.assertEqual(200, response.status)
                    self.assertEqual(png, await response.read())
            finally:
                await adapter.disconnect()
                manager.unload()
                await self.flush_status()
        self.assertEqual(before, [hashlib.sha256(path.read_bytes()).hexdigest() for path in sources])

    async def test_tool_rejects_model_supplied_session_and_unsafe_outputs(self):
        from hermes_state import SessionDB
        from plugins.bellpull.sharing import share_files
        with tempfile.TemporaryDirectory() as home, patch.dict(os.environ, {"HERMES_HOME": home}):
            database = SessionDB(Path(home) / "state.db")
            database.create_session(session_id="one", source="api_server", model="fixture")
            database.close()
            self.assertIn("error", share_files({"action": "prepare", "session_id": "one"}))
            folder = Path(share_files({"action": "prepare"}, session_id="one")["outbox"])
            secret = Path(home, "private.txt"); secret.write_text("private")
            (folder / "link.txt").symlink_to(secret)
            (folder / "hardlink.txt").hardlink_to(secret)
            result = share_files({"action": "publish"}, session_id="one")
            self.assertEqual([], result["artifacts"])
            self.assertEqual(2, len(result["warnings"]))
