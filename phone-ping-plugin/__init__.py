"""Hermes tool for Gate T2-0. Configure through PHONE_PING_NTFY_URL and topic env vars.

The gate carries only an echo string. T2-1 adds the separate bridge credential, capability
allowlist, audit log, and kill switch before any personal data capability exists.
"""

from __future__ import annotations

import json
import os
import socket
import urllib.error
import urllib.parse
import urllib.request
import uuid


SCHEMA = {
    "name": "phone_ping",
    "description": "Check whether the user's phone answers a reachability probe. Returns a scoped timeout if unavailable. Does not access phone data.",
    "parameters": {
        "type": "object",
        "properties": {"echo": {"type": "string", "description": "Short string to echo from the phone, at most 200 characters."}},
        "required": ["echo"],
        "additionalProperties": False,
    },
}


def _config() -> tuple[str, str, str]:
    base = os.environ.get("PHONE_PING_NTFY_URL", "").rstrip("/")
    request_topic = os.environ.get("PHONE_PING_REQUEST_TOPIC", "")
    reply_topic = os.environ.get("PHONE_PING_REPLY_TOPIC", "")
    if not base.startswith(("http://127.0.0.1:", "https://")):
        raise ValueError("PHONE_PING_NTFY_URL must be a loopback HTTP or HTTPS URL")
    if not all(topic and len(topic) <= 100 and all(c.isalnum() or c in "-_" for c in topic)
               for topic in (request_topic, reply_topic)):
        raise ValueError("PHONE_PING_REQUEST_TOPIC and PHONE_PING_REPLY_TOPIC must be set")
    return base, request_topic, reply_topic


def phone_ping(args: dict, **_kwargs) -> str:
    echo = args.get("echo", "")
    if not isinstance(echo, str) or len(echo) > 200:
        return json.dumps({"ok": False, "error": "invalid_echo"})
    try:
        base, request_topic, reply_topic = _config()
    except ValueError as exc:
        return json.dumps({"ok": False, "error": "not_configured", "detail": str(exc)})

    request_id = uuid.uuid4().hex
    payload = json.dumps({"kind": "phone_ping", "id": request_id, "echo": echo})
    stream_url = f"{base}/{urllib.parse.quote(reply_topic)}/json?since=1s"
    publish_url = f"{base}/{urllib.parse.quote(request_topic)}"
    try:
        # Open the reply stream before publishing so a quick phone cannot outrun us.
        with urllib.request.urlopen(stream_url, timeout=25) as stream:
            publish = urllib.request.Request(
                publish_url, data=payload.encode("utf-8"),
                headers={"Content-Type": "text/plain; charset=utf-8"}, method="POST",
            )
            with urllib.request.urlopen(publish, timeout=10):
                pass
            while True:
                line = stream.readline()
                if not line:
                    break
                event = json.loads(line)
                if event.get("event") != "message":
                    continue
                try:
                    answer = json.loads(event.get("message", ""))
                except (ValueError, TypeError):
                    continue
                if answer.get("kind") == "phone_pong" and answer.get("id") == request_id:
                    return json.dumps({"ok": True, "echo": answer.get("echo"),
                                       "received_at_ms": answer.get("received_at_ms")})
    except (TimeoutError, socket.timeout):
        return json.dumps({"ok": False, "error": "phone_unreachable", "detail": "No reply within 25 seconds"})
    except (OSError, urllib.error.URLError) as exc:
        return json.dumps({"ok": False, "error": "transport_error", "detail": type(exc).__name__})
    return json.dumps({"ok": False, "error": "phone_unreachable", "detail": "Reply stream closed"})


def register(ctx) -> None:
    ctx.register_tool(name="phone_ping", toolset="phone_ping_gate", schema=SCHEMA,
                      handler=phone_ping)
