#!/usr/bin/env python3
"""Replay server for the recorded Hermes fixtures.

No live Hermes, no inference, no persistence. Everything it serves comes from
``fixtures/``, and every error shape it returns was captured from the real 0.21.1 API
(see ``fixtures/error-responses.json`` for provenance — one shape is source-derived and
labelled as such).

Auth: every route requires ``Authorization: Bearer $MOCK_HERMES_KEY``, because a client
that is never tested against a 401 will ship without handling one.

Environment:
  MOCK_HERMES_PORT       listen port (default 8643)
  MOCK_HERMES_KEY        expected bearer key (default "mock-hermes-test-key")
  MOCK_HERMES_SSE_DELAY  seconds between SSE events (default 0.0) -- set it to make the
                         stream arrive incrementally instead of all at once
  MOCK_HERMES_FAIL       default fault for every request (see FAULTS)

Fault injection is **not part of the Hermes API**. It is a mock-only control surface, so
tests can reach error paths without a broken server. Per request, send the header
``X-Mock-Fail: <fault>``; it overrides MOCK_HERMES_FAIL. Supported faults:

  auth          -> 401 gateway_auth_failed, even with a correct key
  rate-limit    -> 429 rate_limit_exceeded with Retry-After: 1
  stream-abort  -> stream a few real events, then drop the connection with no terminal
                   event (the "dropped mid-stream" case: a truncated turn must not look
                   finished)
  slow-headers  -> stall before the response headers, to exercise connect/read timeouts
  redirect      -> 302 to another host, which a client must refuse rather than follow (the
                   bearer key is root-equivalent; it must not be carried to a redirect target)
  run-not-active -> 409 run_not_active on a stop, the registration race a client hits when it
                   stops too early (see docs/hermes-api.md Gate 0d)
"""

import http.server
import json
import os
import re
import socketserver
import sys
import time
from pathlib import Path
from typing import Optional

FIXTURES = Path(__file__).parent / "fixtures"


def _load(name: str) -> str:
    return (FIXTURES / name).read_text()


SESSION_LIST = _load("session-list.json")
SESSION_MESSAGES = _load("session-messages.json")
SESSION_CREATE = _load("session-create.json")
CAPABILITIES = _load("capabilities.json")
SESSION_STREAM = _load("session-chat-stream.sse")
RUN_STATUS = _load("run-status.json")
RUN_STOP = _load("run-stop.json")
ERRORS = json.loads(_load("error-responses.json"))

FIXTURE_SESSION_ID = json.loads(SESSION_LIST)["data"][0]["id"]
FIXTURE_RUN_ID = json.loads(RUN_STATUS)["run_id"]

MESSAGES_PATH = re.compile(r"^/api/sessions/([^/?]+)/messages$")
STREAM_PATH = re.compile(r"^/api/sessions/([^/?]+)/chat/stream$")
SESSION_PATH = re.compile(r"^/api/sessions/([^/?]+)$")
RUN_PATH = re.compile(r"^/v1/runs/([^/?]+)$")
RUN_STOP_PATH = re.compile(r"^/v1/runs/([^/?]+)/stop$")

EXPECTED_KEY = os.environ.get("MOCK_HERMES_KEY", "mock-hermes-test-key")
SSE_DELAY = float(os.environ.get("MOCK_HERMES_SSE_DELAY", "0"))
DEFAULT_FAULT = os.environ.get("MOCK_HERMES_FAIL", "")
FAULTS = ("auth", "rate-limit", "stream-abort", "slow-headers", "redirect", "run-not-active")


def _sse_events(raw: str) -> list:
    """Split a recorded SSE body into individual ``event:``/``data:`` blocks, so the
    stream can be replayed one event at a time instead of as one blob."""
    return [b + "\n\n" for b in raw.strip().split("\n\n") if b.strip()]


STREAM_EVENTS = _sse_events(SESSION_STREAM)


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "mock-hermes"
    sys_version = ""

    # ---- plumbing -------------------------------------------------------------

    def _send(self, status: int, content_type: str, body: bytes, headers: Optional[dict] = None) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _json(self, status: int, body, headers: Optional[dict] = None) -> None:
        raw = body if isinstance(body, str) else json.dumps(body)
        self._send(status, "application/json", raw.encode("utf-8"), headers)

    def _error(self, name: str, **fmt) -> None:
        """Reply with one of the recorded real error shapes."""
        spec = ERRORS[name]
        if "body_text" in spec:
            self._send(spec["status"], spec.get("content_type", "text/plain"),
                       spec["body_text"].encode("utf-8"))
            return
        body = json.loads(json.dumps(spec["body"]))
        if fmt:
            body["error"]["message"] = body["error"]["message"].format(**fmt)
        self._json(spec["status"], body, spec.get("headers"))

    def _fault(self) -> str:
        fault = self.headers.get("X-Mock-Fail", DEFAULT_FAULT).strip()
        if fault and fault not in FAULTS:
            self._json(400, {"error": {"message": f"unknown X-Mock-Fail: {fault!r}; "
                                                  f"expected one of {', '.join(FAULTS)}",
                                       "type": "mock_hermes_error"}})
            return "handled"
        return fault

    def _authorized(self) -> bool:
        """True when the request carries the expected bearer key. Mirrors the real
        server: a missing header, a wrong key and a non-Bearer scheme are all one 401."""
        header = self.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            return False
        return header[len("Bearer "):].strip() == EXPECTED_KEY

    def _guard(self) -> Optional[str]:
        """Shared per-request gate. Returns None when the handler should stop."""
        fault = self._fault()
        if fault == "handled":
            return None
        if fault == "slow-headers":
            time.sleep(30)  # client should time out first
        if fault == "auth" or not self._authorized():
            self._error("auth_failed")
            return None
        if fault == "rate-limit":
            self._error("rate_limited")
            return None
        if fault == "redirect":
            self._send(302, "text/plain", b"", {"Location": "http://example.invalid/moved"})
            return None
        return fault or ""

    # ---- routes ---------------------------------------------------------------

    def do_GET(self) -> None:
        fault = self._guard()
        if fault is None:
            return
        if self.path == "/v1/capabilities":
            self._json(200, CAPABILITIES)
            return
        if self.path.split("?")[0] == "/api/sessions":
            self._json(200, SESSION_LIST)
            return
        match = MESSAGES_PATH.match(self.path)
        if match:
            if match.group(1) == FIXTURE_SESSION_ID:
                self._json(200, SESSION_MESSAGES)
            else:
                self._error("session_not_found", id=match.group(1))
            return
        match = RUN_PATH.match(self.path)
        if match:
            # Echo whatever run id was asked for: a real client learns its run_id from the
            # stream, so pinning one fixture id would make the stop path untestable.
            body = json.loads(RUN_STATUS)
            body["run_id"] = match.group(1)
            self._json(200, json.dumps(body))
            return
        match = SESSION_PATH.match(self.path)
        if match:
            # Metadata for a single session is not a recorded fixture: GET /api/sessions/{id}
            # is still unverified against the live server (docs/hermes-api.md). Refusing here
            # is deliberate -- inventing a body would be worse than a blocker.
            self._error("session_not_found", id=match.group(1))
            return
        self._error("unknown_path")

    def do_POST(self) -> None:
        fault = self._guard()
        if fault is None:
            return
        if self.path.split("?")[0] == "/api/sessions":
            self._read_body()
            self._json(201, SESSION_CREATE)
            return
        match = RUN_STOP_PATH.match(self.path)
        if match:
            self._read_body()
            if fault == "run-not-active":
                self._error("run_not_active", run_id=match.group(1))
            else:
                body = json.loads(RUN_STOP)
                body["run_id"] = match.group(1)
                self._json(200, json.dumps(body))
            return
        match = STREAM_PATH.match(self.path)
        if match:
            self._chat_stream(match.group(1), fault)
            return
        self._error("unknown_path")

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    def _chat_stream(self, session_id: str, fault: str) -> None:
        raw = self._read_body()
        if session_id != FIXTURE_SESSION_ID:
            self._error("session_not_found", id=session_id)
            return
        # Body validation matches the real server's observed behaviour: invalid JSON and a
        # missing/empty `message` are two different 400s.
        try:
            body = json.loads(raw or b"{}")
        except ValueError:
            self._error("invalid_json")
            return
        if not str(body.get("message") or "").strip():
            self._error("missing_message")
            return

        # Chunked, so events arrive incrementally instead of as one Content-Length blob.
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        abort_after = 3 if fault == "stream-abort" else None
        for n, event in enumerate(STREAM_EVENTS, 1):
            if abort_after is not None and n > abort_after:
                # Hang up mid-stream: no terminal event, no chunked terminator.
                self.close_connection = True
                self.log_message("stream-abort: dropped after %d/%d events", abort_after, len(STREAM_EVENTS))
                return
            self._write_chunk(event.encode("utf-8"))
            if SSE_DELAY:
                time.sleep(SSE_DELAY)
        self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()

    def _write_chunk(self, payload: bytes) -> None:
        self.wfile.write(f"{len(payload):X}\r\n".encode("ascii") + payload + b"\r\n")
        self.wfile.flush()

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write(f"[mock-hermes] {self.address_string()} {fmt % args}\n")


class Server(socketserver.ThreadingTCPServer):
    """Threaded: a stalled stream must not block the next request, and the 429 path is
    only meaningful with concurrency."""
    allow_reuse_address = True
    daemon_threads = True


def main() -> None:
    port = int(os.environ.get("MOCK_HERMES_PORT", "8643"))
    with Server(("127.0.0.1", port), Handler) as httpd:
        print(f"[mock-hermes] fixtures on http://127.0.0.1:{port}")
        print(f"[mock-hermes] bearer key: {EXPECTED_KEY}")
        print(f"[mock-hermes] sse delay: {SSE_DELAY}s | default fault: {DEFAULT_FAULT or 'none'}")
        print(f"[mock-hermes] fixture session: {FIXTURE_SESSION_ID} | {len(STREAM_EVENTS)} stream events")
        httpd.serve_forever()


if __name__ == "__main__":
    main()
