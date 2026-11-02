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
        # WebSocket upgrades must not follow a redirect carrying helper credentials.
        trace = aiohttp.TraceConfig()
        async def no_redirect(session, context, params):
            raise aiohttp.ClientConnectionError("Speech helper redirects are disabled")
        trace.on_request_redirect.append(no_redirect)
        self.session = aiohttp.ClientSession(headers=self.headers, auto_decompress=False, trace_configs=[trace])

    def stop(self):
        session = self.session
        if session is not None and self.loop is not None and not self.loop.is_closed():
            # A delayed unload must close the old transport, never a session
            # created by a subsequent successful plugin registration.
            self.loop.call_soon_threadsafe(lambda: self.loop.create_task(session.close()))

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

    async def stt_stream(self, request):
        upstream = None
        downstream = web.WebSocketResponse(max_msg_size=64 * 1024, heartbeat=10)
        tasks = []
        try:
            # Authenticate/open upstream before upgrading: old helpers produce an
            # ordinary 404, which lets the phone deliberately use the batch route.
            upstream = await asyncio.wait_for(self.session.ws_connect(self.url + "/stt/stream",
                heartbeat=10, max_msg_size=64 * 1024, receive_timeout=30), 10)
            await downstream.prepare(request)

            async def upload():
                count = 0
                async for message in downstream:
                    if message.type == aiohttp.WSMsgType.BINARY:
                        count += len(message.data)
                        if len(message.data) % 2 or count > 16000 * 2 * 60:
                            raise ValueError("Audio limit exceeded")
                        await upstream.send_bytes(message.data)
                    elif message.type == aiohttp.WSMsgType.TEXT:
                        if len(message.data) > 1024:
                            raise ValueError("Control frame too large")
                        await upstream.send_str(message.data)
                    else:
                        break

            async def download():
                async for message in upstream:
                    if message.type == aiohttp.WSMsgType.TEXT:
                        await downstream.send_str(message.data)
                    else:
                        break

            tasks = [asyncio.create_task(upload()), asyncio.create_task(download())]
            async with asyncio.timeout(90):
                done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                for task in done:
                    task.result()
        except aiohttp.WSServerHandshakeError as exc:
            return error(exc.status if exc.status in (401, 403, 404, 405, 429, 501, 503) else 502,
                         "Streaming transcription is unavailable")
        except (TimeoutError, aiohttp.ClientError, OSError, ValueError):
            if not downstream.prepared:
                return error(503, "Speech helper unavailable")
            if not downstream.closed:
                await downstream.send_json({"type": "error", "message": "Speech stream interrupted. Retry the recording."})
        finally:
            for task in tasks:
                task.cancel()
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)
            if upstream is not None:
                await upstream.close()
            if downstream.prepared:
                await downstream.close()
        return downstream

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
                ("POST", "/api/bellpull/speech/stt", self.stt),
                ("GET", "/api/bellpull/speech/stt/stream", self.stt_stream)]
