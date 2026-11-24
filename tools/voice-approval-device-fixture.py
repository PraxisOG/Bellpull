#!/usr/bin/env python3
"""Run inside a disposable stock Hermes installation with the checkout on PYTHONPATH.

This creates a temporary profile, binds the native gateway to loopback port 18667,
and prints a fresh chat ID for each of the two VoiceDeviceTest approval choices.
Reverse port 18667 on the fixture emulator. Pass approvalSession and approvalChoice
to hearsAndAnswersTheSameNativeApproval. At its microphone markers play any short
request, then "Read the approval request", then the requested spoken decision.
"""
import asyncio
import json
import os
from pathlib import Path
import shutil
import sys
from tempfile import TemporaryDirectory
import time
import uuid

import aiohttp
from aiohttp import web
import yaml

KEY = "local-voice-approval-fixture"
SERVER = "http://127.0.0.1:18667"
MODEL = "fixture-model"
PLUGIN = '''import json
from tools.approval import request_tool_approval

def confirm(args, **kwargs):
    result = request_tool_approval("color_confirmation", "Approve labeling this test card blue?")
    return json.dumps({"confirmed": bool(result.get("approved"))})

def register(ctx):
    ctx.register_tool(name="color_confirmation", toolset="lab_confirmation",
        schema={"name": "color_confirmation", "description": "Ask the human about a blue label.",
                "parameters": {"type": "object", "properties": {}}}, handler=confirm,
        description="Ask for the blue label confirmation.")
'''


async def completion(request):
    body = await request.json()
    last = body["messages"][-1]
    if last["role"] == "tool":
        allowed = json.loads(last["content"])["confirmed"]
        message = {"role": "assistant", "content": "Blue label accepted." if allowed else "Blue label declined."}
        reason = "stop"
    elif any(tool["function"]["name"] == "color_confirmation" for tool in body.get("tools", [])):
        message = {"role": "assistant", "content": None, "tool_calls": [{"type": "function",
            "id": "call_" + uuid.uuid4().hex, "function": {"name": "color_confirmation", "arguments": "{}"}}]}
        reason = "tool_calls"
    else:
        # The native gateway may also ask for ancillary text, such as a title.
        message = {"role": "assistant", "content": "Blue card review"}
        reason = "stop"
    base = {"id": "chatcmpl-" + uuid.uuid4().hex, "model": MODEL, "created": int(time.time())}
    if not body.get("stream"):
        return web.json_response({**base, "object": "chat.completion",
            "choices": [{"index": 0, "message": message, "finish_reason": reason}]})
    if "tool_calls" in message:
        message["tool_calls"][0]["index"] = 0
    chunks = [{"index": 0, "delta": message, "finish_reason": None},
              {"index": 0, "delta": {}, "finish_reason": reason}]
    stream = "".join("data: " + json.dumps({**base, "object": "chat.completion.chunk", "choices": [chunk]})
                     + "\n\n" for chunk in chunks)
    return web.Response(content_type="text/event-stream", text=stream + "data: [DONE]\n\n")


async def models(request):
    return web.json_response({"object": "list", "data": [{"id": MODEL, "object": "model", "owned_by": "fixture"}]})


async def main():
    app = web.Application()
    app.router.add_post("/v1/chat/completions", completion)
    app.router.add_get("/v1/models", models)
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, "127.0.0.1", 18668).start()
    with TemporaryDirectory() as folder:
        home = Path(folder)
        tools = home / "plugins"
        lab = tools / "lab_confirmation"
        lab.mkdir(parents=True)
        (lab / "__init__.py").write_text(PLUGIN)
        (lab / "plugin.yaml").write_text("name: lab_confirmation\nversion: 1.0.0\nkind: standalone\n")
        from plugins import bellpull
        shutil.copytree(Path(bellpull.__file__).parent, tools / "bellpull")
        endpoint = {"host": "127.0.0.1", "port": 18667, "key": KEY}
        config = {"model": {"default": MODEL, "provider": "custom", "base_url": "http://127.0.0.1:18668/v1",
                            "api_key": "local-fixture", "context_length": 65536},
                  "platform_toolsets": {"api_server": ["lab_confirmation"]},
                  "plugins": {"enabled": ["bellpull", "lab_confirmation"]},
                  "platforms": {"api_server": {"enabled": True, "extra": endpoint}},
                  "tools": {"tool_search": {"enabled": "off"}}}
        (home / "config.yaml").write_text(yaml.safe_dump(config))
        env = dict(os.environ, HERMES_HOME=folder, API_SERVER_ENABLED="true", API_SERVER_HOST=endpoint["host"],
                   API_SERVER_PORT=str(endpoint["port"]), API_SERVER_KEY=KEY, HF_HUB_OFFLINE="1")
        gateway = await asyncio.create_subprocess_exec(str(Path(sys.executable).with_name("hermes")),
            "gateway", "run", "--no-supervise", env=env)
        try:
            async with aiohttp.ClientSession(headers={"Authorization": "Bearer " + KEY}) as client:
                async with asyncio.timeout(60):
                    while True:
                        if gateway.returncode is not None:
                            raise RuntimeError(f"Gateway exited with status {gateway.returncode}")
                        try:
                            async with client.get(SERVER + "/health") as health:
                                if health.status == 200:
                                    break
                        except aiohttp.ClientConnectionError:
                            pass
                        await asyncio.sleep(.25)
                for choice in ("once", "deny"):
                    async with client.post(SERVER + "/api/sessions", json={"title": "Voice approval " + choice}) as response:
                        response.raise_for_status()
                        chat = (await response.json())["session"]["id"]
                        print(f"READY approvalChoice={choice} approvalSession={chat}", flush=True)
            await gateway.wait()
        finally:
            if gateway.returncode is None:
                gateway.terminate()
                await gateway.wait()
            await runner.cleanup()


if __name__ == "__main__":
    asyncio.run(main())
