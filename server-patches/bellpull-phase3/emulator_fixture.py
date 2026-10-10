"""Disposable acceptance server: real Hermes HTTP/storage, synthetic models/inference.

Run only with an empty HERMES_HOME and the isolated test image. Never import this
from a production gateway. No upstream model credentials or network are used.
"""
import asyncio
import json
import os
from pathlib import Path
from aiohttp import web
from gateway.config import PlatformConfig
from gateway.platforms.api_server import APIServerAdapter, body_limit_middleware
from gateway.platforms import api_server_bellpull_phase3 as phase3

assert os.environ.get("HERMES_HOME") == "/tmp/bellpull-emulator-fixture"
Path(os.environ["HERMES_HOME"]).mkdir(exist_ok=True)
adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={"key": "bellpull-emulator-fixture-key"}))  # gitleaks:allow
phase3.catalog = lambda _: {"version": 1, "default": {"model": "fixture-vision", "provider": "fixture", "model_options": {}},
    "models": [{"model": "fixture-vision", "provider": "fixture", "configured": True, "vision": True,
                "reasoning": {"efforts": ["low", "high"], "can_disable": True}}], "note": "Synthetic device acceptance fixture"}


class Agent:
    def __init__(self, **kwargs):
        self.session_id = kwargs["session_id"]
        self.prompt = kwargs.get("ephemeral_system_prompt", "")
    def run_conversation(self, **kwargs):
        from gateway.platforms.api_server_bellpull_artifacts import ArtifactStore
        metadata = json.loads(self.prompt.split("\n")[-1])
        Path(metadata["output_directory"], "generated-report.txt").write_text("Bellpull Phase 3 synthetic report. Original bytes preserved.\n")
        pdf = next((item for item in metadata["attachments"] if item["mime"] == "application/pdf"), None)
        if pdf:
            Path(metadata["output_directory"], "generated-report.pdf").write_bytes(Path(pdf["path"]).read_bytes())
        db = adapter._ensure_session_db()
        # Real session history so reconnecting the Android client can reconcile admission.
        db.append_message(self.session_id, "user", kwargs.get("persist_user_message") or "Attached files")
        db.append_message(self.session_id, "assistant", "Fixture received the attachments and generated a report.")
        return {"final_response": "Fixture received the attachments and generated a report."}


adapter._create_agent = lambda **kwargs: Agent(**kwargs)
app = web.Application(middlewares=[body_limit_middleware])
failed_uploads = set()
def fail_once(handler):
    async def upload(request):
        identity = request.match_info["artifact_id"]
        if request.query.get("name", "").startswith("retry-") and identity not in failed_uploads:
            failed_uploads.add(identity)
            return web.json_response({"error": {"message": "Synthetic first upload failure", "code": "fixture_retry"}}, status=503)
        return await handler(request)
    return upload
for method, path, handler in adapter._http_route_table():
    if method == "PUT" and path == "/api/bellpull/artifacts/{artifact_id}":
        handler = fail_once(handler)
    app.router.add_route(method, path, handler)


async def gpu(request):
    rejected = adapter._check_auth(request)
    if rejected is not None:
        return rejected
    return web.json_response({"version": 1, "checked_at": __import__("time").time(), "refresh_seconds": 15,
        "cards": [], "errors": ["Synthetic fixture: GPU tools unavailable"], "scale_note": ""})


app.router.add_get("/api/bellpull/plugins/gpu", gpu)
web.run_app(app, host="0.0.0.0", port=18645)
