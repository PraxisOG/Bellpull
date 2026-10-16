import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch
from plugins.bellpull.planning import PlanningStore, routine_action, routines, digest, cron_ready
from plugins.bellpull.artifacts import ArtifactError
from aiohttp import web
from aiohttp.test_utils import TestClient,TestServer
from gateway.config import PlatformConfig
from gateway.platforms.api_server import APIServerAdapter
from plugins.bellpull.http import wire


class PlanningTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.home=Path(self.temp.name)
        self.env=patch.dict(os.environ,{"HERMES_HOME":str(self.home)}); self.env.start()
        self.store=PlanningStore(self.home); self.now=int(time.time()*1000)
    def tearDown(self): self.env.stop(); self.temp.cleanup()
    def test_reminder_retry_conflict_snooze_and_completion_survive_restart(self):
        body=dict(operation_id="a"*32,action="create",title="Measurements 12.5 mm",due_ms=self.now+60000)
        first=self.store.reminder(body)
        self.assertEqual(first,self.store.reminder(body))
        self.assertEqual(1,len(PlanningStore(self.home).snapshot()["reminders"]))
        with self.assertRaises(ArtifactError): self.store.reminder(dict(body,title="Different"))
        changed=self.store.reminder(dict(operation_id="b"*32,id=first["id"],revision=1,action="snooze",due_ms=self.now+3600000))
        self.assertEqual("pending",changed["state"])
        with self.assertRaises(ArtifactError): self.store.reminder(dict(operation_id="c"*32,id=first["id"],revision=1,action="complete"))
        done=self.store.reminder(dict(operation_id="c"*32,id=first["id"],revision=2,action="complete"))
        self.assertEqual("completed",done["state"])
        self.assertEqual([],PlanningStore(self.home/"other").snapshot()["reminders"])
    def test_delete_and_retry_do_not_create_another_reminder(self):
        body=dict(operation_id="a"*32,action="create",title="Once",due_ms=self.now)
        first=self.store.reminder(body)
        delete=dict(operation_id="b"*32,action="delete",id=first["id"],revision=1)
        self.store.reminder(delete); self.store.reminder(delete); self.store.reminder(body)
        self.assertEqual([],self.store.snapshot()["reminders"])
    def test_busy_only_cache_strips_details_and_revoke_clears_proposals(self):
        device="d"*32
        snapshot=dict(read_at_ms=self.now,busy_only=True,events=[dict(id="12",title="Private title",fingerprint="f"*64,start_ms=self.now,end_ms=self.now+60000)])
        saved=self.store.calendar(device,snapshot)
        self.assertNotIn("title",saved["events"][0]); self.assertNotIn("fingerprint",saved["events"][0])
        proposal=self.store.propose(dict(operation_id="a"*32,device=device,action="create_event",title="New event",start_ms=self.now,end_ms=self.now+60000))
        self.assertEqual("pending",proposal["state"])
        self.store.calendar(device,dict(revoke=True))
        self.assertIsNone(self.store.calendar(device)); self.assertEqual([],self.store.snapshot()["proposals"])
    def test_stale_and_foreign_calendar_references_are_rejected(self):
        device="d"*32
        event=dict(id="12",title="Existing",fingerprint="f"*64,start_ms=self.now,end_ms=self.now+60000)
        self.store.calendar(device,dict(read_at_ms=self.now,busy_only=False,events=[event]))
        args=dict(operation_id="a"*32,device=device,action="delete_event",title="Existing",event_id="12",fingerprint="wrong")
        with self.assertRaises(ArtifactError): self.store.propose(args)
        args["fingerprint"]=event["fingerprint"]
        first=self.store.propose(args); self.assertEqual(first,self.store.propose(args))
        with self.assertRaises(ArtifactError): self.store.resolve(dict(id=first["id"],device="e"*32,state="applied",result="Wrong phone"))
        result=dict(id=first["id"],device=device,state="applied",result="Verified phone deletion")
        self.assertEqual(self.store.resolve(result),self.store.resolve(result))
        with patch("plugins.bellpull.planning.time.time",return_value=time.time()+90000):
            with self.assertRaises(ArtifactError): self.store.propose(dict(args,operation_id="b"*32))
    def test_revoke_scrubs_retained_proposals_but_blocks_replay_after_resharing(self):
        device="d"*32; other="e"*32
        for identity in (device,other):
            self.store.calendar(identity,dict(read_at_ms=self.now,busy_only=True,events=[]))
        args=self.proposal_args(0)
        proposal=self.store.propose(args)
        result=self.store.resolve(dict(id=proposal["id"],device=device,state="applied",result="Private event ID"))
        # This is the same receipt shape produced by terminal-history pruning.
        with self.store.db() as c:
            c.execute("INSERT INTO proposal_receipts VALUES(?,?)",(proposal["id"],json.dumps(result)))
            c.execute("DELETE FROM proposals WHERE id=?",(proposal["id"],))
            peer=dict(result,id="b"*32,device=other)
            c.execute("INSERT INTO proposal_receipts VALUES(?,?)",(peer["id"],json.dumps(peer)))
        revoked=self.store.calendar(device,dict(revoke=True))
        restarted=PlanningStore(self.home)
        with restarted.db() as c:
            stored=json.loads(c.execute("SELECT data FROM proposal_receipts WHERE id=?",(proposal["id"],)).fetchone()[0])
            self.assertEqual({"id":proposal["id"],"device":device,"revoked":True},stored)
            self.assertEqual(peer,json.loads(c.execute("SELECT data FROM proposal_receipts WHERE id=?",(peer["id"],)).fetchone()[0]))
        restarted.calendar(device,dict(read_at_ms=self.now,busy_only=True,events=[],generation=revoked["generation"]))
        with self.assertRaisesRegex(ArtifactError,"revoked"):
            restarted.propose(args)
        self.assertEqual([],restarted.snapshot()["proposals"])
    def proposal_args(self,number,device="d"*32):
        return dict(operation_id=f"{number:032x}",device=device,action="create_event",title="Calendar fixture",start_ms=self.now,end_ms=self.now+60000)
    def test_upgrade_scrubs_old_receipts_for_an_already_revoked_device(self):
        device="d"*32
        self.store.calendar(device,dict(revoke=True))
        old=dict(id="a"*32,device=device,state="applied",title="Private appointment",result="Private result",start_ms=self.now)
        with self.store.db() as c:
            c.execute("INSERT INTO proposal_receipts VALUES(?,?)",(old["id"],json.dumps(old)))
        restarted=PlanningStore(self.home)
        with restarted.db() as c:
            actual=json.loads(c.execute("SELECT data FROM proposal_receipts WHERE id=?",(old["id"],)).fetchone()[0])
        self.assertEqual(dict(id=old["id"],device=device,revoked=True),actual)
    def test_revoked_pending_proposal_cannot_be_recreated_by_an_old_retry(self):
        device="d"*32
        self.store.calendar(device,dict(read_at_ms=self.now,busy_only=True,events=[]))
        args=self.proposal_args(0)
        self.store.propose(args)
        revoked=self.store.calendar(device,dict(revoke=True))
        self.store.calendar(device,dict(read_at_ms=self.now,busy_only=True,events=[],generation=revoked["generation"]))
        with self.assertRaisesRegex(ArtifactError,"revoked"):
            self.store.propose(args)
        self.assertEqual("pending",self.store.propose(self.proposal_args(1))["state"])
    def test_revoke_between_calendar_read_and_proposal_commit_rejects_stale_details(self):
        device="d"*32
        self.store.calendar(device,dict(read_at_ms=self.now,busy_only=True,events=[]))
        read=self.store.calendar
        def read_then_revoke(identity):
            snapshot=read(identity)
            read(identity,dict(revoke=True))
            return snapshot
        with patch.object(self.store,"calendar",side_effect=read_then_revoke):
            with self.assertRaisesRegex(ArtifactError,"sharing changed"):
                self.store.propose(self.proposal_args(0))
        self.assertEqual([],self.store.snapshot()["proposals"])
    def test_over_100_create_resolve_cycles_and_evicted_retries_survive_restart(self):
        for device in ("d"*32,"e"*32):
            self.store.calendar(device,dict(read_at_ms=self.now,busy_only=True,events=[]))
        for i in range(1002):
            args=self.proposal_args(i,device=("d" if i%2==0 else "e")*32)
            proposal=self.store.propose(args)
            resolved=self.store.resolve(dict(id=proposal["id"],device=args["device"],state="applied" if i%2==0 else "rejected",result="Phone result"))
            self.assertEqual(resolved,self.store.propose(args))
            if i==0: first=resolved
        restarted=PlanningStore(self.home)
        self.assertEqual(first,restarted.propose(self.proposal_args(0)))
        with self.assertRaisesRegex(ArtifactError,"Proposal identity reused"):
            restarted.propose(dict(self.proposal_args(0),title="Different content"))
        with restarted.db() as c:
            self.assertIsNone(c.execute("SELECT 1 FROM proposals WHERE id=?",(first["id"],)).fetchone())
            self.assertEqual(0,c.execute("SELECT COUNT(*) FROM proposals WHERE json_extract(data,'$.state')='pending'").fetchone()[0])
            self.assertEqual(1000,c.execute("SELECT COUNT(*) FROM proposals").fetchone()[0])
    def test_pending_proposals_never_hide_behind_newer_resolved_history(self):
        # F04 follow-up: retention must not crowd admitted pending work out of the
        # visible snapshot page (API contract: every pending proposal is reachable).
        self.store.calendar("d"*32,dict(read_at_ms=self.now,busy_only=True,events=[]))
        pending=self.store.propose(self.proposal_args(0))
        for i in range(1,101):
            proposal=self.store.propose(self.proposal_args(i))
            self.store.resolve(dict(id=proposal["id"],device="d"*32,state="rejected",result="No thanks"))
        self.assertIn(pending["id"],[item["id"] for item in self.store.snapshot()["proposals"]])
        restarted=PlanningStore(self.home)
        self.assertIn(pending["id"],[item["id"] for item in restarted.snapshot()["proposals"]])
        # A retried rejected proposal still replays from receipts, unchanged.
        replayed=restarted.propose(self.proposal_args(100))
        self.assertEqual("rejected",replayed["state"])
        # Fill the pending cap; every one stays visible.
        for i in range(101,200): self.store.propose(self.proposal_args(i))
        visible={item["id"] for item in PlanningStore(self.home).snapshot()["proposals"]}
        with self.store.db() as c:
            pending_ids={pid for pid, in c.execute("SELECT id FROM proposals WHERE json_extract(data,'$.state')='pending'")}
        self.assertEqual(pending_ids,visible & pending_ids)
        self.assertEqual(100,len(pending_ids))
    def test_pending_limit_is_shared_across_devices_and_resolution_frees_capacity(self):
        for device in ("d"*32,"e"*32):
            self.store.calendar(device,dict(read_at_ms=self.now,busy_only=True,events=[]))
        for i in range(100): self.store.propose(self.proposal_args(i))
        self.assertEqual("pending",self.store.propose(self.proposal_args(0))["state"])
        with self.assertRaisesRegex(ArtifactError,"Pending proposal limit reached") as error:
            self.store.propose(self.proposal_args(100,device="e"*32))
        self.assertEqual(409,error.exception.status)
        self.store.resolve(dict(id=f"{0:032x}",device="d"*32,state="rejected",result="No thanks"))
        self.assertEqual("pending",self.store.propose(self.proposal_args(100,device="e"*32))["state"])
    def test_terminal_retention_evicts_oldest_creation_not_pending_rows(self):
        self.store.calendar("d"*32,dict(read_at_ms=self.now,busy_only=True,events=[]))
        pending=self.store.propose(self.proposal_args(0))
        oldest=self.store.propose(self.proposal_args(1))
        # Seed existing terminal history to exercise migration as well as pruning.
        with self.store.db() as c:
            for i in range(2,1002):
                value=dict(oldest,id=f"{i:032x}",state="rejected",result="No thanks")
                c.execute("INSERT INTO proposals VALUES(?,?)",(value["id"],json.dumps(value)))
        # The oldest-created row resolves last, but is still evicted first.
        result=self.store.resolve(dict(id=oldest["id"],device="d"*32,state="applied",result="Verified"))
        with self.store.db() as c:
            ids=[pid for pid, in c.execute("SELECT id FROM proposals ORDER BY rowid")]
            self.assertEqual([pending["id"]]+[f"{i:032x}" for i in range(2,1002)],ids)
            self.assertEqual(result,json.loads(c.execute("SELECT data FROM proposal_receipts WHERE id=?",(oldest["id"],)).fetchone()[0]))
    def test_existing_terminal_history_is_pruned_and_receipts_are_bounded(self):
        with self.store.db() as c:
            for i in range(5000):
                value=dict(id=f"{i:032x}",state="applied")
                c.execute("INSERT INTO proposal_receipts VALUES(?,?)",(value["id"],json.dumps(value)))
            for i in range(5000,6002):
                value=dict(id=f"{i:032x}",state="rejected")
                c.execute("INSERT INTO proposals VALUES(?,?)",(value["id"],json.dumps(value)))
        restarted=PlanningStore(self.home)
        with restarted.db() as c:
            self.assertEqual(1000,c.execute("SELECT COUNT(*) FROM proposals").fetchone()[0])
            receipts=[pid for pid, in c.execute("SELECT id FROM proposal_receipts ORDER BY rowid")]
            self.assertEqual([f"{i:032x}" for i in range(2,5002)],receipts)
    def test_upload_after_revoke_rejects_old_and_legacy_generations(self):
        device="d"*32
        body=dict(read_at_ms=self.now,busy_only=True,events=[])
        saved=self.store.calendar(device,body)
        revoked=self.store.calendar(device,dict(revoke=True))
        self.assertGreater(revoked["generation"],saved["generation"])
        for upload in (body,dict(body,generation=saved["generation"])):
            with self.assertRaises(ArtifactError) as error: self.store.calendar(device,upload)
            self.assertEqual(409,error.exception.status)
            self.assertIsNone(self.store.calendar(device))
    def test_legacy_client_pairs_until_a_revoke_then_gets_actionable_error(self):
        device="d"*32
        body=dict(read_at_ms=self.now,busy_only=True,events=[])
        saved=self.store.calendar(device,body)  # the first-ever upload establishes generation 1
        self.assertEqual(1,saved["generation"])
        self.store.calendar(device,dict(revoke=True))
        with self.assertRaises(ArtifactError) as error: self.store.calendar(device,body)
        self.assertEqual(409,error.exception.status)
        self.assertIn("too old",str(error.exception))
        self.assertIsNone(self.store.calendar(device))
    def test_revoke_then_fresh_upload_with_acknowledged_generation(self):
        device="d"*32
        revoked=self.store.calendar(device,dict(revoke=True))
        body=dict(read_at_ms=self.now,busy_only=True,events=[],generation=revoked["generation"])
        saved=self.store.calendar(device,body)
        self.assertEqual(revoked["generation"],saved["generation"])
        self.assertEqual(saved,self.store.calendar(device,body))
    def test_generation_and_revoke_tombstone_survive_restart(self):
        device="d"*32
        body=dict(read_at_ms=self.now,busy_only=True,events=[])
        saved=self.store.calendar(device,body)
        self.assertEqual(saved,PlanningStore(self.home).calendar(device))
        revoked=PlanningStore(self.home).calendar(device,dict(revoke=True))
        restarted=PlanningStore(self.home)
        self.assertIsNone(restarted.calendar(device))
        with self.assertRaises(ArtifactError) as error:
            restarted.calendar(device,dict(body,generation=saved["generation"]))
        self.assertEqual(409,error.exception.status)
        self.assertEqual(revoked["generation"],restarted.calendar(device,dict(body,generation=revoked["generation"]))["generation"])
        self.assertEqual(revoked["generation"]+1,PlanningStore(self.home).calendar(device,dict(revoke=True))["generation"])
    def test_busy_only_policy_change_fences_detailed_upload(self):
        device="d"*32
        body=dict(read_at_ms=self.now,busy_only=False,events=[])
        saved=self.store.calendar(device,body)
        restricted=self.store.calendar(device,dict(body,busy_only=True,generation=saved["generation"]))
        self.assertEqual(saved["generation"]+1,restricted["generation"])
        with self.assertRaises(ArtifactError) as error:
            self.store.calendar(device,dict(body,generation=saved["generation"]))
        self.assertEqual(409,error.exception.status)
        self.assertTrue(self.store.calendar(device)["busy_only"])
    def test_legacy_upload_is_rejected_after_first_policy_acknowledgement(self):
        body=dict(read_at_ms=self.now,busy_only=True,events=[])
        self.assertEqual(1,self.store.calendar("d"*32,body)["generation"])
        with self.assertRaises(ArtifactError) as error: self.store.calendar("d"*32,body)
        self.assertEqual(409,error.exception.status)
    def test_selection_narrowing_revoke_fences_broader_snapshot(self):
        device="d"*32
        event=lambda eid: dict(id=eid,start_ms=self.now,end_ms=self.now+60000)
        body=dict(read_at_ms=self.now,busy_only=True,events=[event("12"),event("13")])
        broad=self.store.calendar(device,body)
        revoked=self.store.calendar(device,dict(revoke=True,generation=broad["generation"]))
        narrowed=self.store.calendar(device,dict(body,events=[event("12")],generation=revoked["generation"]))
        with self.assertRaises(ArtifactError) as error:
            self.store.calendar(device,dict(body,generation=broad["generation"]))
        self.assertEqual(409,error.exception.status)
        self.assertEqual(narrowed,self.store.calendar(device))
    @unittest.skipUnless(cron_ready(),"Host lacks the verified per-profile native cron store context")
    def test_native_scheduler_create_pause_resume_edit_cancel_without_inference(self):
        from cron import jobs
        body=dict(operation_id="a"*32,action="create",name="Check work",prompt="Summarize local work",schedule="every 1d")
        first=routine_action(self.store,body)
        self.assertEqual(first,routine_action(self.store,body))
        self.assertEqual(1,len(jobs.list_jobs(include_disabled=True)))
        self.assertEqual("local",routines()[0]["deliver"])
        current=routines()[0]
        routine_action(self.store,dict(operation_id="b"*32,id=first["id"],fingerprint=current["fingerprint"],action="pause"))
        self.assertFalse(jobs.get_job(first["id"])["enabled"])
        with self.assertRaises(ArtifactError): routine_action(self.store,dict(operation_id="c"*32,id=first["id"],fingerprint=current["fingerprint"],action="resume"))
        for op,action in (("c","resume"),("d","edit"),("e","delete")):
            current=routines()[0]
            routine_action(self.store,dict(operation_id=op*32,id=first["id"],fingerprint=current["fingerprint"],action=action,name="Renamed",prompt="Changed task",schedule="every 2d"))
        self.assertEqual([],jobs.list_jobs(include_disabled=True))
    @unittest.skipUnless(cron_ready(),"Host lacks the verified per-profile native cron store context")
    def test_interrupted_native_creation_is_uncertain_and_never_repeated(self):
        from cron import jobs
        real=jobs.create_job
        body=dict(operation_id="a"*32,action="create",name="Once",prompt="Local",schedule="every 1d")
        def interrupted(**kwargs): real(**kwargs); raise OSError("Lost response after native commit")
        with patch.object(jobs,"create_job",side_effect=interrupted):
            with self.assertRaises(OSError): routine_action(self.store,body)
        with self.assertRaises(ArtifactError) as error: routine_action(self.store,body)
        self.assertIn("uncertain",str(error.exception))
        self.assertEqual(1,len(jobs.list_jobs(include_disabled=True)))
    @unittest.skipUnless(cron_ready(),"Host lacks the verified per-profile native cron store context")
    def test_native_jobs_cannot_cross_profile_store_contexts(self):
        routine_action(self.store,dict(operation_id="a"*32,action="create",name="Private routine",prompt="Local",schedule="every 1d"))
        with patch.dict(os.environ,{"HERMES_HOME":str(self.home/"profile-two")}):
            self.assertEqual([],routines())
            other=PlanningStore(self.home/"profile-two")
            routine_action(other,dict(operation_id="a"*32,action="create",name="Other routine",prompt="Local",schedule="every 1d"))
            self.assertEqual(["Other routine"],[r["name"] for r in routines()])
        self.assertEqual(["Private routine"],[r["name"] for r in routines()])
    def test_symlink_storage_and_invalid_times_are_rejected(self):
        (self.home/"unsafe").mkdir(); (self.home/"unsafe"/"bellpull-planning").symlink_to(self.store.root)
        with self.assertRaises(ArtifactError): PlanningStore(self.home/"unsafe")
        for due in (True,-1,"tomorrow"):
            with self.assertRaises(ArtifactError): self.store.reminder(dict(operation_id="a"*32,action="create",title="Bad",due_ms=due))


class PlanningHttpTest(unittest.IsolatedAsyncioTestCase):
    async def test_native_auth_plugin_discovery_retry_and_phone_cache(self):
        with tempfile.TemporaryDirectory() as home,patch.dict(os.environ,{"HERMES_HOME":home,"BELLPULL_NTFY_URL":""}):
            adapter=APIServerAdapter(PlatformConfig(enabled=True,extra={"key":"fixture-planning-only"}))
            app=web.Application(); stop=wire(app,adapter)
            client=TestClient(TestServer(app)); await client.start_server()
            headers={"Authorization":"Bearer fixture-planning-only"}
            try:
                self.assertEqual(401,(await client.get("/api/bellpull/planning")).status)
                capabilities=await (await client.get("/api/bellpull/capabilities",headers=headers)).json()
                self.assertTrue(capabilities["features"]["bellpull_planning"])
                body=dict(operation_id="a"*32,action="create",title="Only one",due_ms=int(time.time()*1000)+60000)
                for _ in range(2): self.assertEqual(200,(await client.post("/api/bellpull/planning/reminders",headers=headers,json=body)).status)
                snapshot=await (await client.get("/api/bellpull/planning",headers=headers)).json()
                self.assertEqual(1,len(snapshot["reminders"]))
                device="d"*32
                response=await client.put("/api/bellpull/calendar/"+device,headers=headers,json=dict(read_at_ms=int(time.time()*1000),busy_only=True,events=[]))
                self.assertEqual(200,response.status,await response.text())
                response=await client.put("/api/bellpull/calendar/"+device,headers=headers,json=dict(revoke=True))
                self.assertEqual(dict(revoked=True,generation=2),await response.json())
                self.assertEqual([],PlanningStore(Path(home)/"separate-profile").snapshot()["reminders"])
            finally:
                stop(); await client.close()
                for database in getattr(adapter,"_session_dbs",{}).values(): database.close()
