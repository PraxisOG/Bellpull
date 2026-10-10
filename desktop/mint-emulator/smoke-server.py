#!/usr/bin/env python3
"""Local desktop smoke fixture, not an inference server or full server-contract emulator.

Adds synthetic durable admission/status/events/history to the recorded HTTP fixture.
Only binds host loopback. Fixed public test key: mock-hermes-test-key.
Never package this file or its runtime data in the desktop release.
"""
import importlib.util
import json
from pathlib import Path
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("fixtures", ROOT / "mock-hermes/server.py")
fixtures = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fixtures)
REPLY = "Bellpull is running on Linux Mint. This reply streamed from the local desktop test fixture."
runs = {}
lock = threading.Lock()


def completed(run):
    return time.time() >= run["started"] + 8


class Handler(fixtures.Handler):
    def do_POST(self):
        if self.path != "/v1/runs":
            return super().do_POST()
        if not self._authorized():
            return
        body = json.loads(self._read_body())
        key = self.headers.get("Idempotency-Key")
        if not key or not body.get("input") or body.get("session_id") != fixtures.FIXTURE_SESSION_ID:
            return self._json(400, {"error": {"message": "Smoke fixture requires a keyed run in fixture-id-1."}})
        with lock:
            if key in runs and runs[key]["body"] != body:
                return self._json(409, {"error": {"code": "idempotency_key_conflict", "message": "Different payload."}})
            replayed = key in runs
            if not replayed:
                runs[key] = {"body": body, "started": time.time(), "id": f"desktop-smoke-{len(runs)+1}"}
            run = runs[key]
        self._json(200, {"run_id": run["id"], "status": "completed" if completed(run) else "running", "replayed": replayed})

    def do_GET(self):
        path = self.path.split("?")[0]
        run = next((run for run in list(runs.values()) if path.startswith(f"/v1/runs/{run['id']}")), None)
        if run:
            if not self._authorized():
                return
            if path.endswith("/events"):
                return self.stream(run)
            return self._json(200, {"run_id": run["id"], "session_id": fixtures.FIXTURE_SESSION_ID,
                "status": "completed" if completed(run) else "running", "created_at": run["started"],
                "output": REPLY if completed(run) else None})
        if path == f"/api/sessions/{fixtures.FIXTURE_SESSION_ID}/messages":
            if not self._authorized():
                return
            history = json.loads(fixtures.SESSION_MESSAGES)
            for run in list(runs.values()):
                if completed(run):
                    for role, content in (("user", run["body"]["input"]), ("assistant", REPLY)):
                        history["data"].append({"id": len(history["data"]) + 1,
                            "session_id": fixtures.FIXTURE_SESSION_ID, "role": role, "content": content,
                            "timestamp": run["started"] + 8})
            history["pagination"]["returned"] = len(history["data"])
            return self._json(200, history)
        super().do_GET()

    def stream(self, run):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        words = REPLY.split(" ")
        for index, word in enumerate(words):
            payload = {"run_id": run["id"], "session_id": fixtures.FIXTURE_SESSION_ID,
                "message_id": "desktop-reply", "seq": index + 1,
                "delta": word + (" " if index < len(words)-1 else "")}
            self._write_chunk(f"event: assistant.delta\ndata: {json.dumps(payload)}\n\n".encode())
            time.sleep(0.3)
        self._write_chunk(b'event: done\ndata: {}\n\n')
        self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()


if __name__ == "__main__":
    with fixtures.Server(("127.0.0.1", 18643), Handler) as server:
        print("Desktop smoke fixture: http://127.0.0.1:18643 (mock-hermes-test-key)", flush=True)
        server.serve_forever()
