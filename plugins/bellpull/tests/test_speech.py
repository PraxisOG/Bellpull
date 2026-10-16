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
