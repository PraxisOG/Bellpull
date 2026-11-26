"""Read-only observation of Hermes-owned coding processes.

CLI ownership comes from a real terminal post-tool receipt, the exact live Hermes
turn, and ProcessRegistry ownership/incarnation. Polling its rolling tail avoids
stealing on_output; missed bytes are explicitly reported, never called replay.
"""
import asyncio
from contextlib import contextmanager
import hashlib
import inspect
import json
import os
from pathlib import Path
import re
import shlex
import sqlite3
import threading
import time
import weakref

from aiohttp import web
from .artifacts import ArtifactError, checked_session
from .coding_executors import consume_frames
from .coding_workspace import capture_workspace_base
from .lineage import compression_ids, session_reads_supported
from .principal import artifact_scope

MAX_LINE = 131072
MAX_DETAIL = 16384
MAX_ROWS = 300
MAX_EVENTS = 3000
MAX_WORKS = 128


def dispatch_tool(**kwargs):
    # Hermes loads plugins separately per native profile. The process registry is
    # shared; a Bellpull-owned observer set bridges those modules without touching
    # its single output/close sinks. Exact turn/process ownership still gates data.
    from tools.process_registry import process_registry
    with process_registry._lock:
        observers=list(getattr(process_registry,"_bellpull_coding_observers",()))
    for observer in observers:
        observer.observe_tool(**kwargs)


def parent_run(adapter, session_id, turn_id):
    """Identify the native run executing this tool hook on either gateway path."""
    try:
        from gateway.session_api_turn import api_execution
        execution = api_execution.get()
    except ImportError:
        execution = None
    if execution is not None:
        if execution.get("adapter") is not adapter:
            return None
        from .authority_routes import admitted_agent
        claim = admitted_agent(adapter, session_id)
        if claim:
            run_id, _, _, agent = claim
            if getattr(agent, "_current_turn_id", None) == turn_id:
                return run_id
        return None
    matches = [rid for rid, agent in list(getattr(adapter, "_active_run_agents", {}).items())
               if getattr(agent, "_current_turn_id", None) == turn_id and getattr(agent, "session_id", None) == session_id]
    return matches[0] if len(matches) == 1 else None


def clipped(value, limit=MAX_DETAIL):
    return str(value or "")[:limit]


def cli_invocation(command):
    """Accept only an explicit, simple OpenCode JSON invocation, never substrings.

    Shell pipelines/scripts/TUI/remote attach need separate adapters. Rejecting
    them does not affect execution, it only declines this structured monitor.
    """
    if not isinstance(command, str) or len(command) > 32768:
        return False
    try:
        # shlex.shlex preserves quotes by default; split with whitespace enabled.
        lexer = shlex.shlex(command, posix=True, punctuation_chars=";&|<>()")
        lexer.whitespace_split = True
        args = list(lexer)
    except ValueError:
        return False
    if any(arg and set(arg) <= set(";&|<>()") for arg in args):
        return False
    if len(args) < 4 or Path(args[0]).name != "opencode" or args[1] != "run":
        return False
    return "--format=json" in args or any(args[i:i+2] == ["--format", "json"] for i in range(len(args)-1))


def decode_line(line, session=None):
    if len(line) > MAX_LINE:
        return None
    try:
        value = json.loads(line)
    except (ValueError, RecursionError):
        return None
    if not isinstance(value, dict):
        return None
    sid, stamp = value.get("sessionID"), value.get("timestamp")
    if not isinstance(sid, str) or not re.fullmatch(r"ses_[A-Za-z0-9_-]{1,240}", sid):
        return None
    if session and sid != session:
        return None
    if type(stamp) is not int or not 0 <= stamp <= 9223372036854775807:
        return None
    kind = value.get("type")
    if kind == "error":
        error = value.get("error")
        if not isinstance(error, dict) or not isinstance(error.get("name"), str):
            return None
        detail = error.get("data", {}).get("message", "") if isinstance(error.get("data", {}), dict) else ""
        return sid, dict(key=f"error:{stamp}:{clipped(error['name'],256)}", timestamp_ms=stamp,
                         kind="error", label=clipped(error["name"],256), detail=clipped(detail), truncated=len(str(detail)) > MAX_DETAIL)
    expected = {"text":"text", "reasoning":"reasoning", "tool_use":"tool", "step_start":"step-start", "step_finish":"step-finish"}
    part = value.get("part")
    if kind not in expected or not isinstance(part, dict) or part.get("type") != expected[kind] or part.get("sessionID") != sid:
        return None
    if any(not isinstance(part.get(k), str) or not 0 < len(part[k]) <= 256 for k in ("id","messageID")):
        return None
    row = dict(key=f"{len(part['messageID'])}:{part['messageID']}{part['id']}", timestamp_ms=stamp,
               kind={"tool_use":"tool", "step_start":"step_started", "step_finish":"step_finished"}.get(kind,kind),
               label="OpenCode response" if kind == "text" else "OpenCode reasoning" if kind == "reasoning" else "OpenCode step", detail="")
    if kind == "tool_use":
        state = part.get("state")
        if not isinstance(state, dict) or state.get("status") not in ("completed","error") or not isinstance(part.get("tool"),str):
            return None
        row.update(label=clipped(part["tool"],256), outcome=state["status"])
        detail = state.get("output" if state["status"] == "completed" else "error")
        if part["tool"] == "bash" and isinstance(state.get("input"),dict):
            command = state["input"].get("command")
            if isinstance(command,str): row["command"] = clipped(command)
    else:
        detail = part.get("text", "") if kind in ("text","reasoning") else part.get("reason", "")
    if not isinstance(detail,str):
        return None
    row.update(detail=clipped(detail), truncated=len(detail) > MAX_DETAIL)
    return sid, row


def codex_exec_json(flags):
    # Consume the value of each recognized global option before looking for
    # the subcommand. A model, profile or prompt may itself be named "exec".
    value_options = {"-a", "--ask-for-approval", "-c", "--config", "-m", "--model", "-p", "--profile",
                     "-s", "--sandbox", "--enable", "--disable"}
    i = 0
    while i < len(flags):
        flag = flags[i]
        name, equal, value = flag.partition("=")
        if name in value_options:
            if equal and not value:
                return False
            i += 1 if equal else 2
        elif flag in {"--strict-config", "--no-daemon"}:
            i += 1
        else:
            return flag == "exec" and "--json" in flags[i + 1:]
    return False


def launch_transport(command):
    if cli_invocation(command):
        return "cli_json"
    if not isinstance(command, str) or len(command) > 32768:
        return None
    try:
        lexer=shlex.shlex(command,posix=True,punctuation_chars=";&|<>()")
        lexer.whitespace_split=True
        args=list(lexer)
    except ValueError:
        return None
    if not args or any(a and set(a) <= set(";&|<>()") for a in args):
        return None
    executable = Path(args[0]).name
    flags = args[1:args.index("--")] if "--" in args else args[1:]
    if executable == "codex":
        return "codex_json" if codex_exec_json(flags) else None
    if executable == "claude":
        printing = "-p" in flags or "--print" in flags
        stream = "--output-format=stream-json" in flags or any(
            flags[i:i+2] == ["--output-format", "stream-json"] for i in range(len(flags)-1))
        return "claude_stream_json" if printing and stream else None
    if executable != "opencode":
        return None
    # Simple PTY/TUI and plain run launches have only an inert rolling text tail.
    # Server/attach/administrative subcommands require another verified adapter.
    if len(args)==1 or args[1]=="run" or args[1].startswith("--"):
        return "terminal_text"
    return None


class CodingStore:
    def __init__(self, home):
        self.home = Path(home).resolve()
        self.root = self.home / "bellpull-coding" / artifact_scope(self.home)
        if self.root.is_symlink() or self.root.parent.is_symlink():
            raise ArtifactError("Unsafe coding storage.",410)
        self.root.mkdir(parents=True,exist_ok=True,mode=0o700)
        self.path = self.root / "activity.sqlite"
        if self.path.is_symlink() or self.path.exists() and self.path.stat().st_nlink != 1:
            raise ArtifactError("Unsafe coding database.",410)
        with self.db() as conn:
            conn.executescript("""
                CREATE TABLE IF NOT EXISTS works(id TEXT PRIMARY KEY, session TEXT NOT NULL, data TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS rows(work TEXT NOT NULL, key TEXT NOT NULL, seq INTEGER NOT NULL, data TEXT NOT NULL, PRIMARY KEY(work,key));
                CREATE TABLE IF NOT EXISTS events(seq INTEGER PRIMARY KEY AUTOINCREMENT, work TEXT NOT NULL, data TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS gaps(session TEXT PRIMARY KEY);
            """)
        os.chmod(self.path,0o600)

    @contextmanager
    def db(self):
        conn=sqlite3.connect(self.path,timeout=5)
        try:
            with conn: yield conn
        finally: conn.close()

    def works(self):
        with self.db() as conn:
            return [json.loads(r[0]) for r in conn.execute("SELECT data FROM works ORDER BY rowid DESC LIMIT ?",(MAX_WORKS,))]

    def save(self, work, rows=()):
        with self.db() as conn:
            conn.execute("BEGIN IMMEDIATE")
            present=conn.execute("SELECT 1 FROM works WHERE id=?",(work["id"],)).fetchone()
            if not present:
                all_works=conn.execute("SELECT id,session,data FROM works ORDER BY rowid").fetchall()
                while len(all_works)>=MAX_WORKS:
                    old=next((w for w in all_works if json.loads(w[2])["state"] not in ("running","unknown")),None)
                    if old is None:
                        conn.execute("INSERT OR IGNORE INTO gaps VALUES(?)",(work["session_id"],))
                        return False # Never evict an observed live child for newer work.
                    wid,session,_=old
                    conn.execute("INSERT OR IGNORE INTO gaps VALUES(?)",(session,))
                    for table in ("rows","events","works"):
                        conn.execute(f"DELETE FROM {table} WHERE {'id' if table=='works' else 'work'}=?",(wid,))
                    all_works.remove(old)
            for row in rows:
                self._write_row(conn, work, row)
            conn.execute("INSERT INTO works VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET data=excluded.data",
                         (work["id"],work["session_id"],json.dumps(work)))
            conn.execute("DELETE FROM gaps WHERE rowid IN (SELECT rowid FROM gaps ORDER BY rowid DESC LIMIT -1 OFFSET 1000)")
            return True

    @staticmethod
    def _write_row(conn, work, row):
        data=json.dumps(dict(row,work_id=work["id"]),sort_keys=True)
        old=conn.execute("SELECT data FROM rows WHERE work=? AND key=?",(work["id"],row["key"])).fetchone()
        if old and old[0] == data: return
        seq=conn.execute("INSERT INTO events(work,data) VALUES(?,?)",(work["id"],data)).lastrowid
        conn.execute("INSERT INTO rows VALUES(?,?,?,?) ON CONFLICT(work,key) DO UPDATE SET seq=excluded.seq,data=excluded.data",
                     (work["id"],row["key"],seq,data))
        if conn.execute("SELECT COUNT(*) FROM rows WHERE work=?",(work["id"],)).fetchone()[0]>MAX_ROWS:
            work["gap"]=True
        conn.execute("DELETE FROM rows WHERE rowid IN (SELECT rowid FROM rows WHERE work=? ORDER BY seq DESC LIMIT -1 OFFSET ?)",(work["id"],MAX_ROWS))
        conn.execute("DELETE FROM events WHERE seq <= (SELECT COALESCE(MAX(seq),0)-? FROM events)",(MAX_EVENTS,))

    def snapshot(self, session, aliases, after=None, all_executors=False):
        with self.db() as conn:
            conn.execute("BEGIN")
            records = conn.execute("SELECT data FROM works ORDER BY rowid DESC LIMIT ?", (MAX_WORKS,))
            works = []
            for record, in records:
                work = json.loads(record)
                if work["session_id"] in aliases and (all_executors or work.get("executor", "opencode") == "opencode"):
                    works.append(work)
            ids = {work["id"] for work in works}
            low=conn.execute("SELECT COALESCE(MIN(seq),0) FROM events").fetchone()[0]
            high=(conn.execute("SELECT seq FROM sqlite_sequence WHERE name='events'").fetchone() or [0])[0]
            expired=any(conn.execute("SELECT 1 FROM gaps WHERE session=?",(alias,)).fetchone() for alias in aliases)
            reset=after is None or after > high or after < max(0,low-1)
            placeholders=",".join("?" for _ in ids) or "NULL"
            # Per-work retention can be intact while the combined chat view omits older rows.
            window_clipped = len(ids) > 1 and conn.execute(
                f"SELECT 1 FROM rows WHERE work IN ({placeholders}) LIMIT 1 OFFSET ?",
                (*ids, MAX_ROWS),
            ).fetchone() is not None
            if not reset and conn.execute(f"SELECT COUNT(*) FROM events WHERE work IN ({placeholders}) AND seq>?",(*ids,after)).fetchone()[0]>MAX_ROWS:
                reset=True
            source="rows" if reset else "events"
            values=conn.execute(f"SELECT seq,data,work FROM {source} WHERE work IN ({placeholders}) AND seq>? ORDER BY seq DESC LIMIT ?",(*ids,0 if reset else after,MAX_ROWS)).fetchall()
            rows=[dict(json.loads(data),sequence=seq) for seq,data,work in reversed(values) if work in ids][-MAX_ROWS:]
        public=[]
        for work in works:
            public.append({k:v for k,v in work.items() if k not in ("owner_task_id","incarnation","observed_total","workspace","home","awaiting_thread","discard_frame","workspace_baseline","claude_commands")})
        executors = ["opencode", "codex", "claude"] if all_executors else ["opencode"]
        return dict(version=1,session_id=session,session_ids=aliases,cursor=high,reset=reset,executors=executors,
                    gap=window_clipped or expired or any(w.get("gap") for w in works) or after is not None and reset,
                    works=public,rows=rows,observed_at=time.time())


class Observer:
    def __init__(self, adapter):
        self.adapter=adapter
        self.legacy_sessions = callable(getattr(adapter, "_ensure_session_db_async", None))
        self.can_read_sessions = session_reads_supported(adapter)
        self.stores={}
        self.partial={}
        self.exit_receipts = {}
        self.lock=threading.RLock()
        self.active=True
        self.task=None
        try:
            from tools.process_registry import process_registry, ProcessSession
            from model_tools import _emit_post_tool_call_hook
            self.registry=process_registry
            self.supported=all(k in inspect.signature(_emit_post_tool_call_hook).parameters for k in ("task_id","session_id","turn_id","tool_call_id")) and all(k in ProcessSession.__dataclass_fields__ for k in ("owner_task_id","parent_session_id","started_at","total_output_chars"))
        except (ImportError,AttributeError,TypeError,ValueError):
            self.registry=None; self.supported=False
        if self.registry is not None:
            with self.registry._lock:
                if not hasattr(self.registry,"_bellpull_coding_observers"):
                    self.registry._bellpull_coding_observers=weakref.WeakSet()
                self.registry._bellpull_coding_observers.add(self)

    def store(self, home):
        key=str(Path(home).resolve())
        with self.lock:
            if key not in self.stores:
                if len(self.stores) >= 64: raise ArtifactError("Too many active coding profiles.",503)
                store=CodingStore(key)
                for work in store.works():
                    if work["state"] == "running":
                        work.update(state="unknown",gap=True,note="Gateway observation restarted; verifying child identity.")
                        store.save(work)
                self.stores[key]=store
            return self.stores[key]

    async def start(self, _app):
        self.task=asyncio.create_task(self.watch())

    def stop(self):
        self.active=False
        if self.task: self.task.cancel()
        if self.registry is not None:
            with self.registry._lock:
                self.registry._bellpull_coding_observers.discard(self)

    async def close(self):
        self.stop()
        if self.task:
            await asyncio.gather(self.task,return_exceptions=True)

    async def watch(self):
        while self.active:
            try: await asyncio.to_thread(self.tick)
            except (OSError,sqlite3.Error,ArtifactError): pass # Retry; no invented completion.
            await asyncio.sleep(.5)

    def observe_tool(self, tool_name="", args=None, result=None, task_id="", session_id="", turn_id="", tool_call_id="", **_kwargs):
        if not self.active or not self.supported or tool_name != "terminal" or not all((task_id,session_id,turn_id,tool_call_id)):
            return
        args=args if isinstance(args,dict) else {}
        transport=launch_transport(args.get("command"))
        if not transport: return
        run_id = parent_run(self.adapter, session_id, turn_id)
        if run_id is None:
            return
        try:
            result=json.loads(result) if isinstance(result,str) else result
        except (ValueError,RecursionError): return
        if not isinstance(result,dict): return
        pid=result.get("session_id")
        if not isinstance(pid,str) or not pid.startswith("proc_"): return
        process=self.registry.get(pid)
        if process is None or process.id != pid or process.owner_task_id != task_id or process.parent_session_id != session_id or process.command != args["command"]:
            return
        from hermes_constants import get_hermes_home
        home=Path(get_hermes_home()).resolve()
        store=self.store(home)
        wid=hashlib.sha256(f"{run_id}\0{tool_call_id}\0{pid}\0{process.started_at}".encode()).hexdigest()[:32]
        workspace = str(Path(process.cwd).resolve()) if process.pid_scope == "host" and process.cwd else None
        baseline = capture_workspace_base(workspace) if workspace else None
        with self.lock:
            if any(w["id"]==wid for w in store.works()): return
            work=dict(id=wid,session_id=session_id,run_id=run_id,process_id=pid,open_code_session_id=None,
                      transport=transport,state="running",workspace_id=wid if workspace else None,workspace_label=workspace,
                      workspace=workspace,owner_task_id=task_id,incarnation=str(process.started_at),observed_total=0,
                      started_at=process.started_at,gap=False,note="CLI reports completed text/tool parts; it has no interactive request feed." if transport=="cli_json" else "Terminal text only; redraws, partial output and prompts are not structured requests.")
            if transport in ("codex_json", "claude_stream_json"):
                work.update(executor="codex" if transport == "codex_json" else "claude",
                            executor_session_id=None, note="Structured output from the Hermes-owned coding process.")
            if baseline:
                work["workspace_baseline"] = baseline
            if not store.save(work): return
            self.sample(store,work,process)

    def tick(self):
        if not self.registry: return
        with self.lock:
            retained = {}
            for store in list(self.stores.values()):
                for work in store.works():
                    if work["state"] in ("completed", "failed"):
                        process = self.exit_receipt(store, work, retained)
                        if self.matches_process(work, process):
                            with process._lock:
                                if not process.exited or process.completion_reason != "killed":
                                    continue
                                work.update(state="stopped", exit_code=process.exit_code)
                            store.save(work)
                        continue
                    if work["state"] not in ("running","unknown"): continue
                    process=self.registry.get(work["process_id"])
                    if not self.matches_process(work, process):
                        work.update(state="lost",gap=True,note="The recorded child is unavailable. Its completion is unverified.")
                        store.save(work); continue
                    self.sample(store,work,process)
            self.exit_receipts = retained

    def exit_receipt(self, store, work, retained):
        # Hermes can update the receipt from exited to killed after the reader
        # reports EOF. Follow that receipt without retaining the process or its output.
        key = (store.path, work["id"])
        if key not in self.exit_receipts:
            process = self.registry.get(work["process_id"])
            retained[key] = weakref.ref(process) if process is not None else None
            return process
        ref = self.exit_receipts[key]
        if ref is not None and (process := ref()) is not None:
            retained[key] = ref
            return process
        retained[key] = None
        # Look up the final durable receipt once when the live object disappears.
        return self.registry.get(work["process_id"]) if ref is not None else None

    @staticmethod
    def matches_process(work, process):
        return (process is not None and process.id == work["process_id"]
                and str(getattr(process, "started_at", None)) == work["incarnation"]
                and getattr(process, "owner_task_id", None) == work["owner_task_id"]
                and getattr(process, "parent_session_id", None) == work["session_id"])

    def sample(self, store, work, process):
        with process._lock:
            total,tail=process.total_output_chars,process.output_buffer
            exited,code,reason=process.exited,process.exit_code,process.completion_reason
        work["state"]=("stopped" if reason=="killed" else "lost" if reason=="lost" else "completed" if code==0 else "failed" if code is not None else "unknown") if exited else "running"
        if exited:
            work["exit_code"] = code
        rows = []
        if work.get("transport") in ("codex_json", "claude_stream_json"):
            consume_frames(work, total, tail, exited, lambda _, row: rows.append(row), MAX_LINE)
            store.save(work, rows)
            return
        delta=total-work["observed_total"]
        identity=(str(store.path),work["id"])
        pending=self.partial.get(identity,"")
        if delta < 0 or delta > len(tail):
            work["gap"]=True
            pending=""
            chunk=tail.partition("\n")[2] # First retained line may be incomplete.
        else: chunk=tail[-delta:] if delta else ""
        for index,line in enumerate((pending+chunk).splitlines(keepends=True)):
            if not line.endswith(("\n","\r")):
                pending=line if len(line)<=MAX_LINE else ""
                if len(line)>MAX_LINE: work["gap"]=True
                continue
            pending=""
            decoded=decode_line(line,work.get("open_code_session_id")) if work.get("transport","cli_json")=="cli_json" else None
            if decoded:
                sid,row=decoded
                work["open_code_session_id"]=sid
                rows.append(row)
            elif line.strip():
                try: foreign=json.loads(line)
                except (ValueError,RecursionError): foreign=None
                if isinstance(foreign,dict) and isinstance(foreign.get("sessionID"),str) and work.get("open_code_session_id") and foreign["sessionID"]!=work["open_code_session_id"]:
                    continue
                # Plain warnings are inert text, never actionable permissions.
                clean=re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)","",line.rstrip())
                rows.append(dict(key=f"output:{total-delta}:{index}",kind="output",label="Terminal output",detail=clipped(clean),timestamp_ms=int(time.time()*1000),truncated=len(clean)>MAX_DETAIL))
        work["observed_total"]=total
        if exited:
            if pending:
                decoded=decode_line(pending,work.get("open_code_session_id"))
                if decoded:
                    work["open_code_session_id"],row=decoded
                    rows.append(row)
                else:
                    try: foreign=json.loads(pending)
                    except (ValueError,RecursionError): foreign=None
                    if not isinstance(foreign,dict) or not foreign.get("sessionID"):
                        rows.append(dict(key="final-output",kind="output",label="Final terminal output",detail=clipped(pending),timestamp_ms=int(time.time()*1000),truncated=len(pending)>MAX_DETAIL))
        store.save(work, rows)
        if exited:
            self.partial.pop(identity,None)
        else:
            self.partial[identity]=pending

    def require_profile_owner(self, request):
        room_grant = getattr(self.adapter, "_room_grant_token", None)
        if callable(room_grant) and room_grant(request):
            raise ArtifactError("Account-level API credentials are required for coding activity.", 403)

    async def session_aliases(self, session):
        if self.legacy_sessions:
            database = await self.adapter._ensure_session_db_async()
            if database is None:
                raise ArtifactError("Session database is unavailable.", 503)
            return await asyncio.to_thread(compression_ids, database, session)
        store = self.adapter._store_beside(await self.adapter._ensure_store_async())
        if store is None:
            raise ArtifactError("Session database is unavailable.", 503)
        aliases = await store.aio.lineage.lineage(session)
        return aliases if len(aliases) <= 1000 and session in aliases else [session]

    async def snapshot(self, request):
        self.require_profile_owner(request)
        session=checked_session(request.match_info["session_id"])
        _,error=await self.adapter._get_existing_session_or_404(session)
        if error is not None: return error
        from hermes_constants import get_hermes_home
        home=Path(get_hermes_home())
        store=self.store(home)
        aliases=await self.session_aliases(session)
        raw=request.query.get("after")
        if raw is not None and not re.fullmatch(r"[0-9]{1,18}",raw):
            raise ArtifactError("Invalid activity cursor.",400)
        value=await asyncio.to_thread(store.snapshot,session,aliases,int(raw) if raw is not None else None,
                                      request.query.get("executors") == "all")
        if not self.supported:
            value["executors"] = []
        value.update(available=self.supported,note="" if self.supported else "This Hermes version lacks verified tool/turn/process ownership hooks. Update Hermes to enable observation.")
        return web.json_response(value,headers={"Cache-Control":"no-store"})

    async def workspace(self, request):
        self.require_profile_owner(request)
        from hermes_constants import get_hermes_home
        from .coding_workspace import inspect_workspace
        wid=request.match_info["work_id"]
        store=self.store(get_hermes_home())
        work=next((w for w in store.works() if w["id"]==wid),None)
        if not work: raise ArtifactError("Coding work not found.",404)
        _,error=await self.adapter._get_existing_session_or_404(work["session_id"])
        if error is not None: return error
        if not work.get("workspace"): raise ArtifactError("This backend has no verified local workspace.",409)
        value=await asyncio.to_thread(inspect_workspace,work["workspace"],request.query.get("file"),work.get("workspace_baseline"))
        return web.json_response(value,headers={"Cache-Control":"no-store"})

    def routes(self):
        return [("GET","/api/bellpull/sessions/{session_id}/coding",self.snapshot),
                ("GET","/api/bellpull/coding/{work_id}/workspace",self.workspace)]
