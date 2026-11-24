import asyncio
import io
import os
import threading
import unittest
import wave

import aiohttp
from aiohttp.test_utils import TestClient, TestServer

from server import RocmRecognizer, create_app


class RocmProtocolTest(unittest.IsolatedAsyncioTestCase):
    async def test_streams_publish_partials_and_batch_decodes_only_at_finish(self):
        streams = []

        class Stream:
            def __init__(self, partials):
                self.partials = partials
                self.size = 0
                self.closed = False

            def feed(self, pcm):
                self.size += len(pcm)
                return str(self.size) if self.partials else ''

            def finish(self):
                return 'final ' + str(self.size)

            def close(self):
                self.closed = True

        class Model:
            def open_stream(self, partials):
                stream = Stream(partials)
                streams.append(stream)
                return stream

        recognizer = RocmRecognizer(Model())
        client = TestClient(TestServer(create_app(recognizer, 'fixture', backend='rocm')))
        headers = {'Authorization': 'Bearer fixture'}
        try:
            await client.start_server()
            async with client.ws_connect('/stt/stream', headers=headers) as ws:
                self.assertEqual('ready', (await ws.receive_json())['type'])
                await ws.send_json({'type': 'start', 'sample_rate': 16000, 'channels': 1, 'format': 'pcm_s16le'})
                await ws.send_bytes(bytes(6400))
                self.assertEqual({'type': 'partial', 'text': '6400'}, await ws.receive_json())
                audio = io.BytesIO()
                with wave.open(audio, 'wb') as wav:
                    wav.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
                    wav.writeframes(bytes(10000))
                form = aiohttp.FormData()
                form.add_field('audio', audio.getvalue(), filename='test.wav', content_type='audio/wav')
                async with client.post('/stt', headers=headers, data=form) as response:
                    self.assertEqual('final 10000', (await response.json())['text'])
                await ws.send_json({'type': 'finish'})
                self.assertEqual('final 6400', (await ws.receive_json())['text'])
            self.assertEqual([True, False], [s.partials for s in streams])
        finally:
            await client.close()
            recognizer.executor.shutdown(wait=True)
        self.assertTrue(all(s.closed for s in streams))
        self.assertEqual(0, recognizer.active)

    async def test_cancelled_decode_finishes_before_stream_is_cleared(self):
        entered, proceed, closed = threading.Event(), threading.Event(), threading.Event()

        class Stream:
            def feed(self, pcm):
                entered.set()
                if not proceed.wait(4):
                    raise RuntimeError('Timed out waiting for test signal')

            def close(self):
                closed.set()

        recognizer = RocmRecognizer(None)
        state = Stream()
        try:
            decoding = asyncio.create_task(recognizer.feed(state, b'audio'))
            self.assertTrue(await asyncio.to_thread(entered.wait, 3))
            decoding.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await decoding
            cleanup = asyncio.create_task(recognizer.release(state))
            await asyncio.sleep(0)
            self.assertFalse(closed.is_set())
            proceed.set()
            await cleanup
            self.assertTrue(closed.is_set())
        finally:
            proceed.set()
            recognizer.executor.shutdown(wait=True)


@unittest.skipUnless(os.getenv('BELLPULL_ROCM_TEST_WAV'), 'Requires the pinned GPU model and public clean.wav fixture')
class RocmEncoderTest(unittest.TestCase):
    def test_cached_frames_and_transcript_match_the_full_encoder(self):
        import numpy as np
        import torch
        from rocm_models import Moonshine

        with wave.open(os.environ['BELLPULL_ROCM_TEST_WAV']) as wav:
            self.assertEqual((16000, 1, 2), (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()))
            pcm = wav.readframes(wav.getnframes())
        # End between model frames and split transport chunks off the convolution stride.
        pcm = pcm[:-18]
        model = Moonshine()
        state = model.open_stream(True)
        previews = set()
        try:
            for index in range(0, len(pcm), 2802):
                text = state.feed(pcm[index:index + 2802])
                if text:
                    previews.add(text)
            self.assertGreater(len(previews), 1)
            self.assertGreater(state.stable_frames, state.left_frames)
            self.assertEqual(model.transcribe(pcm), state.finish())
            samples = np.frombuffer(pcm, dtype='<i2').astype(np.float32) / 32768
            with torch.inference_mode():
                inputs = model.processor(samples, sampling_rate=16000, return_tensors='pt').to('cuda')
                expected = state.encoder(**inputs)
                self.assertTrue(torch.equal(expected.attention_mask, state.encoded.attention_mask))
                torch.testing.assert_close(expected.last_hidden_state, state.encoded.last_hidden_state, atol=2e-4, rtol=2e-4)
        finally:
            state.close()
        self.assertFalse(state.pcm)
        self.assertIsNone(state.encoded)
