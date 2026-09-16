#!/usr/bin/env python3
"""Replay server for the Gate 0 Hermes fixtures.

No auth, no persistence, no fixture beyond what's recorded in fixtures/ — this exists so
tests and manual pokes don't need a live Hermes instance. See fixtures/README.md for how
those were captured and sanitized.
"""

import http.server
import json
import os
import re
import socketserver
from pathlib import Path

FIXTURES_DIR = Path(__file__).parent / "fixtures"
SESSION_LIST = (FIXTURES_DIR / "session-list.json").read_text()
SESSION_MESSAGES = (FIXTURES_DIR / "session-messages.json").read_text()
SESSION_STREAM = (FIXTURES_DIR / "session-chat-stream.sse").read_text()

FIXTURE_SESSION_ID = json.loads(SESSION_LIST)["data"][0]["id"]

MESSAGES_PATH = re.compile(r"^/api/sessions/([^/]+)/messages$")
STREAM_PATH = re.compile(r"^/api/sessions/([^/]+)/chat/stream$")


class Handler(http.server.BaseHTTPRequestHandler):
    def _respond(self, status: int, content_type: str, body: str) -> None:
        payload = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _json(self, status: int, body: str) -> None:
        self._respond(status, "application/json", body)

    def do_GET(self) -> None:
        if self.path == "/api/sessions":
            self._json(200, SESSION_LIST)
            return
        match = MESSAGES_PATH.match(self.path)
        if match:
            if match.group(1) == FIXTURE_SESSION_ID:
                self._json(200, SESSION_MESSAGES)
            else:
                self._json(404, json.dumps({"error": "no fixture for this session id"}))
            return
        self._json(404, json.dumps({"error": "no fixture for this route"}))

    def do_POST(self) -> None:
        match = STREAM_PATH.match(self.path)
        if match and match.group(1) == FIXTURE_SESSION_ID:
            length = int(self.headers.get("Content-Length", 0))
            self.rfile.read(length)  # request body is unused; fixture reply is fixed
            self._respond(200, "text/event-stream", SESSION_STREAM)
            return
        self._json(404, json.dumps({"error": "no fixture for this route"}))

    def log_message(self, format_str: str, *args) -> None:
        print(f"[mock-hermes] {self.address_string()} {format_str % args}")


def main() -> None:
    port = int(os.environ.get("MOCK_HERMES_PORT", "8643"))
    with socketserver.TCPServer(("127.0.0.1", port), Handler) as httpd:
        print(f"[mock-hermes] serving fixtures on http://127.0.0.1:{port}")
        httpd.serve_forever()


if __name__ == "__main__":
    main()
