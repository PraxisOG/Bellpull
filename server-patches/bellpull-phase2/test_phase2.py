import asyncio
import json
import os
import re
import tempfile
import unittest
from unittest.mock import AsyncMock
os.makedirs(os.environ['HERMES_HOME'], exist_ok=True)
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from gateway.config import PlatformConfig
from gateway.platforms.api_server import APIServerAdapter
from gateway.platforms import api_server_bellpull as relay
from tools.approval import request_tool_approval


class Phase2Test(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.executions = []
        self.published = []
        self.adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={'key': 'isolated-phase2-key'}))  # gitleaks:allow -- isolated test fixture only
        self.adapter._conversation_history_for_session = AsyncMock(return_value=[])
        self.adapter._request_route_conflict_error = lambda **kwargs: None
        owner = self
        class FixtureAgent:
            session_id = 'fixture'
            def run_conversation(self, **kwargs):
                decision = request_tool_approval('phase2_fixture', 'Harmless isolated approval verification')
                if decision.get('approved'):
                    owner.executions.append(kwargs['user_message'])
                return {'final_response': json.dumps(decision)}
        self.adapter._create_agent = lambda **kwargs: FixtureAgent()
        async def publish(request):
            if not re.fullmatch(r'[-_A-Za-z0-9]{1,64}', request.match_info['topic']):
                return web.Response(status=404)
            self.published.append((request.match_info['topic'], await request.json()))
            return web.json_response({'ok': True})
        async def subscribe(request):
            if not re.fullmatch(r'[-_A-Za-z0-9]{1,64}', request.match_info['topic']):
                return web.Response(status=404)
            return web.Response(text=json.dumps({'event': 'message', 'message': json.dumps({
                'run_id': 'run_fixture', 'session_id': 'fixture', 'status': 'completed',
                'untrusted_extra': 'must not be forwarded'})}) + '\n', content_type='application/x-ndjson')
        broker = web.Application()
        broker.router.add_post('/{topic}', publish)
        broker.router.add_get('/{topic}/json', subscribe)
        self.broker = TestServer(broker)
        await self.broker.start_server()
        relay.NTFY = str(self.broker.make_url('')).rstrip('/')
        app = web.Application()
        app.router.add_post('/v1/runs', self.adapter._handle_runs)
        app.router.add_get('/v1/runs/{run_id}', self.adapter._handle_get_run)
        app.router.add_post('/v1/runs/{run_id}/approval', self.adapter._handle_run_approval)
        for method, path, handler in relay.routes(self.adapter):
            app.router.add_route(method, path, handler)
        self.client = TestClient(TestServer(app))
        await self.client.start_server()
        self.headers = {'Authorization': 'Bearer isolated-phase2-key'}

    async def asyncTearDown(self):
        from tools.approval import unregister_gateway_notify
        for key in list(self.adapter._run_approval_sessions.values()):
            unregister_gateway_notify(key)
        tasks = list(self.adapter._active_run_tasks.values())
        if tasks:
            await asyncio.wait_for(asyncio.gather(*tasks, return_exceptions=True), 5)
        await self.client.close()
        await self.broker.close()
        self.adapter._run_idempotency_store.close()

    async def submit(self, key, interactive=True, text='fixture'):
        response = await self.client.post('/v1/runs', json={'input': text, 'session_id': 'fixture', 'interactive_approvals': interactive}, headers={**self.headers, 'Idempotency-Key': key})
        return response.status, await response.json()

    async def status(self, run_id, expected):
        for _ in range(200):
            response = await self.client.get('/v1/runs/' + run_id, headers=self.headers)
            value = await response.json()
            if value.get('status') == expected:
                return value
            await asyncio.sleep(.01)
        self.fail(str(value))

    async def test_real_gate_accepts_once_and_rejects_replay(self):
        status, accepted = await self.submit('accept-' + str(id(self)))
        self.assertEqual(202, status, accepted)
        run_id = accepted['run_id']
        waiting = await self.status(run_id, 'waiting_for_approval')
        request_id = waiting['approval']['request_id']
        self.assertTrue(request_id)
        stale = await self.client.post('/v1/runs/' + run_id + '/approval', headers=self.headers, json={'request_id': 'stale', 'choice': 'once'})
        self.assertEqual(409, stale.status)
        self.assertEqual([], self.executions)
        body = {'request_id': request_id, 'choice': 'once', 'resolve_all': False}
        response = await self.client.post('/v1/runs/' + run_id + '/approval', headers=self.headers, json=body)
        self.assertEqual(200, response.status, await response.text())
        await self.status(run_id, 'completed')
        repeat = await self.client.post('/v1/runs/' + run_id + '/approval', headers=self.headers, json=body)
        self.assertEqual(409, repeat.status)
        self.assertEqual(['fixture'], self.executions)
        await asyncio.sleep(.1)
        self.assertTrue(self.published)
        self.assertTrue(all(set(item[1]) == {'run_id', 'session_id', 'status'} for item in self.published))

    async def test_unattended_run_stays_denied_beside_interactive_run(self):
        _, first = await self.submit('interactive-' + str(id(self)))
        waiting = await self.status(first['run_id'], 'waiting_for_approval')
        _, second = await self.submit('unattended-' + str(id(self)), interactive=False)
        settled = await self.status(second['run_id'], 'completed')
        self.assertFalse(json.loads(settled['output'])['approved'])
        self.assertEqual([], self.executions)
        response = await self.client.post('/v1/runs/' + first['run_id'] + '/approval', headers=self.headers,
            json={'request_id': waiting['approval']['request_id'], 'choice': 'deny'})
        self.assertEqual(200, response.status)
        await self.status(first['run_id'], 'completed')
        self.assertEqual([], self.executions)

    async def test_idempotency_same_payload_reuses_run_changed_payload_conflicts(self):
        key = 'idempotency-' + str(id(self))
        _, first = await self.submit(key, interactive=False)
        _, replay = await self.submit(key, interactive=False)
        self.assertEqual(first['run_id'], replay['run_id'])
        self.assertTrue(replay['replayed'])
        status, conflict = await self.submit(key, interactive=True)
        self.assertEqual(409, status)
        self.assertEqual('idempotency_key_conflict', conflict['error']['code'])
        await self.status(first['run_id'], 'completed')

    async def test_notification_proxy_requires_auth_before_broker_access(self):
        response = await self.client.get('/api/bellpull/events')
        self.assertEqual(401, response.status)
        self.assertEqual([], self.published)

    async def test_notification_subscription_uses_valid_topic_and_filters_hint(self):
        response = await self.client.get('/api/bellpull/events', headers=self.headers)
        self.assertEqual(200, response.status)
        frames = await response.text()
        self.assertIn('event: bellpull.wake', frames)
        hint = json.loads(next(line[6:] for line in frames.splitlines() if line.startswith('data: ')))
        self.assertEqual({'run_id': 'run_fixture', 'session_id': 'fixture', 'status': 'completed'}, hint)
        self.assertRegex(relay.topic('scope-a'), r'^[-_A-Za-z0-9]{1,64}$')
        self.assertNotEqual(relay.topic('scope-a'), relay.topic('scope-b'))

    async def test_completed_identity_survives_adapter_restart(self):
        key = 'restart-' + str(id(self))
        _, first = await self.submit(key, interactive=False)
        await self.status(first['run_id'], 'completed')
        fresh = APIServerAdapter(PlatformConfig(enabled=True, extra={'key': 'isolated-phase2-key'}))  # gitleaks:allow -- isolated test fixture only
        fresh._request_route_conflict_error = lambda **kwargs: None
        app = web.Application()
        app.router.add_post('/v1/runs', fresh._handle_runs)
        restarted = TestClient(TestServer(app))
        await restarted.start_server()
        try:
            response = await restarted.post('/v1/runs', headers={**self.headers, 'Idempotency-Key': key},
                json={'input': 'fixture', 'session_id': 'fixture', 'interactive_approvals': False})
            replay = await response.json()
            self.assertEqual(202, response.status, replay)
            self.assertEqual(first['run_id'], replay['run_id'])
            self.assertTrue(replay['replayed'])
            self.assertEqual({}, fresh._active_run_tasks)
        finally:
            await restarted.close()
            fresh._run_idempotency_store.close()

    async def test_dead_owner_is_interrupted_without_starting_an_executor(self):
        import hashlib, time
        scope = hashlib.sha256(b'default\0isolated-phase2-key').hexdigest()
        run_id = 'run_dead_owner_fixture'
        self.adapter._run_idempotency_store.reserve(scope, 'dead-owner', 'fixture-fingerprint', run_id,
            {'run_id': run_id, 'status': 'running', 'session_id': 'fixture', 'created_at': time.time()},
            owner_pid=99999999, owner_started=0)
        result = await self.status(run_id, 'interrupted')
        self.assertIn('restarted', result['error'])
        self.assertEqual({}, self.adapter._active_run_tasks)

    async def test_invalid_opt_in_rejected(self):
        status, body = await self.submit('invalid-' + str(id(self)), interactive='yes')
        self.assertEqual(400, status)
        self.assertEqual([], self.executions)

if __name__ == '__main__':
    unittest.main()
