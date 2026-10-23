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
    Path(home, 'config.yaml').write_text(yaml.safe_dump({
        'model': {'default': args.model, 'provider': 'custom', 'base_url': args.base_url,
                  'api_mode': 'chat_completions'},
        'agent': {'max_turns': 2}, 'plugins': {'enabled': ['bellpull']},
        'platform_toolsets': {'api_server': []}, 'memory': {'memory_enabled': False, 'user_profile_enabled': False},
    }))
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
                model = next(m for m in catalog['models'] if m['provider'] == 'custom' and m['model'] == args.model)
                assert model['configured'], model
                assert model['reasoning']['efforts'] == ['low', 'medium', 'xhigh'], model
                session = (await call('POST', '/api/sessions', {}))['session']['id']
                for effort in ('off', 'low', 'medium', 'xhigh'):
                    reasoning = {'enabled': effort != 'off'}
                    if effort != 'off': reasoning['effort'] = effort
                    selected = {'model': args.model, 'provider': 'custom', 'model_options': {'reasoning': reasoning}}
                    await call('PUT', f'/api/bellpull/sessions/{session}/model', selected)
                    stored = await call('GET', f'/api/bellpull/sessions/{session}/model')
                    assert stored['selection'] == selected, stored
                    body = {'input': 'Compute 17 times 23. Reply with only the number.', 'session_id': session,
                            **selected, 'require_model_lock': True,
                            'bellpull': {'version': 1, 'selection': selected, 'artifact_ids': []}}
                    headers = {'Idempotency-Key': 'reasoning-probe-' + effort}
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
                    assert wire['chat_template_kwargs']['enable_thinking'] == (effort != 'off'), wire
                    if effort != 'off': assert wire['reasoning_effort'] == effort, wire
                    replay = await call('POST', '/v1/runs', body, headers)
                    assert replay['run_id'] == run_id
                    assert len(requests) == before + 1
                    changed = json.loads(json.dumps(body))
                    changed['model_options']['reasoning'] = {'enabled': True, 'effort': 'medium' if effort != 'medium' else 'low'}
                    changed['bellpull']['selection']['model_options'] = changed['model_options']
                    async with client.post(origin + '/v1/runs', json=changed, headers=headers) as conflict:
                        assert conflict.status == 409, (conflict.status, await conflict.text())
                    row = {'effort': effort, 'wire': wire, 'persisted': True, 'completed': True, 'answer': '391',
                           'replayed_without_inference': True, 'changed_effort_rejected_for_same_key': True}
                    rows.append(row); print(json.dumps(row), flush=True)
                if args.output: Path(args.output).write_text(json.dumps(rows, indent=2) + '\n')
    finally:
        await adapter.disconnect(); manager.unload()
        from gateway import status
        flush = getattr(status, 'flush_runtime_status', None)
        if callable(flush): await asyncio.to_thread(flush)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--model', default='qwen38-flash-next')
    parser.add_argument('--output')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='bellpull-reasoning-') as home:
        os.environ.update(HERMES_HOME=home, OPENAI_BASE_URL=args.base_url, OPENAI_API_KEY='EMPTY',
                          BELLPULL_NTFY_URL='', HERMES_SKIP_UPDATE_CHECK='1')
        asyncio.run(probe(args, home))
