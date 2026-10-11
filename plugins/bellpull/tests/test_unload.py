import asyncio
import unittest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from plugins.bellpull.notifications import Relay


class UnloadTest(unittest.IsolatedAsyncioTestCase):
    async def test_unload_cancels_real_open_broker_subscription(self):
        release = asyncio.Event()
        async def broker_stream(request):
            response = web.StreamResponse(headers={"Content-Type": "application/x-ndjson"})
            await response.prepare(request)
            await response.write(b'{"event":"open"}\n')
            await release.wait()
            return response
        broker = web.Application()
        broker.router.add_get('/{topic}/json', broker_stream)
        upstream = TestServer(broker)
        await upstream.start_server()
        relay = Relay(str(upstream.make_url('')).rstrip('/'))
        app = web.Application()
        app.router.add_get('/events', lambda request: relay.events('fixture-scope', request))
        client = TestClient(TestServer(app))
        await client.start_server()
        try:
            response = await client.get('/events')
            self.assertEqual(200, response.status)
            self.assertEqual(1, len(relay.subscriptions))
            relay.stop()
            await asyncio.wait_for(relay.close(), 2)
            self.assertFalse(relay.subscriptions)
            self.assertTrue(relay.closed)
            response.close()
        finally:
            release.set()
            await relay.close()
            await client.close()
            await upstream.close()
