#!/usr/bin/env python3
"""Exercise installed Bellpull -> stock Hermes -> an explicit vLLM test endpoint.

Run inside a disposable Hermes container/home. Uses only a fixed public arithmetic
prompt, disables tools, records selected wire controls (never credentials/prompts),
and checks native admission replay. Does not change a personal Hermes installation.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import tempfile
from unittest.mock import patch


async def probe(args, home):
    import yaml
    config = {
        'model': {'default': args.model, 'provider': args.provider, 'base_url': args.base_url,
                  'api_mode': 'chat_completions'},
        'agent': {'max_turns': 2}, 'plugins': {'enabled': ['bellpull']},
        'platform_toolsets': {'api_server': []}, 'memory': {'memory_enabled': False, 'user_profile_enabled': False},
    }
    config['providers'] = {args.provider: {'base_url': args.base_url, 'api_key': 'EMPTY',
        'transport': 'chat_completions', 'default_model': args.model, 'models': {args.model: {}}}}
    Path(home, 'config.yaml').write_text(yaml.safe_dump(config))
    from aiohttp import ClientSession
    from gateway.config import PlatformConfig
    from gateway.platforms.api_server import APIServerAdapter
    from hermes_cli.plugins import get_plugin_manager
    from agent.transports.chat_completions import ChatCompletionsTransport

    manager = get_plugin_manager(); manager.discover_and_load()
    key = 'isolated-reasoning-probe-key'  # Synthetic, disposable loopback fixture.
    adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={'key': key, 'host': '127.0.0.1', 'port': 18649}))
    original = ChatCompletionsTransport.build_kwargs
    requests = []
    def capture(self, *a, **kw):
        result = original(self, *a, **kw)
        requests.append({'model': result.get('model'), 'reasoning_effort': result.get('reasoning_effort'),
                         'chat_template_kwargs': (result.get('extra_body') or {}).get('chat_template_kwargs')})
        return result
    rows = []
    try:
        with patch.object(ChatCompletionsTransport, 'build_kwargs', capture), \
             patch('hermes_cli.tools_config._get_platform_tools', return_value=[]):
            assert await adapter.connect()
            origin = 'http://127.0.0.1:18649'
            async with ClientSession(headers={'Authorization': 'Bearer ' + key}) as client:
                async def call(method, path, body=None, headers=None):
                    async with client.request(method, origin + path, json=body, headers=headers) as response:
                        value = await response.json()
                        if response.status >= 400:
                            raise AssertionError((path, response.status, value))
                        return value
                caps = await call('GET', '/api/bellpull/capabilities')
                assert caps['features']['bellpull_native_model_controls'], caps
                assert not caps['features']['bellpull_model_controls'], caps
                assert not caps['features']['bellpull_run_attachments'], caps
                catalog = await call('GET', '/api/bellpull/models')
                model = next(m for m in catalog['models'] if m['provider'] == args.provider and m['model'] == args.model)
                assert model['configured'], model
                assert model['reasoning']['efforts'] == ['low', 'medium', 'xhigh'], model
                session = (await call('POST', '/api/sessions', {}))['session']['id']
                base_selection = {'model': args.model, 'provider': args.provider, 'model_options': {}}
                selection_url = f'/api/bellpull/sessions/{session}/model'
                await call('PUT', selection_url, base_selection)
                for unsupported in ('minimal', 'high', 'max'):
                    bad = dict(base_selection, model_options={'reasoning': {'effort': unsupported}})
                    async with client.put(origin + selection_url, json=bad) as rejected:
                        assert rejected.status == 409, (rejected.status, await rejected.text())
                    assert (await call('GET', selection_url))['selection'] == base_selection
                assert not requests, 'Invalid saves must not start inference'
                # Reproduce a lock persisted by plugin 0.6.1 without exposing it
                # through the fixed validator. This is a disposable SQLite home.
                old = dict(base_selection, model_options={'reasoning': {'effort': 'high'}})
                assert adapter._persist_session_runtime_lock(session,
                    adapter._session_runtime_request_from_body(dict(old, require_model_lock=True)))
                async def session_stream(message):
                    async with client.post(origin + f'/api/sessions/{session}/chat/stream',
                                           json={'message': message}) as response:
                        assert response.status == 200, (response.status, await response.text())
                        return await asyncio.wait_for(response.text(), 120)
                failed = await session_stream('Synthetic invalid-choice check.')
                assert 'Thinking level' in failed and 'Default' in failed, failed
                assert not requests, 'An old invalid lock must fail before inference'
                print(json.dumps({'unsupported_saves_rejected': ['minimal', 'high', 'max'],
                    'previous_selection_preserved': True, 'old_invalid_lock_has_reset_instruction': True}), flush=True)
                for effort in ('default', 'off', 'on', 'low', 'medium', 'xhigh'):
                    reasoning = {'enabled': effort != 'off'}
                    if effort in ('low', 'medium', 'xhigh'): reasoning['effort'] = effort
                    selected = {'model': args.model, 'provider': args.provider,
                                'model_options': {} if effort == 'default' else {'reasoning': reasoning}}
                    await call('PUT', f'/api/bellpull/sessions/{session}/model', selected)
                    stored = await call('GET', f'/api/bellpull/sessions/{session}/model')
                    assert stored['selection'] == selected, stored
                    body = {'input': 'Compute 17 times 23. Reply with only the number.', 'session_id': session,
                            **selected, 'require_model_lock': True,
                            'bellpull': {'version': 1, 'selection': selected, 'artifact_ids': []}}
                    headers = {'Idempotency-Key': 'reasoning-probe-' + effort}
                    if effort == 'default':
                        recovered = await session_stream('Compute 17 times 23. Reply with only the number.')
                        assert '391' in recovered and 'Thinking level' not in recovered, recovered
                        assert len(requests) == 1, requests
                        print(json.dumps({'old_locked_session_recovered_through_native_chat_stream': True}), flush=True)
                    before = len(requests)
                    admission = await call('POST', '/v1/runs', body, headers)
                    run_id = admission['run_id']
                    for _ in range(240):
                        status = await call('GET', '/v1/runs/' + run_id)
                        if status['status'] in ('completed', 'failed', 'cancelled'): break
                        await asyncio.sleep(.5)
                    assert status['status'] == 'completed', status
                    assert (status.get('output') or '').strip() == '391', status
                    assert len(requests) == before + 1, requests[before:]
                    wire = requests[-1]
                    if effort == 'default':
                        assert 'enable_thinking' not in (wire['chat_template_kwargs'] or {}), wire
                    else:
                        assert wire['chat_template_kwargs']['enable_thinking'] == (effort != 'off'), wire
                    if effort in ('low', 'medium', 'xhigh'): assert wire['reasoning_effort'] == effort, wire
                    replay = await call('POST', '/v1/runs', body, headers)
                    assert replay['run_id'] == run_id
                    assert len(requests) == before + 1
                    changed = json.loads(json.dumps(body))
                    changed['model_options']['reasoning'] = {'enabled': True, 'effort': 'medium' if effort != 'medium' else 'low'}
                    changed['bellpull']['selection']['model_options'] = changed['model_options']
                    async with client.post(origin + '/v1/runs', json=changed, headers=headers) as conflict:
                        assert conflict.status == 409, (conflict.status, await conflict.text())
                    row = {'provider': args.provider, 'model': args.model, 'effort': effort, 'wire': wire, 'persisted': True, 'completed': True, 'answer': '391',
                           'replayed_without_inference': True, 'changed_effort_rejected_for_same_key': True}
                    rows.append(row); print(json.dumps(row), flush=True)
                if args.output: Path(args.output).write_text(json.dumps(rows, indent=2) + '\n')
    finally:
        await adapter.disconnect(); manager.unload()
        from gateway import status
        flush = getattr(status, 'flush_runtime_status', None)
        if callable(flush): await asyncio.to_thread(flush)


async def run_probe(args, home):
    if not args.backend_model:
        return await probe(args, home)
    # Native Hermes may query model metadata synchronously during agent creation.
    # Give the fixture relay its own thread so that cannot block its event loop.
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from threading import Thread
    from urllib.request import Request, urlopen
    from urllib.error import HTTPError
    class Relay(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass
        def do_GET(self):
            if self.path != '/v1/models':
                self.send_error(404); return
            data = json.dumps({'object': 'list', 'data': [
                {'id': args.model, 'object': 'model', 'owned_by': 'fixture-relay', 'created': 0}]}).encode()
            self.send_response(200); self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
        def do_POST(self):
            if self.path != '/v1/chat/completions':
                self.send_error(404); return
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            if body.get('model') != args.model:
                self.send_error(400, 'Unknown fixture alias'); return
            body['model'] = args.backend_model
            request = Request(args.base_url.rstrip('/') + '/chat/completions', json.dumps(body).encode(),
                              headers={'Authorization': 'Bearer EMPTY', 'Content-Type': 'application/json'})
            try:
                response = urlopen(request, timeout=120)
            except HTTPError as error:
                response = error
            with response:
                self.send_response(response.status)
                self.send_header('Content-Type', response.headers.get('Content-Type', 'application/json'))
                self.send_header('Connection', 'close'); self.end_headers()
                while chunk := response.read(4096):
                    self.wfile.write(chunk); self.wfile.flush()
    relay = ThreadingHTTPServer(('127.0.0.1', 0), Relay)
    thread = Thread(target=relay.serve_forever, daemon=True); thread.start()
    try:
        forwarded = argparse.Namespace(**(vars(args) | {'base_url': f'http://127.0.0.1:{relay.server_port}/v1'}))
        os.environ['OPENAI_BASE_URL'] = forwarded.base_url
        await probe(forwarded, home)
    finally:
        await asyncio.to_thread(relay.shutdown)
        relay.server_close(); thread.join(timeout=5)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--model', default='qwen38-flash-next')
    parser.add_argument('--provider', default='custom')
    parser.add_argument('--backend-model', help='Use a disposable model-alias relay to this actual backend model')
    parser.add_argument('--output')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='bellpull-reasoning-') as home:
        os.environ.update(HERMES_HOME=home, OPENAI_BASE_URL=args.base_url, OPENAI_API_KEY='EMPTY',
                          BELLPULL_NTFY_URL='', HERMES_SKIP_UPDATE_CHECK='1')
        asyncio.run(run_probe(args, home))
