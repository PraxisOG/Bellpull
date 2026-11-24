import json
import unittest

from plugins.bellpull.coding import launch_transport
from plugins.bellpull.coding_executors import consume_frames, observed_rows


class ExecutorEventsTest(unittest.TestCase):
    def setUp(self):
        self.session = "6181c478-e0f6-4b19-acaa-986b2b6e06ce"
        self.other = "4e900987-8192-4e64-9b6d-7040141eda8e"

    def test_structured_formats_require_the_actual_program_and_option(self):
        self.assertEqual("codex_json", launch_transport("/opt/tools/codex exec --json 'check the toy project'"))
        self.assertEqual("claude_stream_json", launch_transport("claude --print --verbose --output-format=stream-json fixture"))
        for command in ("echo 'codex exec --json'", "codex --version", "codex exec -- --json",
                        "claude -p 'stream-json'", "claude -p --output-format text",
                        "printf text | claude -p --output-format stream-json"):
            self.assertIsNone(launch_transport(command), command)

    def test_codex_binds_first_thread_updates_items_and_keeps_foreign_output_separate(self):
        work = {"transport":"codex_json"}
        def emit(value, at=0):
            return observed_rows(work, json.dumps(value), at)
        item = dict(id="item_2", type="command_execution", command="python -m unittest",
                    aggregated_output="A fixture assertion failed.", exit_code=1)
        self.assertEqual([], emit(dict(type="item.completed", item=item)))
        emit({"type":"thread.started", "thread_id":self.session})
        start, = emit(dict(type="item.started", item={**item, "exit_code":None, "aggregated_output":""}), 20)
        end, = emit(dict(type="item.completed", item=item), 300)
        self.assertEqual(start["key"], end["key"])
        self.assertEqual("exit 1", end["outcome"])
        from agent import redact
        if hasattr(redact, "redact_for_egress"):
            self.assertEqual("python -m unittest", end["command"])
        else:
            self.assertNotIn("command", end)
        self.assertEqual(self.session, work["executor_session_id"])
        self.assertFalse(emit({"type":"thread.started","thread_id":self.other}))
        self.assertTrue(work["gap"])
        self.assertFalse(emit(dict(type="item.completed", item=item)))
        self.assertFalse(emit({"type":"approval_request","state":"approved"}))

    def test_claude_binds_init_and_publishes_only_display_evidence(self):
        work = {"transport":"claude_stream_json"}
        def emit(value):
            return observed_rows(work, json.dumps(value), 0)
        started, = emit(dict(type="system",subtype="init",session_id=self.session,apiKeySource="private fixture"))
        self.assertNotIn("private fixture",json.dumps(started))
        content = [dict(type="text",text="Ran the fixture."),dict(type="tool_use",id="toolu_1",name="Bash",input={"private":"hidden"})]
        rows = emit(dict(type="assistant",session_id=self.session,message=dict(id="msg_1",content=content)))
        self.assertEqual(["text","tool"], [r["kind"] for r in rows])
        self.assertNotIn("hidden",json.dumps(rows))
        self.assertFalse(emit(dict(type="assistant",session_id=self.other,message=dict(id="msg_1",content=content))))
        self.assertFalse(emit(dict(type="control_request",session_id=self.session,request={"subtype":"can_use_tool"})))
        failed, = emit(dict(type="result",subtype="error_during_execution",session_id=self.session,is_error=True))
        self.assertEqual("error",failed["kind"])

    def test_restart_replays_partial_frame_and_accepts_unterminated_final_frame(self):
        work = dict(transport="codex_json", observed_total=0)
        started = json.dumps(dict(type="thread.started", thread_id=self.session)) + "\n"
        item = json.dumps(dict(type="item.completed", item=dict(
            type="agent_message", id="item_receipt", text="before\u2028after")), ensure_ascii=False)
        output = []
        def emit(_, value):
            output.append(value["key"])
        first = started + item[:25]
        consume_frames(work, len(first), first, False, emit, 1000)
        self.assertEqual(len(started), work["observed_total"])
        work = json.loads(json.dumps(work))
        full = started + item
        consume_frames(work, len(full), full, True, emit, 1000)
        consume_frames(work, len(full), full, True, emit, 1000)
        self.assertEqual(["thread", "item:item_receipt"], output)
        self.assertEqual(len(full), work["observed_total"])

    def test_lost_or_oversize_frame_waits_for_original_codex_identity(self):
        work = dict(transport="codex_json", observed_total=0, executor_session_id=self.session)
        output = []
        def emit(_, value):
            output.append(value["key"])
        tail = "x" * 150
        total = 1000
        consume_frames(work, total, tail, False, emit, 120)
        self.assertTrue(work["gap"])
        suffix = '\n{"type":"turn.started"}\n'
        consume_frames(work, total + len(suffix), suffix, False, emit, 120)
        self.assertEqual([], output)
        started = json.dumps(dict(type="thread.started", thread_id=self.session)) + "\n"
        total += len(suffix)
        consume_frames(work, total + len(started), started, False, emit, 120)
        self.assertEqual(["thread"], output)
        long_frame = "x" * 121 + "\n"
        total += len(started)
        consume_frames(work, total + len(long_frame), long_frame, False, emit, 120)
        self.assertTrue(work["awaiting_thread"])
