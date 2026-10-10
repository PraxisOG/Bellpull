"""Run in the Hermes test image with this plugin mounted at /tmp/bellpull_gpu."""
import os
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch

try:
    from aiohttp import web
    from aiohttp.test_utils import TestClient, TestServer
    from gateway.config import PlatformConfig
    from gateway.platforms.api_server import APIServerAdapter
    sys.path.insert(0, '/tmp')
    import bellpull_gpu
    AVAILABLE = True
except ImportError:
    AVAILABLE = False


@unittest.skipUnless(AVAILABLE, "Requires Hermes/aiohttp test image")
class PluginHttpTest(unittest.IsolatedAsyncioTestCase):
    async def test_requires_native_bearer_before_sampling_and_fails_closed_without_key(self):
        os.makedirs(os.environ['HERMES_HOME'], exist_ok=True)
        adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={'key': 'isolated-gpu-fixture-key'}))  # gitleaks:allow
        calls = []
        class Sample:
            async def read(self):
                calls.append(True)
                return {'version': 1, 'cards': [], 'checked_at': 100, 'errors': ['Fixture: no GPU']}
        app = web.Application()
        def factory(platform, callback):
            self.assertEqual('api_server', platform)
            callback(app, adapter)
        with patch.object(bellpull_gpu, 'Sampler', Sample):
            bellpull_gpu.register(SimpleNamespace(register_platform_handler=factory))
        client = TestClient(TestServer(app))
        await client.start_server()
        try:
            path = '/api/bellpull/plugins/gpu'
            self.assertEqual(401, (await client.get(path)).status)
            self.assertEqual([], calls)
            response = await client.get(path, headers={'Authorization': 'Bearer isolated-gpu-fixture-key'})
            self.assertEqual(200, response.status)
            self.assertEqual('no-store', response.headers['Cache-Control'])
            self.assertEqual([True], calls)
            adapter._api_key = ''
            self.assertEqual(401, (await client.get(path)).status)
            self.assertEqual([True], calls)
        finally:
            await client.close()
            adapter._run_idempotency_store.close()


if __name__ == '__main__':
    unittest.main()
