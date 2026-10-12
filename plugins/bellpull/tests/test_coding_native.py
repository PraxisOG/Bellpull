"""Real stock run admission/plugin hooks/process registry; deterministic CLI fixture.

No inference, OpenCode provider, phone, Docker or personal account is involved.
"""
import asyncio
import hashlib
import json
import os
from pathlib import Path
import shlex
import tempfile
import threading
import unittest
from unittest.mock import patch
from aiohttp import ClientSession
from gateway.config import PlatformConfig
from gateway.platforms import api_server
from hermes_cli.plugins import get_plugin_manager
from tools.process_registry import process_registry
from tools.process_registry import ProcessSession
import inspect
import model_tools


@unittest.skipUnless(all(k in inspect.signature(getattr(model_tools,"_emit_post_tool_call_hook",lambda:None)).parameters for k in ("turn_id","tool_call_id")) and "owner_task_id" in ProcessSession.__dataclass_fields__, "Host lacks verified native ownership hooks; pure observer tests still run")
class CodingNativeTest(unittest.IsolatedAsyncioTestCase):
    async def test_stock_admission_observation_reconnect_workspace_and_one_run(self):
        release=threading.Event()
        original_sink=process_registry.on_output
        core=Path(api_server.__file__).resolve().parents[2]
        paths=[core/"gateway/platforms/api_server.py",core/"gateway/platforms/api_server_runs.py"]
        hashes=[hashlib.sha256(p.read_bytes()).hexdigest() for p in paths]
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ,{"HERMES_HOME":temporary,"BELLPULL_NTFY_URL":""}):
            home=Path(temporary)
            (home/"config.yaml").write_text("plugins:\n  enabled:\n    - bellpull\n")
            workspace=home/"workspace"; workspace.mkdir()
            executable=workspace/"opencode"
            envelope=dict(type="text",timestamp=123,sessionID="ses_fixture",part=dict(id="part_fixture",messageID="msg_fixture",sessionID="ses_fixture",type="text",text="Observed fixture output"))
            executable.write_text("#!/usr/bin/env python3\nimport time\nprint("+repr(json.dumps(envelope))+",flush=True)\ntime.sleep(1)\n")
            executable.chmod(0o700)
            manager=get_plugin_manager(); manager.discover_and_load()
            key="coding-disposable-key"
            adapter=api_server.APIServerAdapter(PlatformConfig(enabled=True,extra={"key":key,"host":"127.0.0.1","port":18648}))
            admissions=[]; processes=[]
            def create_agent(**kwargs):
                class Agent:
                    session_id=kwargs.get("session_id")
                    _current_turn_id="coding-turn-fixture"
                    def run_conversation(self,**values):
                        admissions.append(values)
                        command=shlex.quote(str(executable))+" run --format json fixture"
                        proc=process_registry.spawn_local(command,cwd=str(workspace),task_id=values["task_id"],owner_task_id=values["task_id"])
                        processes.append(proc)
                        from hermes_cli.lifecycle import invoke_hook
                        invoke_hook("post_tool_call",tool_name="terminal",args={"command":command,"background":True},
                                    result=json.dumps({"session_id":proc.id,"output":"","exit_code":None}),task_id=values["task_id"],
                                    session_id=self.session_id,turn_id=self._current_turn_id,tool_call_id="call_fixture")
                        if not release.wait(15): raise RuntimeError("Fixture was not released")
                        return {"final_response":"Fixture finished","completed":True}
                return Agent()
            adapter._create_agent=create_agent
            try:
                self.assertTrue(await adapter.connect())
                async with ClientSession(headers={"Authorization":"Bearer "+key}) as client:
                    origin="http://127.0.0.1:18648"
                    response=await client.post(origin+"/api/sessions",json={})
                    session=(await response.json())["session"]["id"]
                    response=await client.post(origin+"/v1/runs",headers={"Idempotency-Key":"coding-once"},json={"input":"Fixture","session_id":session})
                    self.assertEqual(202,response.status,await response.text())
                    run=(await response.json())["run_id"]
                    async def observed():
                        for _ in range(60):
                            response=await client.get(origin+f"/api/bellpull/sessions/{session}/coding")
                            self.assertEqual(200,response.status,await response.text())
                            value=await response.json()
                            if any(r["detail"]=="Observed fixture output" for r in value["rows"]): return value
                            await asyncio.sleep(.1)
                        self.fail("No observed output")
                    snapshot=await observed()
                    self.assertEqual(run,snapshot["works"][0]["run_id"])
                    self.assertEqual("ses_fixture",snapshot["works"][0]["open_code_session_id"])
                    response=await client.get(origin+f"/api/bellpull/sessions/{session}/coding?after={snapshot['cursor']}")
                    delta=await response.json(); self.assertFalse(delta["reset"]); self.assertEqual([],delta["rows"])
                    wid=snapshot["works"][0]["id"]
                    response=await client.get(origin+f"/api/bellpull/coding/{wid}/workspace")
                    self.assertEqual(200,response.status); self.assertIn("Git repository root",(await response.json())["note"])
                    response=await client.get(origin+f"/api/bellpull/sessions/{session}/coding",headers={"Authorization":"Bearer wrong"})
                    self.assertEqual(401,response.status)
                    self.assertEqual(1,len(admissions)); self.assertIs(original_sink,process_registry.on_output)
                    release.set()
                    await asyncio.gather(*adapter._active_run_tasks.values(),return_exceptions=True)
            finally:
                release.set()
                for proc in processes:
                    if not proc.exited: process_registry.kill_process(proc.id)
                await adapter.disconnect(); manager.unload()
                from gateway import status
                if callable(getattr(status,"flush_runtime_status",None)):
                    await asyncio.to_thread(status.flush_runtime_status)
        self.assertEqual(hashes,[hashlib.sha256(p.read_bytes()).hexdigest() for p in paths])
