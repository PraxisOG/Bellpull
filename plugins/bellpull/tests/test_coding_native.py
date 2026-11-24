"""Real stock run admission/plugin hooks/process registry; deterministic CLI fixture.

No inference, OpenCode provider, phone, Docker or personal account is involved.
"""
import asyncio
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
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


NATIVE_TRACKING = (
    all(k in inspect.signature(getattr(model_tools,"_emit_post_tool_call_hook",lambda:None)).parameters
        for k in ("task_id","session_id","turn_id","tool_call_id"))
    and all(k in ProcessSession.__dataclass_fields__
            for k in ("owner_task_id","parent_session_id","started_at","total_output_chars"))
)


@unittest.skipUnless(NATIVE_TRACKING, "Host lacks native ownership/output tracking; unsupported-host rejection is tested separately")
class CodingNativeTest(unittest.IsolatedAsyncioTestCase):
    async def test_stock_admission_coding_and_planning_share_one_plugin_and_one_run(self):
        await self.observe("opencode")

    async def test_codex_events_stay_with_one_native_process_and_run(self):
        await self.observe("codex")

    async def test_claude_events_stay_with_one_native_process_and_run(self):
        await self.observe("claude")

    async def observe(self, executor):
        release=threading.Event()
        original_sink=process_registry.on_output
        core=Path(api_server.__file__).resolve().parents[2]
        paths=[core/"gateway/platforms/api_server.py",core/"gateway/platforms/api_server_runs.py"]
        hashes=[hashlib.sha256(p.read_bytes()).hexdigest() for p in paths]
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ,{"HERMES_HOME":temporary,"BELLPULL_NTFY_URL":""}):
            home=Path(temporary)
            (home/"config.yaml").write_text("plugins:\n  enabled:\n    - bellpull\n")
            workspace=home/"workspace"
            executable=home/executor
            sid = "6181c478-e0f6-4b19-acaa-986b2b6e06ce"
            if executor == "opencode":
                workspace.mkdir()
                sid = "ses_fixture"
                flags = " run --format json fixture"
                event = dict(type="text", timestamp=123, sessionID=sid, part=dict(id="part_fixture",
                             messageID="msg_fixture", sessionID=sid, type="text", text="Observed fixture output"))
                executable.write_text("#!/usr/bin/env python3\nimport time\nprint("+repr(json.dumps(event))+",flush=True)\ntime.sleep(1)\n")
            else:
                flags = " exec --json fixture" if executor == "codex" else " -p --verbose --output-format stream-json fixture"
                shutil.copyfile(Path(__file__).parent / "fixtures/coding_child.py", executable)
                source = home / "source"
                source.mkdir()
                def git(*args):
                    return subprocess.check_output(["git","-C",str(source),*args], text=True).strip()
                git("init","-q")
                git("config","user.name","Fixture")
                git("config","user.email","fixture@invalid")
                (source/"answer.py").write_text("value = 1\n")
                (source/"test_answer.py").write_text("import unittest\nfrom answer import value\nclass TestAnswer(unittest.TestCase):\n    def test_value(self): self.assertEqual(2,value)\n")
                (source/".gitignore").write_text("__pycache__/\n")
                git("add",".")
                git("commit","-qm","Fixture baseline")
                base = git("rev-parse","HEAD")
                git("worktree","add","--detach","-q",str(workspace),"HEAD")
                (source/"answer.py").write_text("owner edit\n")
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
                        command=shlex.quote(str(executable))+flags
                        proc=process_registry.spawn_local(command,cwd=str(workspace),task_id=values["task_id"],owner_task_id=values["task_id"])
                        processes.append(proc)
                        from hermes_cli.lifecycle import invoke_hook
                        invoke_hook("post_tool_call",tool_name="terminal",args={"command":command,"background":True},
                                    result=json.dumps({"session_id":proc.id,"output":"","exit_code":None}),task_id=values["task_id"],
                                    session_id=self.session_id,turn_id=self._current_turn_id,tool_call_id="call_fixture")
                        if not release.wait(30): raise RuntimeError("Fixture was not released")
                        return {"final_response":"Fixture finished","completed":True}
                return Agent()
            adapter._create_agent=create_agent
            try:
                self.assertTrue(await adapter.connect())
                async with ClientSession(headers={"Authorization":"Bearer "+key}) as client:
                    origin="http://127.0.0.1:18648"
                    response=await client.get(origin+"/api/bellpull/capabilities")
                    capabilities=await response.json()
                    self.assertTrue(capabilities["features"]["bellpull_coding_activity"])
                    self.assertTrue(capabilities["features"]["bellpull_planning"])
                    reminder=dict(operation_id="e"*32,action="create",title="Review coding output",due_ms=2000000000000)
                    response=await client.post(origin+"/api/bellpull/planning/reminders",json=reminder)
                    self.assertEqual(200,response.status,await response.text())
                    response=await client.post(origin+"/api/sessions",json={})
                    session=(await response.json())["session"]["id"]
                    response=await client.post(origin+"/v1/runs",headers={"Idempotency-Key":"coding-once"},json={"input":"Fixture","session_id":session})
                    self.assertEqual(202,response.status,await response.text())
                    run=(await response.json())["run_id"]
                    response=await client.post(origin+"/v1/runs",headers={"Idempotency-Key":"coding-once"},json={"input":"Fixture","session_id":session})
                    self.assertEqual(run,(await response.json())["run_id"])
                    coding=origin+f"/api/bellpull/sessions/{session}/coding"
                    params = {} if executor == "opencode" else {"executors":"all"}
                    if executor != "opencode":
                        for _ in range(100):
                            opening=await (await client.get(coding,params=params)).json()
                            if opening["works"] and opening["works"][0].get("executor_session_id"): break
                            await asyncio.sleep(.05)
                        running, = opening["works"]
                        self.assertEqual("running", running["state"])
                        async with ClientSession(headers={"Authorization":"Bearer "+key}) as reopened:
                            resumed=await (await reopened.get(coding,params={**params,"after":str(opening["cursor"])})).json()
                            self.assertEqual(running["id"],resumed["works"][0]["id"])
                            inspection=origin+f"/api/bellpull/coding/{running['id']}/workspace"
                            clean=await (await reopened.get(inspection)).json()
                            self.assertEqual(base,clean["base_commit"])
                            self.assertEqual([],clean["files"])
                        (home/"release-edit").touch()
                    async def observed():
                        for _ in range(60):
                            response=await client.get(coding, params=params)
                            self.assertEqual(200,response.status,await response.text())
                            value=await response.json()
                            if any(r["detail"]=="Observed fixture output" for r in value["rows"]): return value
                            await asyncio.sleep(.1)
                        self.fail("No observed output")
                    snapshot=await observed()
                    self.assertEqual(run,snapshot["works"][0]["run_id"])
                    response=await client.get(origin+"/api/bellpull/planning")
                    planning=await response.json()
                    self.assertEqual(["Review coding output"],[r["title"] for r in planning["reminders"]])
                    current=planning["reminders"][0]
                    response=await client.post(origin+"/api/bellpull/planning/reminders",json=dict(
                        operation_id="f"*32,action="complete",id=current["id"],revision=current["revision"]))
                    self.assertEqual(200,response.status,await response.text())
                    work, = snapshot["works"]
                    self.assertEqual(executor, work.get("executor", "opencode"))
                    self.assertEqual(sid, work["open_code_session_id"] if executor == "opencode" else work["executor_session_id"])
                    if executor != "opencode":
                        old_view = await (await client.get(coding)).json()
                        self.assertEqual([], old_view["works"])
                        self.assertEqual([], old_view["rows"])
                    self.assertEqual(1,len(processes))
                    response=await client.get(coding, params={**params, "after":str(snapshot["cursor"])})
                    delta=await response.json(); self.assertFalse(delta["reset"]); self.assertEqual([],delta["rows"])
                    wid=snapshot["works"][0]["id"]
                    response=await client.get(origin+f"/api/bellpull/coding/{wid}/workspace")
                    self.assertEqual(200,response.status)
                    listing=await response.json()
                    if executor == "opencode":
                        self.assertIn("Git repository root", listing["note"])
                    else:
                        self.assertEqual(base, listing["base_commit"])
                        entry, = listing["files"]
                        self.assertEqual("answer.py", entry["path"])
                        preview=await (await client.get(inspection,params={"file":entry["id"]})).json()
                        self.assertIn("-value = 1", preview["diff"])
                        self.assertIn("+value = 2", preview["diff"])
                        self.assertEqual("owner edit\n",(source/"answer.py").read_text())
                        self.assertEqual(base,git("rev-parse","HEAD"))
                        outcomes=[r["outcome"] for r in snapshot["rows"] if r["label"] in ("Command execution","Bash")]
                        self.assertEqual(["exit 1","exit 0"] if executor == "codex" else ["failed","returned"], outcomes)
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


@unittest.skipIf(NATIVE_TRACKING, "Supported host runs the full admission/observation test")
class UnsupportedCodingNativeTest(unittest.IsolatedAsyncioTestCase):
    async def test_older_stock_declines_activity_without_admitting_inference(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ,{
            "HERMES_HOME":temporary,"BELLPULL_NTFY_URL":""
        }):
            Path(temporary,"config.yaml").write_text("plugins:\n  enabled:\n    - bellpull\n")
            manager=get_plugin_manager(); manager.discover_and_load()
            key="coding-unsupported-disposable-key"
            adapter=api_server.APIServerAdapter(PlatformConfig(enabled=True,
                extra={"key":key,"host":"127.0.0.1","port":18648}))
            admissions=[]
            def reject_inference(**kwargs):
                admissions.append(kwargs)
                raise AssertionError("Unsupported activity must not submit inference")
            adapter._create_agent=reject_inference
            try:
                self.assertTrue(await adapter.connect())
                async with ClientSession(headers={"Authorization":"Bearer "+key}) as client:
                    origin="http://127.0.0.1:18648"
                    response=await client.get(origin+"/api/bellpull/capabilities")
                    self.assertEqual(200,response.status)
                    self.assertFalse((await response.json())["features"]["bellpull_coding_activity"])
                    response=await client.post(origin+"/api/sessions",json={})
                    self.assertEqual(201,response.status)
                    session=(await response.json())["session"]["id"]
                    response=await client.get(origin+f"/api/bellpull/sessions/{session}/coding", params={"executors":"all"})
                    self.assertEqual(200,response.status)
                    snapshot=await response.json()
                    self.assertFalse(snapshot["available"])
                    self.assertEqual([],snapshot["executors"])
                    self.assertTrue(snapshot["note"])
                    self.assertEqual([],snapshot["works"])
                    self.assertEqual([],snapshot["rows"])
                    self.assertEqual([],admissions)
            finally:
                await adapter.disconnect(); manager.unload()
                from gateway import status
                if callable(getattr(status,"flush_runtime_status",None)):
                    await asyncio.to_thread(status.flush_runtime_status)
