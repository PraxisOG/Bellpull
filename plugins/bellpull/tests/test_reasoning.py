"""Native Hermes request options -> plugin -> actual transport serialization."""
import json
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import httpx
from openai import OpenAI
from plugins.bellpull import model_controls, models_files
from plugins.bellpull.artifacts import ArtifactError


class ReasoningTest(unittest.TestCase):
    def test_exact_model_capabilities_and_validation(self):
        for model in ('qwen38-flash-next', 'Qwen/Qwen3.8-Flash-Next', 'Qwen/Qwen3.8-Flash-Next-FP8'):
            caps = models_files.reasoning_capabilities('custom', model)
            self.assertEqual(['low', 'medium', 'xhigh'], caps['efforts'])
            self.assertTrue(caps['can_enable']); self.assertTrue(caps['can_disable'])
            available = {'models': [{'model': model, 'provider': 'custom', 'configured': True, 'reasoning': caps}]}
            for choice in ({'enabled': False}, {'enabled': True}, *({'enabled': True, 'effort': e} for e in caps['efforts'])):
                value = {'model': model, 'provider': 'custom', 'model_options': {'reasoning': choice}}
                self.assertEqual(value, models_files.validate_selection(value, available)[0])
            for choice in ({'effort': 'high'}, {'effort': 'max'}, {'effort': 'low', 'enabled': False}, {'enabled': 'false'}):
                with self.assertRaises(ArtifactError):
                    models_files.validate_selection({'model': model, 'provider': 'custom', 'model_options': {'reasoning': choice}}, available)
                with self.assertRaises(ArtifactError):
                    models_files.request_overrides('custom', model, {'reasoning': choice})
        self.assertIsNone(models_files.local_qwen_efforts('custom', 'Qwen/Qwen3.5-0.8B'))
        self.assertIsNone(models_files.local_qwen_efforts('openrouter', 'qwen38-flash-next'))
        self.assertEqual({}, models_files.request_overrides('custom', 'qwen38-flash-next', {}))

    def test_stock_adapter_forwards_each_choice_to_actual_http_body_and_unloads(self):
        from agent.transports.chat_completions import ChatCompletionsTransport
        from providers import get_provider_profile
        created = []
        provider_defaults = {'temperature': .4, 'extra_body': {'top_k': 20, 'chat_template_kwargs': {'preserve_thinking': True}}}
        def create(requested_model=None, requested_provider=None, model_options=None, confirmed_runtime_lock=False):
            agent = SimpleNamespace(provider=requested_provider or 'custom', model=requested_model or 'qwen38-flash-next',
                                    request_overrides=provider_defaults)
            created.append(agent)
            return agent
        adapter = SimpleNamespace(_create_agent=create, _session_runtime_request_from_body=lambda x: x,
                                  _persist_session_runtime_lock=lambda *args: True,
                                  _runtime_request_from_persisted_session_lock=lambda *args: None)
        ready, stop = model_controls.wire(adapter)
        self.assertTrue(ready)
        wire_bodies = []
        def receive(request):
            wire_bodies.append(json.loads(request.content))
            return httpx.Response(200, json={'id': 'fixture', 'object': 'chat.completion', 'created': 0,
                'model': 'qwen38-flash-next', 'choices': [{'index': 0, 'finish_reason': 'stop',
                'message': {'role': 'assistant', 'content': '391'}}]})
        try:
            with OpenAI(base_url='http://fixture.invalid/v1', api_key='synthetic-fixture-key',
                        http_client=httpx.Client(transport=httpx.MockTransport(receive))) as client:
                for effort in ('low', 'medium', 'xhigh', 'off', 'on', 'default'):
                    choice = {} if effort == 'default' else {'reasoning': {'enabled': effort != 'off'}}
                    if effort in ('low', 'medium', 'xhigh'): choice['reasoning']['effort'] = effort
                    agent = adapter._create_agent(requested_model='qwen38-flash-next', requested_provider='custom',
                                                  model_options=choice, confirmed_runtime_lock=True)
                    kwargs = ChatCompletionsTransport().build_kwargs(agent.model, [{'role': 'user', 'content': 'Fixture'}],
                        provider_profile=get_provider_profile('custom'), reasoning_config=choice.get('reasoning'),
                        supports_reasoning=True, request_overrides=agent.request_overrides)
                    client.chat.completions.create(**kwargs)
                    body = wire_bodies[-1]; template = body['chat_template_kwargs']
                    self.assertNotIn('extra_body', body)  # SDK flattens this onto vLLM's request.
                    self.assertEqual(20, body['top_k']); self.assertTrue(template['preserve_thinking'])
                    if effort in ('low', 'medium', 'xhigh'):
                        self.assertEqual(effort, body['reasoning_effort'])
                        self.assertEqual(effort, template['reasoning_effort'])
                        self.assertTrue(template['enable_thinking'])
                    elif effort in ('off', 'on'):
                        self.assertEqual(effort == 'on', template['enable_thinking'])
                        self.assertNotIn('reasoning_effort', template)
                    else:
                        self.assertNotIn('enable_thinking', template)
            self.assertNotIn('enable_thinking', provider_defaults['extra_body']['chat_template_kwargs'])
            self.assertEqual('low', created[0].request_overrides['reasoning_effort'])
            self.assertEqual('xhigh', created[2].request_overrides['reasoning_effort'])
        finally:
            stop()
        self.assertIs(adapter._create_agent, create)

    def test_unknown_host_is_not_modified(self):
        adapter = SimpleNamespace(_create_agent=lambda: None)
        original = adapter._create_agent
        supported, stop = model_controls.wire(adapter)
        self.assertFalse(supported); stop(); self.assertIs(original, adapter._create_agent)
