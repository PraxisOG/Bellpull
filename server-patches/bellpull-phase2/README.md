# Bellpull Phase 2 gateway extension — 2026-10-09

Built image: `hermes-agent:bellpull-phase2-20261009`, extending the existing prompt-edit
fork image. Activated on `hermes-gateway` after recognized module hashes and idle health
checks; no model route, config.yaml, API key, ntfy config, or published port changes.

`interactive_approvals: true` on durable run admission enables the real per-run approval
context. It is an explicit capability-gated opt-in and part of the idempotency fingerprint.
Legacy/default unattended behavior remains unchanged. Client decisions use an exact
request ID, once/deny, and resolve_all:false.

Authenticated `GET /api/bellpull/events` proxies existing loopback ntfy at port 2586.
Scope-specific hints contain only run_id/session_id/status; canonical run status/history
remain the authority. Events may repeat or be missed. No inference/output or bearer keys
are published to ntfy. The phone keeps durable local recovery and notification identities.

Build from this folder:

```sh
docker build -t hermes-agent:bellpull-phase2-20261009 .
python3 activate.py check
python3 activate.py apply
# Roll back only after stopping/settling current work:
python3 activate.py rollback
```

Activation and rollback verify exact source hashes before copying and refuse to restart
an active gateway. Rollback restores the prompt-edit fork patch, not an unpatched image.
The copied live modules survive restart. Container recreation must use the new image;
the running container's base image identifier is still the previous deployment's image.
Do not reconstruct deployment configuration from secret-bearing container environment.

Eight isolated aiohttp/SQLite tests pass with the real approval gate and a harmless
fixture agent: once/deny, stale and repeated decisions, concurrent unattended requests,
opt-in fingerprint conflict, missing auth, completed-run restart, and interrupted owner.
All 16 existing selected-reply/prompt-edit fork regression tests also pass on this image.
No network/model/tool execution is allowed in those fixture containers.

```sh
docker run --rm --network none -e HERMES_HOME=/tmp/bellpull-phase2-tests \
  -v "$PWD/test_phase2.py:/tmp/test_phase2.py:ro" \
  --entrypoint /bin/bash hermes-agent:bellpull-phase2-20261009 \
  -c 'cd /opt/hermes && PYTHONPATH=/opt/hermes python /tmp/test_phase2.py'
```

Android contract and real-device results: `/home/praxisog/Hermes-App-Project/docs/HANDOFF.md`
and `docs/hermes-api.md`. Durable SSE is a consuming queue, not a replay log. Broker
outage recovery uses canonical synchronization; a gateway crash cannot resume interrupted
tool execution and must not submit it again automatically.

The on-device locked-screen check caught ntfy rejecting a 73-character hex topic.
The relay now uses the same full SHA-256 digest encoded as unpadded URL-safe base64
(52 characters including the prefix), within ntfy's 64-character limit. Regression
fixtures enforce that limit and exercise the authenticated filtered event stream.
Source: https://docs.ntfy.sh/publish/#picking-a-topic
