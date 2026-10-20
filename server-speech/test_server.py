import asyncio
import threading
import unittest

from aiohttp import WSServerHandshakeError
from aiohttp.test_utils import TestClient, TestServer
from server import Recognizer, create_app


class FakeRecognizer:
    def __init__(self):
        self.active = 0
        self.released = 0

    async def create(self):
        return {"bytes": 0}

    async def feed(self, state, data):
        state["bytes"] += len(data)
        return "samples " + str(state["bytes"])

    async def finish(self, state):
        return "final " + str(state["bytes"])

    async def release(self, state):
        if state is not None:
            self.released += 1


class ServerTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.recognizer = FakeRecognizer()
        self.client = TestClient(TestServer(create_app(self.recognizer, "test-key")))
        await self.client.start_server()
        self.headers = {"Authorization": "Bearer test-key"}

    async def asyncTearDown(self):
        await self.client.close()

    async def stream(self):
        ws = await self.client.ws_connect("/stt/stream", headers=self.headers)
        self.assertEqual("ready", (await ws.receive_json())["type"])
        await ws.send_json({"type": "start", "sample_rate": 16000, "channels": 1, "format": "pcm_s16le"})
        return ws

    async def test_authentication_before_upgrade(self):
        with self.assertRaises(WSServerHandshakeError) as caught:
            await self.client.ws_connect("/stt/stream")
        self.assertEqual(401, caught.exception.status)
        self.assertEqual(0, self.recognizer.active)

    async def test_two_streams_keep_separate_state_and_overload_is_bounded(self):
        one, two = await self.stream(), await self.stream()
        try:
            with self.assertRaises(WSServerHandshakeError) as caught:
                await self.stream()
            self.assertEqual(429, caught.exception.status)
            await one.send_bytes(b"\0" * 100)
            await two.send_bytes(b"\0" * 200)
            self.assertEqual("samples 100", (await one.receive_json())["text"])
            self.assertEqual("samples 200", (await two.receive_json())["text"])
            await one.send_json({"type": "finish"})
            final = await one.receive_json()
            self.assertEqual("final 100", final["text"])
            self.assertEqual("final", final["type"])
        finally:
            await one.close()
            await two.close()
        for _ in range(100):
            if self.recognizer.active == 0:
                break
            await asyncio.sleep(.01)
        self.assertEqual(0, self.recognizer.active)
        self.assertEqual(2, self.recognizer.released)

    async def test_invalid_audio_fails_and_releases_slot(self):
        ws = await self.stream()
        await ws.send_bytes(b"\0")
        self.assertEqual("error", (await ws.receive_json())["type"])
        await ws.close()
        self.assertEqual(0, self.recognizer.active)

    async def test_audio_before_start_and_invalid_format_fail(self):
        for control in (None, {"type": "start", "sample_rate": 48000, "channels": 2, "format": "float"}):
            ws = await self.client.ws_connect("/stt/stream", headers=self.headers)
            await ws.receive_json()
            if control:
                await ws.send_json(control)
            else:
                await ws.send_bytes(b"\0\0")
            self.assertEqual("error", (await ws.receive_json())["type"])
            await ws.close()

    async def test_cleanup_failure_still_releases_capacity(self):
        async def failed_release(state):
            raise RuntimeError("Synthetic native cleanup failure")
        self.recognizer.release = failed_release
        with self.assertLogs("aiohttp.server", level="ERROR"):
            ws = await self.stream()
            await ws.send_json({"type": "finish"})
            self.assertEqual("final", (await ws.receive_json())["type"])
            await ws.close()
            for _ in range(100):
                if self.recognizer.active == 0:
                    break
                await asyncio.sleep(.01)
            # Let the handler finish logging its deliberately injected failure.
            await asyncio.sleep(.01)
        self.assertEqual(0, self.recognizer.active)


class NativeOwnershipTest(unittest.IsolatedAsyncioTestCase):
    async def test_cancel_during_creation_closes_native_result(self):
        entered, proceed, closed = threading.Event(), threading.Event(), threading.Event()

        class Stream:
            def start(self):
                entered.set()
                if not proceed.wait(5):
                    raise RuntimeError("Test did not release native worker")
            def add_listener(self, listener):
                pass
            def close(self):
                closed.set()

        class Model:
            def create_stream(self, **kwargs):
                return Stream()

        recognizer = Recognizer(Model())
        pending = asyncio.create_task(recognizer.create())
        try:
            self.assertTrue(await asyncio.to_thread(entered.wait, 5))
            pending.cancel()
            proceed.set()
            with self.assertRaises(asyncio.CancelledError):
                await pending
            self.assertTrue(closed.is_set())
        finally:
            proceed.set()
            recognizer.executor.shutdown(wait=True)


if __name__ == "__main__":
    unittest.main()
