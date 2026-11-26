import gc
import json
from types import SimpleNamespace
import unittest
import weakref

from plugins.bellpull.memory_status import observe


class Provider:
    name = "memory-subsystem"
    session_id = "one"

    def __init__(self):
        self.calls = []
        self.block = {"created": 150.0, "body": "private context"}
        self.result = '{"records":0,"access":{"owner_memory":"unavailable"}}'

    def handle_tool_call(self, name, args, **kwargs):
        self.calls.append((name, args, kwargs))
        return self.result


class Agent:
    session_id = "one"


class MemoryStatusTest(unittest.TestCase):
    def setUp(self):
        self.provider = Provider()
        self.agent = Agent()
        self.agent._memory_manager = SimpleNamespace(get_provider=lambda name: self.provider)
        self.active = True

    def attach(self):
        observe(self.agent, lambda: self.active)

    def status(self):
        return self.provider.handle_tool_call("memory_status", {}, source="native")

    def test_receipt_preserves_native_access_and_does_not_include_memory(self):
        self.attach()
        self.attach()
        result = json.loads(self.status())
        self.assertEqual(result["access"], {"owner_memory": "unavailable"})
        self.assertEqual(result["records"], 0)
        receipt = result.pop("bellpull_memory")
        self.assertEqual(set(receipt), {"version", "provider", "observed_at", "context_created_at"})
        self.assertEqual(receipt["provider"], "memory-subsystem")
        self.assertEqual(receipt["context_created_at"], 150)
        self.assertGreaterEqual(receipt["observed_at"], 150)
        self.assertEqual(self.provider.calls, [("memory_status", {}, {"source": "native"})])

    def test_unavailable_snapshot_time_stays_unknown(self):
        self.attach()
        for created in (None, False, "old", -10, float("nan"), float("inf"), 10**16):
            with self.subTest(created=created):
                self.provider.block = {"created": created}
                self.assertNotIn("context_created_at", json.loads(self.status())["bellpull_memory"])

    def test_errors_and_unrecognized_results_pass_through(self):
        self.attach()
        for result in ("a result", "[]", '{}', '{"access":{"owner_memory":"unknown"}}',
                       '{"error":"denied","access":{"owner_memory":"available"}}',
                       '{"status":"blocked","access":{"owner_memory":"available"}}'):
            self.provider.result = result
            self.assertEqual(self.status(), result)

    def test_unload_mismatched_session_and_other_tools_pass_through(self):
        self.attach()
        self.assertEqual(self.provider.handle_tool_call("memory_read", {"ref": "test"}), self.provider.result)
        self.active = False
        self.assertEqual(self.status(), self.provider.result)
        self.active = True
        self.provider.session_id = "two"
        self.assertEqual(self.status(), self.provider.result)
        self.agent.session_id = "two"
        self.attach()
        self.assertIn("bellpull_memory", json.loads(self.status()))
        self.assertEqual(len(self.provider.calls), 4)

    def test_missing_or_different_provider_is_untouched(self):
        self.agent._memory_manager.get_provider = lambda name: None
        self.attach()
        self.agent._memory_manager.get_provider = lambda name: self.provider
        self.provider.name = "other"
        self.attach()
        self.assertEqual(self.status(), self.provider.result)

    def test_cached_provider_does_not_retain_its_agent(self):
        agent_ref = weakref.ref(self.agent)
        self.attach()
        del self.agent
        gc.collect()
        self.assertIsNone(agent_ref())
