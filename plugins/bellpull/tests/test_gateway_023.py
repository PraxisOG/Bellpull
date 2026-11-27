"""Exercise Bellpull on the v0.23 admission and profile authorities, over loopback."""

import http.server
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

from plugins.bellpull.tests.host_storage import open_persistence


class Model(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if "/json?" in self.path:
            self.server.subscriptions.append(self.path.split("/")[1])
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson")
            self.end_headers()
            hint = {
                "run_id": "local-event",
                "session_id": "local-chat",
                "status": "completed",
                "other": "ignored",
            }
            self.wfile.write(
                (
                    json.dumps({"event": "message", "message": json.dumps(hint)}) + "\n"
                ).encode()
            )
            return
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'{"data":[{"id":"fixture","context_length":64000}]}')

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if not self.path.startswith("/v1/"):
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"ok":true}')
            return
        history = body.get("messages") or []
        last = history[-1] if history else {}
        if last.get("role") == "tool":
            self.server.tool_results.append(str(last.get("content")))
        msg = {"role": "assistant", "content": "DONE"}
        if last.get("role") != "tool" and "APPROVAL_FIXTURE" in str(
            last.get("content")
        ):
            # The operand is a nonexistent leaf of this test's private temp dir.
            import shlex

            cmd = "rm -rf -- " + shlex.quote(str(self.server.unused_path))
            msg = {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "id": "fixture_call",
                        "type": "function",
                        "function": {
                            "name": "terminal",
                            "arguments": json.dumps({"command": cmd}),
                        },
                    }
                ],
            }
        reason = "tool_calls" if "tool_calls" in msg else "stop"
        self.send_response(200)
        self.send_header(
            "Content-Type",
            "text/event-stream" if body.get("stream") else "application/json",
        )
        self.end_headers()
        if body.get("stream"):
            if "tool_calls" in msg:
                msg["tool_calls"][0]["index"] = 0
            frame = {
                "id": "fixture",
                "choices": [{"index": 0, "delta": msg, "finish_reason": reason}],
            }
            self.wfile.write(
                ("data: " + json.dumps(frame) + "\n\ndata: [DONE]\n\n").encode()
            )
        else:
            self.wfile.write(
                json.dumps(
                    {
                        "id": "fixture",
                        "choices": [
                            {"index": 0, "message": msg, "finish_reason": reason}
                        ],
                    }
                ).encode()
            )


@unittest.skipUnless(open_persistence, "Requires the v0.23 profile and run authorities")
class GatewayTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        tmp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(tmp.cleanup)
        root = Path(tmp.name)
        home = root / "account" / ".hermes"
        home.mkdir(parents=True)
        peer = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Model)
        cls.peer = peer
        peer.subscriptions = []
        peer.tool_results = []
        peer.unused_path = root / "absent-approval-fixture"
        threading.Thread(target=peer.serve_forever, daemon=True).start()
        cls.addClassCleanup(peer.server_close)
        cls.addClassCleanup(peer.shutdown)
        base = f"http://127.0.0.1:{peer.server_port}/v1"
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        cls.url = f"http://127.0.0.1:{port}"
        cls.keys = {name: secrets.token_hex(32) for name in ("default", "work")}
        for profile, key in cls.keys.items():
            phome = home if profile == "default" else home / "profiles" / profile
            phome.mkdir(parents=True, exist_ok=True)
            config = {
                "_config_version": 54,
                "plugins": {"enabled": ["bellpull"]},
                "model": {
                    "provider": "custom",
                    "default": "fixture",
                    "base_url": base,
                    "context_length": 64000,
                },
                "gateway": {
                    "multiplex_profiles": True,
                    "platforms": {
                        "api_server": {
                            "enabled": True,
                            "host": "127.0.0.1",
                            "port": port,
                        }
                    },
                },
                "auxiliary": {"title_generation": {"enabled": False}},
                "approvals": {"mode": "manual", "timeout": 6},
                "terminal": {"cwd": str(root)},
                "platform_toolsets": {"api_server": ["terminal"]},
            }
            (phome / "config.yaml").write_text(json.dumps(config))
            (phome / ".env").write_text(
                f"API_SERVER_KEY={key}\nOPENAI_API_KEY=loopback-only\nOPENAI_BASE_URL={base}\nBELLPULL_NTFY_URL={base.removesuffix('/v1')}\n"
            )
        env = {
            k: os.environ[k]
            for k in ("PATH", "LANG", "PYTHONPATH", "SYSTEMROOT", "LD_LIBRARY_PATH")
            if k in os.environ
        }
        env.update(
            HOME=str(home.parent),
            USERPROFILE=str(home.parent),
            HERMES_HOME=str(home),
            API_SERVER_KEY=cls.keys["default"],
            GATEWAY_MULTIPLEX_PROFILES="true",
            API_SERVER_ENABLED="true",
            API_SERVER_HOST="127.0.0.1",
            API_SERVER_PORT=str(port),
            OPENAI_BASE_URL=base,
            OPENAI_API_KEY="loopback-only",
            HERMES_DISABLE_LAZY_INSTALLS="1",
            BELLPULL_NTFY_URL=base.removesuffix("/v1"),
            NO_PROXY="*",
        )
        cls.log_path = root / "gateway.log"
        log = cls.log_path.open("w")
        cls.addClassCleanup(log.close)
        proc = subprocess.Popen(
            [sys.executable, "-m", "gateway.run"],
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
        )

        def stop():
            proc.terminate()
            try:
                proc.wait(20)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()

        cls.addClassCleanup(stop)
        for _ in range(180):
            if proc.poll() is not None:
                raise RuntimeError("Disposable gateway exited")
            try:
                if cls.http("/health")[0] == 200:
                    break
            except OSError:
                pass
            time.sleep(0.5)
        else:
            raise RuntimeError("Disposable gateway readiness timeout")

    @classmethod
    def http(cls, path, body=None, *, key="default", headers=None):
        headers = dict(headers or {})
        if key:
            headers["Authorization"] = "Bearer " + cls.keys[key]
        if body is not None:
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(
            cls.url + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers=headers,
        )
        try:
            with urllib.request.urlopen(req, timeout=10) as res:
                return res.status, json.load(res)
        except urllib.error.HTTPError as exc:
            with exc:
                data = exc.read().decode(errors="replace")
                try:
                    return exc.code, json.loads(data)
                except ValueError:
                    return exc.code, {"message": data[:300]}

    def poll(self, fn, predicate):
        for _ in range(100):
            value = fn()
            if predicate(value):
                return value
            time.sleep(0.25)
        self.fail(f"Timed out waiting for disposable gateway response: {value}")

    def test_profile_specific_projects_and_offline_import(self):
        path = "/p/work/api/bellpull"
        status, response = self.http(path + "/capabilities", key="default")
        self.assertEqual(401, status, response)
        self.assertEqual(200, self.http(path + "/capabilities", key="work")[0])
        session = "bp-profile-local"
        status, value = self.http("/p/work/api/sessions", {"id": session}, key="work")
        self.assertIn(status, (200, 201), value)
        project = "a" * 32
        for i, fields in enumerate(
            [
                dict(operation="create", project_id=project, name="Private"),
                dict(operation="move", session_id=session, project_id=project),
                dict(operation="pin", session_id=session, pinned=True),
            ]
        ):
            status, snap = self.http(
                path + "/projects",
                {
                    **fields,
                    "operation_id": secrets.token_hex(16),
                    "expected_revision": i,
                },
                key="work",
            )
            self.assertEqual(status, 200, snap)
        self.assertTrue(snap["memberships"][0]["pinned"])
        self.assertEqual([], self.http("/api/bellpull/projects")[1]["projects"])
        transfer = {
            "operation_id": "b" * 32,
            "source_id": session,
            "messages": [{"role": "user", "content": "Offline fixture"}],
        }
        self.assertEqual(404, self.http("/api/bellpull/offline/handoff", transfer)[0])
        first = self.http(path + "/offline/handoff", transfer, key="work")
        self.assertEqual(200, first[0], first)
        self.assertTrue(first[1]["created"])
        self.assertFalse(
            self.http(path + "/offline/handoff", transfer, key="work")[1]["created"]
        )

        fork = self.http(
            f"/api/sessions/{session}/fork-at-message", {"through_message_id": 1}
        )
        self.assertEqual(404, fork[0])
        file_path = "/api/bellpull/artifacts/" + "f" * 32
        upload = urllib.request.Request(
            self.url
            + "/p/work"
            + file_path
            + "?session_id="
            + session
            + "&name=profile.txt",
            method="PUT",
            data=b"work fixture",
            headers={
                "Authorization": "Bearer " + self.keys["work"],
                "Content-Type": "text/plain",
            },
        )
        with urllib.request.urlopen(upload, timeout=5) as r:
            self.assertEqual(201, r.status)
        self.assertEqual(404, self.http(file_path + "/content")[0])
        self.assertEqual([], self.http("/api/bellpull/artifacts")[1]["data"])
        ack = self.http(
            "/p/work/v1/runs",
            {"session_id": session, "input": "ordinary fixture"},
            key="work",
        )
        self.assertEqual(202, ack[0], ack)
        run_path = "/v1/runs/" + ack[1]["run_id"]
        self.assertEqual(404, self.http(run_path)[0])
        self.assertEqual(
            404,
            self.http(
                run_path + "/approval",
                {"request_id": "foreign", "execution_generation": 1, "choice": "once"},
            )[0],
        )
        self.assertEqual(
            "completed",
            self.poll(
                lambda: self.http("/p/work" + run_path, key="work")[1],
                lambda v: v.get("status") in ("completed", "failed"),
            )["status"],
        )

    def test_approval_and_completion_have_durable_receipts(self):
        status, ack = self.http(
            "/v1/runs",
            {"session_id": "bp-approval", "input": "APPROVAL_FIXTURE"},
            headers={"Idempotency-Key": "bp-approval-1"},
        )
        self.assertEqual(202, status, ack)
        run = ack["run_id"]
        path = "/v1/runs/" + run
        view = self.poll(
            lambda: self.http(path)[1],
            lambda item: (
                item.get("status") in ("waiting_for_approval", "failed", "completed")
            ),
        )
        self.assertEqual("waiting_for_approval", view["status"], view)
        prompt = next(x for x in view["pending_controls"] if x["kind"] == "approval")

        def events():
            return [
                x
                for x in self.http("/api/bellpull/notifications/history")[1]["events"]
                if x.get("run_id") == run
            ]

        self.poll(
            events,
            lambda rows: any(x["status"] == "waiting_for_approval" for x in rows),
        )
        decision = {
            "request_id": prompt["prompt_id"],
            "execution_generation": prompt["execution_generation"],
            "choice": "once",
        }
        self.assertEqual(
            409,
            self.http(
                path + "/approval",
                {
                    **decision,
                    "execution_generation": decision["execution_generation"] + 1,
                },
            )[0],
        )
        self.assertEqual(200, self.http(path + "/approval", decision)[0])
        self.assertEqual(
            "completed",
            self.poll(
                lambda: self.http(path)[1],
                lambda v: v["status"] in ("completed", "failed"),
            )["status"],
        )
        self.assertEqual(409, self.http(path + "/approval", decision)[0])
        rows = self.poll(events, lambda r: any(x["status"] == "completed" for x in r))
        self.assertEqual(1, sum(x["status"] == "completed" for x in rows))

    def test_authenticated_notification_stream_uses_only_public_fields(self):
        before = len(self.peer.subscriptions)
        self.assertEqual(401, self.http("/api/bellpull/events", key=None)[0])
        self.assertEqual(before, len(self.peer.subscriptions))
        request = urllib.request.Request(
            self.url + "/api/bellpull/events",
            headers={"Authorization": "Bearer " + self.keys["default"]},
        )
        with urllib.request.urlopen(request, timeout=10) as response:
            frames = response.read().decode().splitlines()
        self.assertEqual(before + 1, len(self.peer.subscriptions))
        self.assertRegex(self.peer.subscriptions[-1], r"^[-_a-zA-Z0-9]{1,64}$")
        self.assertIn("event: bellpull.wake", frames)
        hint = json.loads(
            next(line[6:] for line in frames if line.startswith("data: "))
        )
        self.assertEqual(
            {
                "run_id": "local-event",
                "session_id": "local-chat",
                "status": "completed",
            },
            hint,
        )

    def test_two_pending_runs_have_independent_approval_decisions(self):
        pending = []
        for session in ("alpha-prompt", "beta-prompt"):
            status, ack = self.http(
                "/v1/runs",
                {
                    "input": "APPROVAL_FIXTURE",
                    "session_id": session,
                    "interactive_approvals": True,
                },
            )
            self.assertEqual(202, status, ack)
            path = "/v1/runs/" + ack["run_id"]
            view = self.poll(
                lambda: self.http(path)[1],
                lambda r: r.get("status") == "waiting_for_approval",
            )
            prompt = next(
                p for p in view["pending_controls"] if p["kind"] == "approval"
            )
            pending.append(
                (
                    path,
                    {
                        "request_id": prompt["prompt_id"],
                        "execution_generation": prompt["execution_generation"],
                        "choice": "deny",
                    },
                )
            )
        first, other = pending
        self.assertEqual(409, self.http(first[0] + "/approval", other[1])[0])
        for path, decision in pending:
            self.assertEqual(200, self.http(path + "/approval", decision)[0])
            self.assertEqual(
                "completed",
                self.poll(
                    lambda: self.http(path)[1],
                    lambda v: v["status"] in ("completed", "failed"),
                )["status"],
            )
            self.assertEqual(409, self.http(path + "/approval", decision)[0])

    def test_unanswered_approval_expires_and_next_run_has_no_pending_control(self):
        before = len(self.peer.tool_results)
        ack = self.http(
            "/v1/runs",
            {
                "session_id": "bp-expiry",
                "input": "APPROVAL_FIXTURE",
                "interactive_approvals": False,
            },
        )
        self.assertEqual(202, ack[0], ack)
        path = "/v1/runs/" + ack[1]["run_id"]
        finished = self.poll(
            lambda: self.http(path)[1],
            lambda r: r.get("status") in ("completed", "failed"),
        )
        self.assertEqual("completed", finished["status"])
        self.assertTrue(
            any(
                "denied" in result.lower() or "timed out" in result.lower()
                for result in self.peer.tool_results[before:]
            ),
            self.peer.tool_results[before:],
        )
        follow = self.http(
            "/v1/runs", {"session_id": "bp-expiry", "input": "ordinary fixture"}
        )
        self.assertEqual(202, follow[0], follow)
        view = self.poll(
            lambda: self.http("/v1/runs/" + follow[1]["run_id"])[1],
            lambda r: r.get("status") in ("completed", "failed"),
        )
        self.assertEqual("completed", view["status"])
        self.assertFalse(view.get("pending_controls"))
