"""Prototype reminders, native cron controls and opt-in phone calendar cache.

Never runs inference, scripts, calendar writes or a second scheduler. Calendar
proposals remain pending until an explicit phone action reports a verified result.
"""
import asyncio
from contextlib import contextmanager
import hashlib
import fcntl
import inspect
import json
import os
from pathlib import Path
import re
import sqlite3
import time
from aiohttp import web
from .artifacts import ArtifactError
from .principal import artifact_scope


def identity(value):
    if not isinstance(value,str) or not re.fullmatch(r"[a-f0-9]{32}",value):
        raise ArtifactError("Invalid operation identity.",400)
    return value


def text(value, maximum=200):
    if not isinstance(value,str) or not value.strip() or len(value)>maximum:
        raise ArtifactError("Missing or oversized text.",400)
    return value.strip()


def instant(value):
    if type(value) is not int or not 0<value<32503680000000:
        raise ArtifactError("Use an absolute UTC time in milliseconds.",400)
    return value


def digest(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(",",":")).encode()).hexdigest()


class PlanningStore:
    def __init__(self,home):
        self.root=Path(home)/"bellpull-planning"/artifact_scope(home)
        if self.root.is_symlink() or self.root.parent.is_symlink(): raise ArtifactError("Unsafe planning storage.",410)
        self.root.mkdir(parents=True,exist_ok=True,mode=0o700)
        self.path=self.root/"planning.sqlite"
        if self.path.is_symlink() or self.path.exists() and self.path.stat().st_nlink!=1: raise ArtifactError("Unsafe planning database.",410)
        with self.db() as c:
            c.executescript("""
            CREATE TABLE IF NOT EXISTS reminders(id TEXT PRIMARY KEY,data TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS receipts(id TEXT PRIMARY KEY,hash TEXT NOT NULL,result TEXT);
            CREATE TABLE IF NOT EXISTS calendars(device TEXT PRIMARY KEY,data TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS calendar_policies(device TEXT PRIMARY KEY,generation INTEGER NOT NULL,busy_only INTEGER);
            CREATE TABLE IF NOT EXISTS proposals(id TEXT PRIMARY KEY,data TEXT NOT NULL);
            """)
        self.path.chmod(0o600)

    @contextmanager
    def db(self):
        c=sqlite3.connect(self.path,timeout=15)
        try:
            with c:
                c.execute("BEGIN IMMEDIATE")
                yield c
        finally: c.close()

    def snapshot(self):
        with self.db() as c:
            reminders=[json.loads(v) for v, in c.execute("SELECT data FROM reminders ORDER BY rowid DESC LIMIT 1000")]
            proposals=[json.loads(v) for v, in c.execute("SELECT data FROM proposals ORDER BY rowid DESC LIMIT 100")]
        return dict(version=1,reminders=reminders,proposals=proposals,observed_at=time.time())

    def reminder(self,body):
        operation=identity(body.get("operation_id")); fingerprint=digest(body)
        with self.db() as c:
            receipt=c.execute("SELECT hash,result FROM receipts WHERE id=?",(operation,)).fetchone()
            if receipt:
                if receipt[0]!=fingerprint: raise ArtifactError("Operation reused for different content.",409)
                return json.loads(receipt[1])
            action=body.get("action"); rid=identity(body.get("id",operation))
            old=c.execute("SELECT data FROM reminders WHERE id=?",(rid,)).fetchone()
            value=json.loads(old[0]) if old else None
            if action=="create":
                if value: raise ArtifactError("Reminder identity already exists.",409)
                if c.execute("SELECT COUNT(*) FROM reminders").fetchone()[0]>=1000: raise ArtifactError("Reminder limit reached. Remove finished reminders.",409)
                value=dict(id=rid,title=text(body.get("title")),due_ms=instant(body.get("due_ms")),state="pending",revision=1)
            else:
                if not value: raise ArtifactError("Reminder not found.",404)
                if type(body.get("revision")) is not int or body["revision"]!=value["revision"]: raise ArtifactError("Reminder changed. Refresh before editing.",409)
                if action=="delete":
                    c.execute("DELETE FROM reminders WHERE id=?",(rid,)); value=dict(id=rid,deleted=True)
                elif action in ("complete","dismiss","reopen"):
                    value["state"]={"complete":"completed","dismiss":"dismissed","reopen":"pending"}[action]
                elif action in ("edit","snooze"):
                    value["due_ms"]=instant(body.get("due_ms"))
                    if action=="edit": value["title"]=text(body.get("title"))
                    value["state"]="pending"
                else: raise ArtifactError("Unknown reminder action.",400)
                if not value.get("deleted"): value["revision"]+=1
            if not value.get("deleted"):
                c.execute("INSERT INTO reminders VALUES(?,?) ON CONFLICT(id) DO UPDATE SET data=excluded.data",(rid,json.dumps(value)))
            c.execute("INSERT INTO receipts VALUES(?,?,?)",(operation,fingerprint,json.dumps(value)))
            self.prune(c)
            return value

    @staticmethod
    def prune(c):
        c.execute("DELETE FROM receipts WHERE rowid IN (SELECT rowid FROM receipts WHERE result IS NOT NULL ORDER BY rowid DESC LIMIT -1 OFFSET 4000)")

    def calendar(self,device,body=None):
        identity(device)
        with self.db() as c:
            if body is not None:
                state=c.execute("SELECT generation,busy_only FROM calendar_policies WHERE device=?",(device,)).fetchone()
                generation=state[0] if state else 0
                if body.get("revoke") is True:
                    generation+=1
                    c.execute("INSERT INTO calendar_policies VALUES(?,?,NULL) ON CONFLICT(device) DO UPDATE SET generation=excluded.generation,busy_only=NULL",(device,generation))
                    c.execute("DELETE FROM calendars WHERE device=?",(device,))
                    for pid,data in c.execute("SELECT id,data FROM proposals").fetchall():
                        if json.loads(data).get("device")==device: c.execute("DELETE FROM proposals WHERE id=?",(pid,))
                    return dict(revoked=True,generation=generation)
                supplied=body.get("generation",0)
                if type(supplied) is not int or not 0<=supplied<9223372036854775807:
                    raise ArtifactError("Invalid calendar policy generation.",400)
                if supplied!=generation:
                    raise ArtifactError("Stale calendar policy generation.",409)
                if type(body.get("read_at_ms")) is not int or abs(time.time()*1000-body["read_at_ms"])>300000:
                    raise ArtifactError("Calendar snapshot must be a fresh phone read.",409)
                if type(body.get("busy_only")) is not bool or not isinstance(body.get("events"),list) or len(body["events"])>500:
                    raise ArtifactError("Invalid bounded calendar snapshot.",400)
                events=[]
                for event in body["events"]:
                    e=dict(id=text(event.get("id"),100),start_ms=instant(event.get("start_ms")),end_ms=instant(event.get("end_ms")),all_day=event.get("all_day") is True)
                    if e["end_ms"]<=e["start_ms"]: raise ArtifactError("Invalid event interval.",400)
                    if not body["busy_only"]: e.update(title=text(event.get("title"),300),fingerprint=text(event.get("fingerprint"),64))
                    events.append(e)
                # Uploads carry busy-only policy but not the selected calendar set.
                # Selection changes use an explicit revoke barrier on the phone.
                old=c.execute("SELECT data FROM calendars WHERE device=?",(device,)).fetchone()
                previous=state[1] if state else (json.loads(old[0])["busy_only"] if old else None)
                if state is None or previous is not None and bool(previous)!=body["busy_only"]: generation+=1
                c.execute("INSERT INTO calendar_policies VALUES(?,?,?) ON CONFLICT(device) DO UPDATE SET generation=excluded.generation,busy_only=excluded.busy_only",(device,generation,int(body["busy_only"])))
                body=dict(device=device,generation=generation,read_at_ms=body["read_at_ms"],busy_only=body["busy_only"],events=events)
                if c.execute("SELECT COUNT(*) FROM calendars").fetchone()[0]>=16 and not c.execute("SELECT 1 FROM calendars WHERE device=?",(device,)).fetchone(): raise ArtifactError("Too many calendar devices.",409)
                c.execute("INSERT INTO calendars VALUES(?,?) ON CONFLICT(device) DO UPDATE SET data=excluded.data",(device,json.dumps(body)))
            row=c.execute("SELECT data FROM calendars WHERE device=?",(device,)).fetchone()
            return json.loads(row[0]) if row else None

    def propose(self,args):
        pid=identity(args.get("operation_id")); device=identity(args.get("device"))
        calendar=self.calendar(device)
        if not calendar: raise ArtifactError("Phone calendar sharing is not enabled.",409)
        if time.time()*1000-calendar["read_at_ms"]>86400000: raise ArtifactError("Calendar cache is stale. Ask the user to refresh Bellpull.",409)
        action=args.get("action")
        if action not in ("create_event","edit_event","delete_event"): raise ArtifactError("Invalid calendar proposal.",400)
        value=dict(id=pid,device=device,action=action,state="pending",title=text(args.get("title")),created_at=time.time())
        if action!="delete_event":
            value.update(start_ms=instant(args.get("start_ms")),end_ms=instant(args.get("end_ms")))
            if value["end_ms"]<=value["start_ms"]: raise ArtifactError("Event end must follow start.",400)
        if action!="create_event":
            value.update(event_id=text(args.get("event_id"),100),fingerprint=text(args.get("fingerprint"),64))
            if calendar["busy_only"] or not any(e["id"]==value["event_id"] and e.get("fingerprint")==value["fingerprint"] for e in calendar["events"]): raise ArtifactError("Event is absent or changed. Refresh the phone calendar.",409)
        with self.db() as c:
            old=c.execute("SELECT data FROM proposals WHERE id=?",(pid,)).fetchone()
            if old:
                previous=json.loads(old[0]); compared={**previous,"state":"pending"}; compared.pop("result",None)
                compared["created_at"]=value["created_at"]
                if compared!=value: raise ArtifactError("Proposal identity reused.",409)
                return previous
            if c.execute("SELECT COUNT(*) FROM proposals").fetchone()[0]>=100: raise ArtifactError("Resolve old proposals first.",409)
            c.execute("INSERT INTO proposals VALUES(?,?)",(pid,json.dumps(value)))
        return value

    def resolve(self,body):
        with self.db() as c:
            row=c.execute("SELECT data FROM proposals WHERE id=?",(identity(body.get("id")),)).fetchone()
            if not row: raise ArtifactError("Proposal not found.",404)
            value=json.loads(row[0])
            if value["device"]!=identity(body.get("device")): raise ArtifactError("Proposal belongs to another phone.",403)
            state=body.get("state")
            if state not in ("applied","rejected"): raise ArtifactError("Invalid proposal result.",400)
            result=text(body.get("result"),500)
            if value["state"]!="pending" and (value["state"]!=state or value.get("result")!=result): raise ArtifactError("Proposal already resolved.",409)
            value.update(state=state,result=result)
            c.execute("UPDATE proposals SET data=? WHERE id=?",(json.dumps(value),value["id"]))
            return value


def cron_ready():
    try:
        from cron import jobs
        return all(callable(getattr(jobs,k,None)) for k in ("create_job","list_jobs","get_job","update_job","pause_job","resume_job","remove_job","use_cron_store")) and "include_disabled" in inspect.signature(jobs.list_jobs).parameters
    except (ImportError,ValueError): return False


def routines():
    if not cron_ready(): raise ArtifactError("Native Hermes scheduler is unavailable.",501)
    from cron import jobs
    from hermes_constants import get_hermes_home
    with jobs.use_cron_store(get_hermes_home()):
        return _routines(jobs)


def _routines(jobs):
    result=[]
    for job in jobs.list_jobs(include_disabled=True)[:500]:
        result.append({k:job.get(k) for k in ("id","name","prompt","schedule_display","next_run_at","last_run_at","last_status","last_error","enabled","state","deliver","no_agent")})
        for key,maximum in (("name",200),("prompt",10000),("last_error",2000),("schedule_display",500)):
            if isinstance(result[-1][key],str): result[-1][key]=result[-1][key][:maximum]
        result[-1]["fingerprint"]=digest(jobs.get_job(job["id"]) or job)
        schedule=job.get("schedule") or {}
        result[-1]["schedule_input"]=(schedule.get("run_at") if schedule.get("kind")=="once" else f"every {schedule.get('minutes')}m" if schedule.get("kind")=="interval" else schedule.get("expr"))
        result[-1]["last_delivery_error"]=job.get("last_delivery_error")
    return result


def routine_action(store,body):
    fd=os.open(store.root/".routine-lock",os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,"a") as locked:
        if os.fstat(locked.fileno()).st_nlink!=1: raise ArtifactError("Unsafe routine lock.",410)
        fcntl.flock(locked,fcntl.LOCK_EX)
        if not cron_ready(): raise ArtifactError("Native profile-safe Hermes scheduler is unavailable.",501)
        from cron import jobs
        from hermes_constants import get_hermes_home
        with jobs.use_cron_store(get_hermes_home()):
            return _routine_action(store,body)


def _routine_action(store,body):
    from cron import jobs
    if not cron_ready(): raise ArtifactError("Native Hermes scheduler is unavailable.",501)
    op=identity(body.get("operation_id")); hashed=digest(body)
    # Persist intent before native mutation. A crash has an explicit uncertain state;
    # never repeat admission just because the HTTP response was lost.
    with store.db() as c:
        old=c.execute("SELECT hash,result FROM receipts WHERE id=?",(op,)).fetchone()
        if old:
            if old[0]!=hashed: raise ArtifactError("Operation identity reused.",409)
            if old[1] is None: raise ArtifactError("Routine change outcome is uncertain. Refresh routines before taking another action.",409)
            return json.loads(old[1])
        action=body.get("action")
        if action not in ("create","edit","pause","resume","delete"): raise ArtifactError("Invalid routine action.",400)
        if action=="create":
            fields=dict(name=text(body.get("name")),prompt=text(body.get("prompt"),10000),schedule=text(body.get("schedule"),200),deliver="local")
            try: jobs.parse_schedule(fields["schedule"])
            except (ValueError,TypeError) as exc: raise ArtifactError("Invalid Hermes schedule. Use an interval, ISO timestamp or cron expression.",400) from exc
        else:
            jid=text(body.get("id"),100)
            job=jobs.get_job(jid)
            if not job: raise ArtifactError("Routine no longer exists.",404)
            if body.get("fingerprint")!=digest(job): raise ArtifactError("Routine changed. Refresh before editing.",409)
            if action=="edit":
                if job.get("script") or job.get("skills") or job.get("monitor_script") or job.get("monitor_url"): raise ArtifactError("Edit this advanced routine through Hermes.",409)
                fields=dict(name=text(body.get("name")),prompt=text(body.get("prompt"),10000),schedule=text(body.get("schedule"),200))
                try: jobs.parse_schedule(fields["schedule"])
                except (ValueError,TypeError) as exc: raise ArtifactError("Invalid Hermes schedule. Use an interval, ISO timestamp or cron expression.",400) from exc
        c.execute("INSERT INTO receipts VALUES(?,?,NULL)",(op,hashed))
    try:
        if action=="create": job=jobs.create_job(**fields)
        elif action=="edit": job=jobs.update_job(jid,fields)
        elif action=="pause": job=jobs.pause_job(jid)
        elif action=="resume": job=jobs.resume_job(jid)
        else: job=dict(deleted=jobs.remove_job(jid),id=jid)
        if not isinstance(job,dict): raise ArtifactError("Hermes routine changed concurrently. Refresh before retrying.",409)
        result=dict(id=job.get("id",body.get("id")),action=action)
    except (ValueError,TypeError) as exc:
        raise ArtifactError("Hermes rejected the routine change. Refresh before retrying.",409) from exc
    with store.db() as c:
        c.execute("UPDATE receipts SET result=? WHERE id=?",(json.dumps(result),op)); store.prune(c)
    return result


def current_store():
    from hermes_constants import get_hermes_home
    return PlanningStore(get_hermes_home())


def routes(adapter):
    async def snapshot(request):
        store=current_store(); value=await asyncio.to_thread(store.snapshot)
        value.update(routines=await asyncio.to_thread(routines) if cron_ready() else [],routines_available=cron_ready())
        return web.json_response(value,headers={"Cache-Control":"no-store"})
    async def mutate(request):
        body,error=await adapter._read_json_body(request)
        if error is not None: return error
        if not isinstance(body,dict): raise ArtifactError("JSON object required.",400)
        store=current_store()
        kind=request.match_info["kind"]
        if kind=="reminders": function=store.reminder
        elif kind=="proposals": function=store.resolve
        elif kind=="routines": function=lambda b:routine_action(store,b)
        else: raise ArtifactError("Unknown planning action.",404)
        value=await asyncio.to_thread(function,body)
        return web.json_response(value)
    async def calendar(request):
        body,error=await adapter._read_json_body(request)
        if error is not None: return error
        if not isinstance(body,dict): raise ArtifactError("JSON object required.",400)
        return web.json_response(await asyncio.to_thread(current_store().calendar,request.match_info["device"],body))
    return [("GET","/api/bellpull/planning",snapshot),("POST","/api/bellpull/planning/{kind}",mutate),("PUT","/api/bellpull/calendar/{device}",calendar)]


def register(ctx):
    def handle(args,**_kwargs):
        try:
            store=current_store()
            if args.get("action")=="read":
                value=store.snapshot(); now=int(time.time()*1000)
                start=instant(args.get("from_ms",now)); end=instant(args.get("until_ms",start+7*86400000))
                if not 0<end-start<=31*86400000: raise ArtifactError("Read at most 31 days of calendar context.",400)
                pending=sorted((r for r in value["reminders"] if r["state"]=="pending" and r["due_ms"]<=end),key=lambda r:r["due_ms"])
                value["reminders"]=pending[:20]; value["reminders_truncated"]=len(pending)>20
                value["proposals"]=[p for p in value["proposals"] if p["state"]=="pending"][:20]
                value["calendars"]=[]; remaining=200
                with store.db() as c:
                    for raw, in c.execute("SELECT data FROM calendars ORDER BY device"):
                        calendar=json.loads(raw)
                        candidates=[e for e in calendar["events"] if e["end_ms"]>start and e["start_ms"]<end]
                        calendar["events"]=candidates[:remaining]; calendar["truncated"]=len(candidates)>remaining
                        remaining-=len(calendar["events"]); calendar["stale"]=now-calendar["read_at_ms"]>86400000
                        value["calendars"].append(calendar)
                return json.dumps(value)
            return json.dumps(dict(proposal=store.propose(args),message="Pending in Bellpull. No calendar write has happened; the user must apply it on the phone."))
        except (ArtifactError,OSError,ValueError) as exc: return json.dumps(dict(error=str(exc)))
    ctx.register_tool(name="bellpull_planning",toolset="bellpull",description="Read reminders and opt-in phone calendar; propose phone calendar changes",schema={"name":"bellpull_planning","description":"Actions: read, create_event, edit_event, delete_event. Read at most 20 unfinished reminders and 200 opt-in phone calendar events (default next 7 days, from_ms/until_ms up to 31 days). Check phone-read freshness and truncation. This never writes a phone calendar. Do not claim an event is created before the phone reports applied. Busy-only events contain no titles or editable references. Use a stable 32-character lowercase hex operation_id when retrying a proposal.","parameters":{"type":"object","properties":{k:{"type":t} for k,t in {"action":"string","operation_id":"string","device":"string","title":"string","start_ms":"integer","end_ms":"integer","event_id":"string","fingerprint":"string","from_ms":"integer","until_ms":"integer"}.items()},"required":["action"],"additionalProperties":False}},handler=handle)
