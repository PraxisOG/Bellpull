import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from plugins.bellpull.coding import CodingStore, Observer, cli_invocation, decode_line, launch_transport
from plugins.bellpull.coding_workspace import git as workspace_git, inspect_workspace, owned_bytes
from plugins.bellpull.artifacts import ArtifactError
from plugins.bellpull.lineage import compression_ids
from hermes_state import SessionDB

class ProcessFixture:
    """Pure observer tests also run on older hosts without modern ownership fields."""
    def __init__(self,*args,**kwargs):
        self._lock=threading.RLock()
        self.output_buffer=""; self.total_output_chars=0; self.max_output_chars=100000
        self.exited=False; self.exit_code=None; self.completion_reason=None
    def append_output(self,text):
        self.total_output_chars+=len(text)
        self.output_buffer=(self.output_buffer+text)[-self.max_output_chars:]


def line(text="Hello", session="ses_one", part="part_one", kind="text"):
    return json.dumps(dict(type=kind,timestamp=123,sessionID=session,part=dict(id=part,messageID="msg_one",sessionID=session,type=kind,text=text)))+"\n"


class CodingTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.home=Path(self.temp.name)
        self.store=CodingStore(self.home)
        self.work=dict(id="a"*32,session_id="root",run_id="run_once",process_id="proc_one",open_code_session_id=None,
                       state="running",owner_task_id="root",incarnation="123.0",observed_total=0,workspace=None,gap=False)
        self.store.save(self.work)
        self.observer=Observer(SimpleNamespace(_active_run_agents={}))
        self.process=ProcessFixture()

    def tearDown(self):
        self.observer.stop(); self.temp.cleanup()

    def sample(self, text):
        self.process.append_output(text)
        self.observer.sample(self.store,self.work,self.process)

    def test_split_lines_unicode_crlf_updates_and_foreign_sessions(self):
        value=line("héllo 🌙").replace("\n","\r\n")
        self.sample(value[:25]); self.assertEqual([],self.store.snapshot("root",["root"])["rows"])
        self.sample(value[25:]); self.sample(line("Updated")); self.sample(line("Foreign",session="ses_other"))
        snapshot=self.store.snapshot("root",["root"])
        self.assertEqual(["Updated"],[r["detail"] for r in snapshot["rows"]])
        self.assertEqual("ses_one",self.work["open_code_session_id"])

    def test_replay_is_idempotent_and_cursors_survive_reopening(self):
        self.sample(line()); old=self.store.snapshot("root",["root"])
        self.sample(line()); after=self.store.snapshot("root",["root"],old["cursor"])
        self.assertEqual([],after["rows"])
        self.assertEqual(old["cursor"],after["cursor"])
        reopened=CodingStore(self.home)
        self.assertEqual(old["rows"],reopened.snapshot("root",["root"])["rows"])

    def test_evicted_journal_forces_snapshot_and_explicit_gap(self):
        with patch("plugins.bellpull.coding.MAX_EVENTS",3):
            for i in range(7): self.sample(line(str(i),part=str(i)))
        snapshot=self.store.snapshot("root",["root"],1)
        self.assertTrue(snapshot["reset"]); self.assertTrue(snapshot["gap"])
        self.assertEqual(7,len(snapshot["rows"]))

    def test_other_busy_conversations_cannot_starve_a_snapshot(self):
        self.sample(line("Keep me"))
        other=dict(self.work,id="b"*32,session_id="other")
        self.store.save(other)
        for i in range(305): self.store.emit(other,dict(key=str(i),detail="other"))
        self.assertEqual(["Keep me"],[r["detail"] for r in self.store.snapshot("root",["root"])["rows"]])

    def test_tail_loss_marks_gap_and_step_finish_does_not_complete_process(self):
        self.process.max_output_chars=200
        self.sample("lost"*100+"\n"+line())
        self.assertTrue(self.work["gap"])
        self.assertEqual("running",self.work["state"])
        step=json.loads(line(kind="step_finish")); step["part"]["type"]="step-finish"
        self.sample(json.dumps(step)+"\n")
        self.assertEqual("running",self.work["state"])
        self.process.exited=True; self.process.exit_code=0
        self.observer.sample(self.store,self.work,self.process)
        self.assertEqual("completed",self.work["state"])

    def test_plain_permission_warning_never_becomes_a_request(self):
        self.sample("Permission rejected: bash\n")
        row=self.store.snapshot("root",["root"])["rows"][0]
        self.assertEqual("output",row["kind"]); self.assertNotIn("request_id",row)

    def test_compression_generations_share_work_but_forks_and_profiles_do_not(self):
        database=SessionDB(self.home/"state.db")
        try:
            database.create_session("root","cli")
            for parent,child in (("root","middle"),("middle","tip")):
                database.end_session(parent,"compression")
                database.create_session(child,"cli",parent_session_id=parent)
            database.create_session("branch","cli",parent_session_id="root",model_config={"_branched_from":"root"})
            self.sample(line())
            self.assertEqual(1,len(self.store.snapshot("tip",compression_ids(database,"tip"))["works"]))
            self.assertEqual([],self.store.snapshot("branch",compression_ids(database,"branch"))["works"])
            other=CodingStore(self.home/"profile-two")
            self.assertEqual([],other.snapshot("root",["root"])["works"])
        finally: database.close()

    def test_restart_unknown_and_process_incarnation_mismatch_never_becomes_success(self):
        restored=self.observer.store(self.home)
        self.assertEqual("unknown",restored.works()[0]["state"])
        fake=SimpleNamespace(get=lambda _:SimpleNamespace(id="proc_one",started_at=999,owner_task_id="root"))
        self.observer.registry=fake
        self.observer.tick()
        self.assertEqual("lost",restored.works()[0]["state"])
        self.observer.registry=None

    def test_only_exact_simple_json_launches_are_candidates(self):
        self.assertTrue(cli_invocation('opencode run --format json "Update app"'))
        self.assertTrue(cli_invocation('/opt/bin/opencode run --format=json "Fix it"'))
        for command in ('echo opencode run --format json','opencode','opencode run hi','cd /tmp && opencode run --format json hi','opencode run --format json hi | cat'):
            self.assertFalse(cli_invocation(command),command)

    def test_schema_mismatch_and_nested_foreign_session_are_rejected(self):
        value=json.loads(line()); value["part"]["sessionID"]="ses_other"
        self.assertIsNone(decode_line(json.dumps(value)))
        for value in ("[]","not json",'{"sessionID":"ses_one"}',"["*1500+"0"+"]"*1500):
            self.assertIsNone(decode_line(value))

    def test_retention_preserves_live_work_and_reports_expired_activity(self):
        with patch("plugins.bellpull.coding.MAX_WORKS",2):
            done=dict(self.work,id="b"*32,state="completed")
            self.assertTrue(self.store.save(done))
            newer=dict(self.work,id="c"*32)
            self.assertTrue(self.store.save(newer))
            self.assertEqual({self.work["id"],newer["id"]},{w["id"] for w in self.store.works()})
            self.assertTrue(self.store.snapshot("root",["root"])["gap"])
            self.assertFalse(self.store.save(dict(self.work,id="d"*32)))
            self.assertEqual(2,len(self.store.works()))

    def test_unterminated_foreign_frame_is_not_rendered_on_exit(self):
        self.sample(line()); self.sample(line("Foreign",session="ses_other").rstrip())
        self.process.exited=True; self.process.exit_code=0
        self.observer.sample(self.store,self.work,self.process)
        self.assertEqual(["Hello"],[r["detail"] for r in self.store.snapshot("root",["root"])["rows"]])

    def test_terminal_fallback_is_inert_and_removes_escape_sequences(self):
        self.assertEqual("terminal_text",launch_transport("opencode"))
        self.assertEqual("terminal_text",launch_transport("opencode run 'Fix app'"))
        for command in ("opencode serve","opencode attach http://host","echo opencode","opencode | cat"):
            self.assertIsNone(launch_transport(command))
        self.work["transport"]="terminal_text"
        self.sample("\x1b[31mPermission: bash\x1b[0m\n")
        row=self.store.snapshot("root",["root"])["rows"][0]
        self.assertEqual("Permission: bash",row["detail"])
        self.assertEqual("output",row["kind"])

    def test_tool_receipt_requires_exact_live_turn_process_and_owner(self):
        process=self.process
        for key,value in dict(id="proc_owned",owner_task_id="owner",parent_session_id="root",command="opencode run --format json hi",started_at=1.0,pid_scope="host",cwd=str(self.home)).items(): setattr(process,key,value)
        self.observer.adapter=SimpleNamespace(_active_run_agents={"run_exact":SimpleNamespace(_current_turn_id="turn",session_id="root")})
        self.observer.supported=True
        self.observer.registry=SimpleNamespace(get=lambda pid:process)
        receipt=dict(tool_name="terminal",args=dict(command=process.command),result=dict(session_id="proc_owned"),task_id="owner",session_id="root",turn_id="turn",tool_call_id="call")
        try:
            with patch.dict(os.environ,{"HERMES_HOME":str(self.home)}):
                for changes in (dict(turn_id="other"),dict(task_id="other"),dict(session_id="other"),dict(result=dict(session_id="proc_wrong")),dict(tool_call_id="")):
                    self.observer.observe_tool(**dict(receipt,**changes))
                self.assertEqual(1,len(self.store.works()))
                self.observer.observe_tool(**receipt)
                works=self.store.works(); self.assertEqual(2,len(works))
                self.assertEqual("run_exact",works[0]["run_id"])
        finally: self.observer.registry=None


class WorkspaceTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.root=Path(self.temp.name)/"repo"; self.root.mkdir()
        subprocess.run(["git","init","-q",str(self.root)],check=True)
        self.git("config","user.name","Fixture"); self.git("config","user.email","fixture@example.invalid")
        (self.root/"hello.txt").write_text("Before\n")
        self.git("add","hello.txt"); self.git("commit","-qm","Initial")

    def tearDown(self): self.temp.cleanup()
    def git(self,*args): subprocess.run(["git","-C",str(self.root),*args],check=True)

    def test_changed_and_untracked_files_have_opaque_readonly_diff_ids(self):
        (self.root/"hello.txt").write_text("After\n"); (self.root/"new.txt").write_text("New\n")
        snapshot=inspect_workspace(self.root)
        self.assertEqual({"hello.txt","new.txt"},{f["path"] for f in snapshot["files"]})
        entry=next(f for f in snapshot["files"] if f["path"]=="hello.txt")
        preview=inspect_workspace(self.root,entry["id"])
        self.assertIn("-Before",preview["diff"]); self.assertIn("+After",preview["diff"])
        self.assertEqual("After\n",(self.root/"hello.txt").read_text())

    def test_symlinks_binary_large_files_and_unknown_ids_are_rejected(self):
        outside=Path(self.temp.name)/"outside"; outside.write_text("Private")
        (self.root/"link").symlink_to(outside)
        with self.assertRaises(ArtifactError): owned_bytes(self.root,"link")
        with self.assertRaises(ArtifactError): owned_bytes(self.root,"../outside")
        for name,data in (("binary",b"\0"),("large",b"x"*262145)):
            (self.root/name).write_bytes(data)
            entry=next(f for f in inspect_workspace(self.root)["files"] if f["path"]==name)
            with self.assertRaises(ArtifactError): inspect_workspace(self.root,entry["id"])
        with self.assertRaises(ArtifactError): inspect_workspace(self.root,"invented")

    def test_parent_repository_is_not_silently_granted_and_fsmonitor_does_not_run(self):
        sub=self.root/"sub"; sub.mkdir()
        self.assertIn("repository root",inspect_workspace(sub)["note"])
        marker=self.root/"unexpected"
        self.git("config","core.fsmonitor",f"touch {marker}")
        inspect_workspace(self.root)
        self.assertFalse(marker.exists())

    def check_filter_is_inert(self, kind):
        # Install filter config only after fixture writes/commits: any marker
        # must come from listing/preview, never from test setup's git add.
        (self.root/".gitattributes").write_text("hello.txt filter=fixture\n")
        self.git("add",".gitattributes"); self.git("commit","-qm","Attributes")
        clean=Path(self.temp.name)/"clean-executed"
        process=Path(self.temp.name)/"process-executed"
        smudge=Path(self.temp.name)/"smudge-executed"
        self.git("config","filter.fixture.clean",f"touch {shlex.quote(str(clean))}; cat")
        self.git("config","filter.fixture.smudge",f"touch {shlex.quote(str(smudge))}; cat")
        if kind=="process":
            # Exit immediately if launched; even a failed protocol handshake
            # creates the marker and must fail this regression.
            self.git("config","filter.fixture.process",f"touch {shlex.quote(str(process))}; exit 1")
        self.git("config","filter.fixture.required","true")
        (self.root/"hello.txt").write_bytes(b"After without conversion\r\n")
        listing=inspect_workspace(self.root)
        for marker in (clean,process,smudge): self.assertFalse(marker.exists(),str(marker))
        entry=next(f for f in listing["files"] if f["path"]=="hello.txt")
        preview=inspect_workspace(self.root,entry["id"])
        self.assertIn("-Before\n",preview["diff"])
        self.assertIn("+After without conversion\r\n",preview["diff"])
        for marker in (clean,process,smudge): self.assertFalse(marker.exists(),str(marker))

    def test_clean_and_smudge_filters_never_run_during_listing_or_preview(self):
        self.check_filter_is_inert("clean")

    def test_process_filter_never_runs_during_listing_or_preview(self):
        self.check_filter_is_inert("process")

    def test_hard_links_are_excluded_from_tracked_and_untracked_previews(self):
        outside=Path(self.temp.name)/"outside.txt"
        outside.write_text("PRIVATE_OUTSIDE_WORKSPACE\n")
        (self.root/"hello.txt").unlink()
        for name in ("hello.txt","linked.txt"): os.link(outside,self.root/name)
        listing=inspect_workspace(self.root)
        self.assertIn("containment cannot be proven",listing["note"])
        for name in ("hello.txt","linked.txt"):
            entry=next(f for f in listing["files"] if f["path"]==name)
            with self.assertRaisesRegex(ArtifactError,"Hard-linked files"):
                inspect_workspace(self.root,entry["id"])
            with self.assertRaisesRegex(ArtifactError,"Hard-linked files"):
                owned_bytes(self.root,name)

    def test_staged_changes_and_deletions_compare_raw_head_to_worktree(self):
        (self.root/"hello.txt").write_text("Staged version\n")
        self.git("add","hello.txt")
        entry=next(f for f in inspect_workspace(self.root)["files"] if f["path"]=="hello.txt")
        self.assertIn("+Staged version",inspect_workspace(self.root,entry["id"])["diff"])
        (self.root/"hello.txt").write_text("Worktree version\n")
        preview=inspect_workspace(self.root,entry["id"])
        self.assertIn("-Before",preview["diff"]); self.assertIn("+Worktree version",preview["diff"])
        self.assertNotIn("Staged version",preview["diff"])
        (self.root/"hello.txt").unlink()
        preview=inspect_workspace(self.root,entry["id"])
        self.assertIn("-Before",preview["diff"])

    def test_unborn_head_keeps_tracked_file_preview(self):
        root=Path(self.temp.name)/"unborn"; root.mkdir()
        subprocess.run(["git","init","-q",str(root)],check=True)
        (root/"new.txt").write_text("Unborn fixture\n")
        subprocess.run(["git","-C",str(root),"add","new.txt"],check=True)
        entry=inspect_workspace(root)["files"][0]
        self.assertEqual("new.txt",entry["path"])
        self.assertIn("+Unborn fixture",inspect_workspace(root,entry["id"])["diff"])

    def test_git_timeout_kills_the_process_group(self):
        proc=Mock(pid=12345)
        proc.wait.side_effect=[subprocess.TimeoutExpired("git",5),0]
        selector=Mock()
        selector.select.return_value=[(None,None)]
        with patch("plugins.bellpull.coding_workspace.subprocess.Popen",return_value=proc) as popen, \
             patch("plugins.bellpull.coding_workspace.selectors.DefaultSelector",return_value=selector), \
             patch("plugins.bellpull.coding_workspace.os.read",return_value=b""), \
             patch("plugins.bellpull.coding_workspace.os.killpg") as killpg:
            with self.assertRaisesRegex(ArtifactError,"timed out"):
                workspace_git(self.root,"rev-parse","HEAD")
        self.assertTrue(popen.call_args.kwargs["start_new_session"])
        killpg.assert_called_once_with(proc.pid,signal.SIGKILL)
        self.assertEqual(2,proc.wait.call_count)
