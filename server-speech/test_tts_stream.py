import asyncio
from concurrent.futures import ThreadPoolExecutor
import io
import threading
import unittest
import wave
import numpy as np
from aiohttp.test_utils import TestClient, TestServer
from aiohttp import web
from server import create_app
from tts_stream import StreamingSynthesis, sentence_batches


class FakeSynthesis(StreamingSynthesis):
    def __init__(self):
        self.executor = ThreadPoolExecutor(max_workers=1)
        self.active = False
        self.calls = []
        self.blocked = threading.Event()
        self.release = threading.Event()
        self.release.set()
        self.model = self

    def create(self, text, **kwargs):
        self.calls.append(text)
        if text.startswith("Second"):
            self.blocked.set()
            self.release.wait(5)
        return np.zeros(2400, dtype=np.float32), 24000


class StreamingTtsTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.synthesis = FakeSynthesis()
        self.client = TestClient(TestServer(create_app(None, "fixture", synthesizer=self.synthesis)))
        await self.client.start_server()

    async def asyncTearDown(self):
        self.synthesis.release.set()
        await self.client.close()
        self.synthesis.executor.shutdown(wait=True)

    async def post(self, body):
        return await self.client.post("/tts", json=body, headers={"Authorization": "Bearer fixture"})

    async def test_first_sentence_arrives_before_second_finishes_and_wav_still_works(self):
        self.synthesis.release.clear()
        response = await self.post({"text": "First sentence. Second sentence.", "stream": True})
        self.assertEqual(200, response.status)
        self.assertEqual("24000", response.headers["X-Audio-Sample-Rate"])
        self.assertEqual("pcm_s16le", response.headers["X-Audio-Format"])
        first = await asyncio.wait_for(response.content.readexactly(4800), 1)
        self.assertEqual(4800, len(first))
        self.assertFalse(response.content.at_eof())
        self.synthesis.release.set()
        self.assertEqual(4800, len(await response.read()))
        response = await self.post({"text": "A WAV caller."})
        with wave.open(io.BytesIO(await response.read())) as wav:
            self.assertEqual((24000, 1, 2), (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()))

    async def test_cancellation_retains_native_ownership_and_discards_future_batches(self):
        self.synthesis.release.clear()
        chunks = self.synthesis.chunks("Second blocked. Third must not start.", "af_heart")
        task = asyncio.create_task(anext(chunks))
        self.assertTrue(await asyncio.to_thread(self.synthesis.blocked.wait, 1))
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertTrue(self.synthesis.active)
        with self.assertRaises(web.HTTPTooManyRequests):
            await anext(self.synthesis.chunks("Overlapping work", "af_heart"))
        self.synthesis.release.set()
        for _ in range(100):
            if not self.synthesis.active:
                break
            await asyncio.sleep(.01)
        self.assertFalse(self.synthesis.active)
        self.assertEqual(["Second blocked."], self.synthesis.calls)

    async def test_auth_format_and_empty_input_are_rejected(self):
        self.assertEqual(401, (await self.client.post("/tts", json={"text": "Hello"})).status)
        for body in ({"text": " "}, {"text": "Hello", "stream": "yes"}, {"text": "Hello", "voice": "missing"}):
            self.assertEqual(400, (await self.post(body)).status)
        self.assertEqual([], self.synthesis.calls)

    def test_batches_are_bounded_and_preserve_words(self):
        text = "Start now. " + "A sentence with punctuation, numbers 123, and Unicode café. " * 12
        batches = list(sentence_batches(text))
        self.assertEqual(text.split(), " ".join(batches).split())
        self.assertTrue(all(0 < len(batch) <= 220 for batch in batches))
        self.assertEqual("Start now.", batches[0])
