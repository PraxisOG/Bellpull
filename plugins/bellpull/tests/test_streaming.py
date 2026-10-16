import asyncio
import unittest
from unittest.mock import patch
from plugins.bellpull.streaming import wire


class StreamingTest(unittest.IsolatedAsyncioTestCase):
    def adapter(self):
        class Adapter:
            def __init__(self):
                self._run_streams = {"one": asyncio.Queue(), "two": asyncio.Queue()}
                self.progress = []
            def _make_run_event_callback(self, run_id, loop):
                return lambda event, *args, **kwargs: self.progress.append((run_id, event, args))
            def _create_agent(self, tool_progress_callback=None, reasoning_callback=None):
                return tool_progress_callback, reasoning_callback
        return Adapter()

    async def test_unload_restores_callbacks_and_fences_already_created_agents(self):
        adapter = self.adapter()
        original_create, original_progress = adapter._create_agent, adapter._make_run_event_callback
        ready, stop = wire(adapter)
        self.assertTrue(ready)
        progress = adapter._make_run_event_callback("one", asyncio.get_running_loop())
        _, reasoning = adapter._create_agent(tool_progress_callback=progress)
        reasoning("Before unload")
        await asyncio.sleep(0)
        self.assertEqual("Before unload", adapter._run_streams["one"].get_nowait()["text"])
        stop()
        reasoning("After unload")
        await asyncio.sleep(0)
        self.assertTrue(adapter._run_streams["one"].empty())
        self.assertEqual(original_create, adapter._create_agent)
        self.assertEqual(original_progress, adapter._make_run_event_callback)

    async def test_existing_reasoning_callback_and_other_surfaces_are_preserved(self):
        adapter = self.adapter()
        _, stop = wire(adapter)
        try:
            progress = adapter._make_run_event_callback("one", asyncio.get_running_loop())
            explicit = lambda text: None
            _, reasoning = adapter._create_agent(progress, explicit)
            self.assertIs(explicit, reasoning)
            progress("reasoning.available", "_thinking", "Native preview", None)
            self.assertEqual("reasoning.available", adapter.progress[-1][1])
            _, reasoning = adapter._create_agent(tool_progress_callback=lambda *args: None)
            self.assertIsNone(reasoning)
        finally:
            stop()

    async def test_runs_remain_separate_and_native_preview_falls_back_per_response(self):
        adapter = self.adapter()
        _, stop = wire(adapter)
        try:
            one = adapter._make_run_event_callback("one", asyncio.get_running_loop())
            two = adapter._make_run_event_callback("two", asyncio.get_running_loop())
            _, think_one = adapter._create_agent(one)
            _, think_two = adapter._create_agent(two)
            think_one("one"); think_two("two")
            one("reasoning.available", "_thinking", "duplicate", None)
            one("reasoning.available", "_thinking", "Next non-streaming response", None)
            one("tool.started", "read_file", "notes.md", None)
            await asyncio.sleep(0)
            self.assertEqual("one", adapter._run_streams["one"].get_nowait()["text"])
            self.assertEqual("two", adapter._run_streams["two"].get_nowait()["text"])
            self.assertEqual(["reasoning.available", "tool.started"], [event[1] for event in adapter.progress])
        finally:
            stop()

    async def test_unsupported_adapter_keeps_its_original_behavior(self):
        adapter = self.adapter()
        adapter._create_agent = lambda: None
        original = adapter._create_agent
        ready, stop = wire(adapter)
        self.assertFalse(ready)
        stop()
        self.assertIs(original, adapter._create_agent)

    async def test_older_native_factory_connects_the_existing_agent_callback_attribute(self):
        adapter = self.adapter()
        class Agent:
            reasoning_callback = None
        adapter._create_agent = lambda tool_progress_callback=None: Agent()
        with patch("run_agent.AIAgent", lambda reasoning_callback=None: None):
            ready, stop = wire(adapter)
        self.assertTrue(ready)
        try:
            progress = adapter._make_run_event_callback("one", asyncio.get_running_loop())
            agent = adapter._create_agent(tool_progress_callback=progress)
            agent.reasoning_callback("Older stock reasoning")
            await asyncio.sleep(0)
            self.assertEqual("Older stock reasoning", adapter._run_streams["one"].get_nowait()["text"])
        finally:
            stop()
