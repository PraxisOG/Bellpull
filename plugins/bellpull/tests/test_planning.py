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
                self.assertEqual(dict(revoked=True),await response.json())
                self.assertEqual([],PlanningStore(Path(home)/"separate-profile").snapshot()["reminders"])
            finally:
                stop(); await client.close()
                for database in getattr(adapter,"_session_dbs",{}).values(): database.close()
