#!/usr/bin/env python3
"""Isolated phone -> real plugin proxy -> local CPU helper.
Only loopback listeners and synthetic credentials; never connects to Hermes.
"""
import os
from pathlib import Path
import sys

from aiohttp import web

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from plugins.bellpull.speech import SpeechProxy

os.environ["BELLPULL_SPEECH_URL"] = "http://127.0.0.1:18645"
os.environ["BELLPULL_SPEECH_KEY"] = "local-speech-fixture-key"
proxy = SpeechProxy()


@web.middleware
async def authenticate(request, handler):
    if request.headers.get("Authorization") != "Bearer phone-stream-fixture-key":
        raise web.HTTPUnauthorized()
    return await handler(request)


app = web.Application(middlewares=[authenticate])
app.on_startup.append(proxy.start)


async def cleanup(app):
    await proxy.close()


app.on_cleanup.append(cleanup)
for method, path, handler in proxy.routes():
    app.router.add_route(method, path, handler)
    app.router.add_route(method, "/p/fixture" + path, handler)
web.run_app(app, host="127.0.0.1", port=18648, access_log=None)
