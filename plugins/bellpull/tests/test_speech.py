import asyncio
import os
import unittest
from unittest.mock import patch

from aiohttp import FormData, web
from aiohttp.test_utils import TestClient, TestServer
from plugins.bellpull.speech import SpeechProxy, TTS_LIMIT, STT_LIMIT


class SpeechTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.status = 200
        self.delay = 0
        self.streaming = False
        self.finish = asyncio.Event()
        self.seen = []
        self.stream_closed = asyncio.Event()
        self.stream_audio = bytearray()
        self.redirect_stream = False
        async def speech_stream(request):
            self.seen.append((request.path, request.headers.get("Authorization"), b""))
            if self.redirect_stream:
                raise web.HTTPFound("/health")
            if self.status != 200:
                return web.Response(status=self.status)
            ws = web.WebSocketResponse()
            await ws.prepare(request)
            try:
                await ws.send_json({"type": "ready", "sample_rate": 16000})
                async for message in ws:
                    if message.type == 2:
                        self.stream_audio.extend(message.data)
                        await ws.send_json({"type": "partial", "text": "Live words"})
                    elif message.json().get("type") == "finish":
                        await ws.send_json({"type": "final", "text": "Live words complete"})
                        break
            finally:
                self.stream_closed.set()
                await ws.close()
            return ws
        async def helper(request):
            self.seen.append((request.path, request.headers.get("Authorization"), await request.read()))
            await asyncio.sleep(self.delay)
            if self.status != 200:
                return web.Response(status=self.status, body=b'helper failure')
            if request.path == '/health':
                return web.json_response(dict(ok=True, tts=True, stt=True, tts_loaded=True, stt_loaded=False))
            if request.path == '/stt':
                return web.json_response(dict(text='hello', language='en', seconds=1, took_ms=12))
            if self.streaming:
                response = web.StreamResponse(headers={'Content-Type': 'application/octet-stream'})
                await response.prepare(request)
                await response.write(b'\x00\x00')
                await self.finish.wait()
                await response.write(b'\xff\x7f')
                return response
            return web.Response(body=b'\x00\x00\xff\x7f', content_type='application/octet-stream',
                headers={'X-Audio-Seconds': '1', 'X-Took-Ms': '12'})
        app = web.Application()
        app.router.add_get('/stt/stream', speech_stream)
        app.router.add_route('*', '/{path}', helper)
        self.helper = TestServer(app)
        await self.helper.start_server()
        with patch.dict(os.environ, BELLPULL_SPEECH_URL=str(self.helper.make_url('')).rstrip('/'),
                BELLPULL_SPEECH_KEY='synthetic-helper'):
            self.proxy = SpeechProxy()
        app = web.Application()
        app.on_startup.append(self.proxy.start)
        async def cleanup(_app):
            await self.proxy.close()
        app.on_cleanup.append(cleanup)
        for method, path, handler in self.proxy.routes():
            app.router.add_route(method, path, handler)
        self.client = TestClient(TestServer(app))
        await self.client.start_server()

    async def asyncTearDown(self):
        self.finish.set()
        await self.client.close()
        await self.helper.close()

    async def test_success_and_helper_auth(self):
        response = await self.client.get('/api/bellpull/speech/health')
        self.assertTrue((await response.json())['proxy'])
        response = await self.client.post('/api/bellpull/speech/tts?stream=true', json={'text': 'hello'})
        self.assertEqual(b'\x00\x00\xff\x7f', await response.read())
        self.assertEqual('1', response.headers['X-Audio-Seconds'])
        self.assertEqual('12', response.headers['X-Took-Ms'])
        self.assertIn(b'"stream": true', self.seen[-1][2])
        audio = FormData()
        audio.add_field('audio', b'RIFFfixture', filename='audio.wav', content_type='audio/wav')
        response = await self.client.post('/api/bellpull/speech/stt', data=audio)
        self.assertEqual('hello', (await response.json())['text'])
        self.assertIn(b'RIFFfixture', self.seen[-1][2])
        self.assertTrue(all(row[1] == 'Bearer synthetic-helper' for row in self.seen))

    async def test_passthrough_timeout_and_refused(self):
        self.status = 502
        response = await self.client.post('/api/bellpull/speech/tts', json={'text': 'hello'})
        self.assertEqual(502, response.status)
        self.assertEqual(b'helper failure', await response.read())
        self.delay = .1
        self.proxy.tts_timeout = .01
        response = await self.client.post('/api/bellpull/speech/tts', json={'text': 'hello'})
        self.assertEqual(504, response.status)
        self.assertIn('error', await response.json())
        self.proxy.stt_timeout = .01
        audio = FormData()
        audio.add_field('audio', b'RIFFfixture', filename='audio.wav', content_type='audio/wav')
        response = await self.client.post('/api/bellpull/speech/stt', data=audio)
        self.assertEqual(504, response.status)
        await self.helper.close()
        response = await self.client.get('/api/bellpull/speech/health')
        self.assertEqual(503, response.status)
        self.assertIn('error', await response.json())

    async def test_tts_stream_delivers_pcm_before_helper_finishes(self):
        self.streaming = True
        response = await asyncio.wait_for(self.client.post('/api/bellpull/speech/tts',
            json={'text': 'hello', 'stream': True}), 2)
        self.assertEqual(b'\x00\x00', await asyncio.wait_for(response.content.readexactly(2), 2))
        self.assertFalse(self.finish.is_set())
        self.finish.set()
        self.assertEqual(b'\xff\x7f', await response.read())

    async def test_limits_and_validation(self):
        for data in ({'text': ''}, {'text': 'x' * 4001}, {'text': 'ok', 'stream': 'yes'}):
            self.assertEqual(400, (await self.client.post('/api/bellpull/speech/tts', json=data)).status)
        self.assertEqual(413, (await self.client.post('/api/bellpull/speech/tts', data=b'x' * (TTS_LIMIT + 1))).status)
        self.assertEqual(413, (await self.client.post('/api/bellpull/speech/stt',
            data=b'x' * (STT_LIMIT + 1), headers={'Content-Type': 'multipart/form-data; boundary=fixture'})).status)

    async def test_streamed_input_returns_partial_before_finish_and_uses_helper_key(self):
        async with self.client.ws_connect('/api/bellpull/speech/stt/stream') as ws:
            self.assertEqual("ready", (await ws.receive_json())["type"])
            await ws.send_json({"type": "start", "sample_rate": 16000, "channels": 1, "format": "pcm_s16le"})
            await ws.send_bytes(b"\x00\x00" * 1600)
            self.assertEqual("Live words", (await ws.receive_json())["text"])
            self.assertFalse(self.stream_closed.is_set())
            await ws.send_json({"type": "finish"})
            self.assertEqual("final", (await ws.receive_json())["type"])
        self.assertEqual(b"\x00\x00" * 1600, self.stream_audio)
        self.assertEqual("Bearer synthetic-helper", self.seen[-1][1])

    async def test_stream_cancel_closes_helper_and_old_helper_preserves_404(self):
        ws = await self.client.ws_connect('/api/bellpull/speech/stt/stream')
        await ws.receive_json()
        await ws.close()
        await asyncio.wait_for(self.stream_closed.wait(), 2)
        self.status = 404
        response = await self.client.get('/api/bellpull/speech/stt/stream')
        self.assertEqual(404, response.status)

    async def test_websocket_redirect_never_reaches_redirect_target(self):
        self.redirect_stream = True
        response = await self.client.get('/api/bellpull/speech/stt/stream')
        self.assertEqual(503, response.status)
        self.assertEqual(['/stt/stream'], [row[0] for row in self.seen])

    async def test_wire_auth_and_capability(self):
        # Gateway modules are supplied by the pinned stock-Hermes test image.
        from gateway.config import PlatformConfig
        from gateway.platforms.api_server import APIServerAdapter
        from plugins.bellpull.http import wire
        adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={'key': 'synthetic-profile'}))
        app = web.Application()
        stop = wire(app, adapter)
        client = TestClient(TestServer(app))
        await client.start_server()
        try:
            for prefix in ('', '/p/fixture'):
                for method, path, _ in self.proxy.routes():
                    for headers in ({}, {'Authorization': 'Bearer wrong-synthetic'}):
                        response = await client.request(method, prefix + path, headers=headers)
                        self.assertEqual(401, response.status)
            response = await client.get('/api/bellpull/capabilities', headers={'Authorization': 'Bearer synthetic-profile'})
            self.assertTrue((await response.json())['features']['bellpull_speech'])
        finally:
            stop()
            await client.close()
