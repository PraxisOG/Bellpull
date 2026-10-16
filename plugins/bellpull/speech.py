"""Plugin-owned speech boundary; helper credentials never leave this host."""
import asyncio
import json
import os

import aiohttp
from aiohttp import web

TTS_LIMIT = 8 * 1024
STT_LIMIT = 32 * 1024 * 1024


def error(status, message):
    return web.json_response({"error": {"message": message}}, status=status)


class SpeechProxy:
    def __init__(self):
        self.url = os.environ.get("BELLPULL_SPEECH_URL", "http://127.0.0.1:8645").rstrip("/")
        key = os.environ.get("BELLPULL_SPEECH_KEY", "")
        self.headers = {"Authorization": "Bearer " + key} if key else {}
        self.session = None
        self.loop = None
        self.tts_timeout = 120
        self.stt_timeout = 60

    async def start(self, _app):
        self.loop = asyncio.get_running_loop()
        self.session = aiohttp.ClientSession(headers=self.headers, auto_decompress=False)

    def stop(self):
        if self.loop is not None and not self.loop.is_closed():
            self.loop.call_soon_threadsafe(lambda: self.loop.create_task(self.close()))

    async def close(self):
        if self.session is not None:
            await self.session.close()

    async def body(self, request, limit):
        if request.content_length is not None and request.content_length > limit:
            raise web.HTTPRequestEntityTooLarge(max_size=limit, actual_size=request.content_length)
        data = bytearray()
        async for chunk in request.content.iter_chunked(64 * 1024):
            data.extend(chunk)
            if len(data) > limit:
                raise web.HTTPRequestEntityTooLarge(max_size=limit, actual_size=len(data))
        return bytes(data)

    async def health(self, request):
        return await self.proxy(request, "health", timeout=10)

    async def tts(self, request):
        try:
            body = json.loads(await self.body(request, TTS_LIMIT))
        except (ValueError, UnicodeError):
            return error(400, "Invalid speech JSON")
        if not isinstance(body, dict) or not isinstance(body.get("text"), str) or not 1 <= len(body["text"]) <= 4000:
            return error(400, "text must contain 1..4000 characters")
        if ("voice" in body and not isinstance(body["voice"], str)) or ("stream" in body and not isinstance(body["stream"], bool)):
            return error(400, "Invalid voice or stream option")
        if request.query.get("stream") == "true":
            body["stream"] = True
        return await self.proxy(request, "tts", timeout=self.tts_timeout, json=body)

    async def stt(self, request):
        # Keep the original multipart boundary and bytes; bound the complete envelope.
        if request.content_type != "multipart/form-data":
            return error(400, "Expected multipart audio field")
        body = await self.body(request, STT_LIMIT)
        return await self.proxy(request, "stt", timeout=self.stt_timeout, data=body,
            headers={"Content-Type": request.headers["Content-Type"]})

    async def proxy(self, request, endpoint, timeout, **kwargs):
        response = None
        try:
            async with self.session.request(request.method, self.url + "/" + endpoint,
                    timeout=aiohttp.ClientTimeout(total=timeout), allow_redirects=False, **kwargs) as upstream:
                headers = {key: upstream.headers[key] for key in
                    ("Content-Type", "Content-Encoding", "X-Audio-Seconds", "X-Took-Ms") if key in upstream.headers}
                if endpoint == "health" and 200 <= upstream.status < 300:
                    payload = await upstream.json()
                    payload["proxy"] = True
                    return web.json_response(payload)
                response = web.StreamResponse(status=upstream.status, headers=headers)
                # Read the first chunk before committing headers, allowing startup timeouts
                # to become JSON 504s. Later failures must abort the already-started stream.
                first = await upstream.content.read(64 * 1024)
                await response.prepare(request)
                if first:
                    await response.write(first)
                async for chunk in upstream.content.iter_chunked(64 * 1024):
                    await response.write(chunk)
                await response.write_eof()
                return response
        except (asyncio.TimeoutError, aiohttp.ClientError, OSError) as exc:
            if response is not None and response.prepared:
                if request.transport is not None:
                    request.transport.close()
                raise
            return error(504 if isinstance(exc, asyncio.TimeoutError) else 503,
                "Speech helper timed out" if isinstance(exc, asyncio.TimeoutError) else "Speech helper unavailable")

    def routes(self):
        return [("GET", "/api/bellpull/speech/health", self.health),
                ("POST", "/api/bellpull/speech/tts", self.tts),
                ("POST", "/api/bellpull/speech/stt", self.stt)]
