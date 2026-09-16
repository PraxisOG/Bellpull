# Hermes API contract

**Source:** Hermes Agent official docs (API Server, Programmatic Integration, Web Dashboard),
read 2026-09-16. **Partially verified against installed Hermes 0.21.1 on 2026-09-16.**
The capabilities response and session list/history endpoints are confirmed; verify remaining
operations before implementing them. Claude Code must not use any endpoint not listed here.

## Decision: use the API server, not the dashboard, not the TUI gateway

Hermes exposes three programmatic surfaces. Tier 1 uses exactly one.

| Surface | Transport | Verdict |
|---|---|---|
| **API server** (`gateway/platforms/api_server.py`) | HTTP + SSE, static bearer key | **Use this.** |
| Dashboard (`hermes dashboard`, :9119) | HTTP, session cookies + WS tickets | Avoid — see below |
| TUI gateway | JSON-RPC over stdio / WebSocket | Avoid — see below |

**Why not the dashboard.** It has an overlapping `/api/sessions/*` surface, but its auth is
built for a browser: session cookies, a 15-minute OAuth access token with *no refresh token*
in contract v1, single-use tickets for WebSockets, a DNS-rebinding Host guard, and CORS
restricted to localhost origins. It also needs the `[web,pty]` extras and Node. A native
client against that is a re-login treadmill.

**Why not the TUI gateway.** It's the richest surface (`session.list`, `session.history`,
`session.branch`, slash commands), but it's JSON-RPC over stdio/WebSocket built for the Ink
TUI, and the agent sends *server→client requests* (approval, clarify, sudo, secret, vault)
that a host must answer or explicitly refuse with `-32601` or the agent hangs waiting out a
timeout. That's Tier 2+ work if we ever want slash commands.

## Setup on the Hermes host

`~/.hermes/.env`:

```bash
API_SERVER_ENABLED=true
API_SERVER_KEY=<long random>
API_SERVER_HOST=<tailscale IP>   # default 127.0.0.1 — must change or the phone can't reach it
API_SERVER_PORT=8642
```

Then `hermes gateway`. It logs `[API Server] API server listening on http://…:8642`.

Auth is `Authorization: Bearer $API_SERVER_KEY` on every endpoint. Same key for `/v1/*` and
`/api/*`. No CORS needed (native client, not a browser).

> **This key is root-equivalent on the Hermes host.** The docs are explicit that the API
> server exposes the full toolset *including terminal commands*. Bind to Tailscale only,
> never a public interface. Store the key in EncryptedSharedPreferences, never in a build
> file or a committed env. Have a rotation path for a lost phone.

## Verify before building

```bash
curl -s -H "Authorization: Bearer $KEY" http://$HOST:8642/v1/capabilities | jq
```

`/v1/capabilities` reports `auth`, and feature flags including `run_submission`,
`run_events_sse`, `run_stop`, `run_approval`, and the `session_*` flags plus
`endpoints.session_*` entries. **That response is the real contract for our installed
version — where it disagrees with this file, it wins.** Update this file, don't work around it.

## Endpoints Tier 1 uses

All under `Authorization: Bearer $API_SERVER_KEY`.

### Sessions

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/sessions` | List. Params: `limit`, `offset`, `source`, `include_children` |
| `POST` | `/api/sessions` | Create an empty session |
| `GET` | `/api/sessions/{id}` | Metadata |
| `PATCH` | `/api/sessions/{id}` | Update `title` or `end_reason` |
| `DELETE` | `/api/sessions/{id}` | Delete session + history |
| `GET` | `/api/sessions/{id}/messages` | History |
| `POST` | `/api/sessions/{id}/fork` | Branch (CLI `/branch` semantics) |
| `POST` | `/api/sessions/{id}/chat` | One synchronous turn |
| `POST` | `/api/sessions/{id}/chat/stream` | SSE turn — see events below |

`include_children=false` and a `source` filter are what keep cron, tool, API, and subagent
sessions out of the human chat list. The dashboard's own Chats/Automation split works the
same way. Get this right in slice 2 or the session list is mostly noise.

### Streaming

`POST /api/sessions/{id}/chat/stream` emits SSE events:

The installed 0.21.1 server requires a JSON body containing a nonempty `message` field.
Its source also accepts `input`, but the phone will use `message`. This body has been
verified in the installed source and accepted by a live SSE request:

```json
{"message":"Fixture stream ready."}
```

```
run.started         turn accepted
message.started     assistant message opened
assistant.delta     token text
tool.progress       tool/reasoning progress (observed in the fixture)
tool.started        tool call begins
tool.completed      tool call ends
assistant.completed assistant content and completion flags
run.completed       successful terminal event with usage
done                stream closed
```

In the installed 0.21.1 stream, `assistant.completed` carries `completed` / `partial` /
`interrupted` flags; `run.completed` carries `completed` and usage. Render the distinction
— a truncated or interrupted turn must not look like a finished one.
The provider-404 attempt still produced `run.completed` with zero usage and no persisted
assistant message, so the client must also recognize an empty or failed inference.

### Run control (applies to session/chat/stream runs too)

| Method | Path | Notes |
|---|---|---|
| `GET` | `/v1/runs/{run_id}` | Status without holding SSE open |
| `GET` | `/v1/runs/{run_id}/events` | SSE, attach/detach without losing state |
| `POST` | `/v1/runs/{run_id}/stop` | Settles as `cancelled` |
| `POST` | `/v1/runs/{run_id}/approval` | **Required — see below** |

### Discovery

`GET /v1/capabilities`, `GET /v1/skills`, `GET /v1/toolsets`, `GET /health`,
`GET /health/detailed`, `GET /api/model/options`.

## Things that will bite

- **Approvals are a Tier 1 requirement, not a nicety.** If Hermes' `approvals` config is
  `smart` or `manual`, a turn that hits a gated command *stalls waiting for a human*. If
  the phone can't render the prompt and POST to `/v1/runs/{id}/approval`, the chat just
  hangs with no error. Either implement the approval prompt or knowingly run
  `approvals: off` on this profile and write down that you chose that.
- **`Idempotency-Key` header on `/v1/runs`** makes a run submission safely retryable — an
  identical retry returns the original `run_id` with `Idempotency-Replayed: true`, surviving
  gateway restarts, retained 24h. Dedupe for `/api/sessions/{id}/chat/stream` is not verified;
  do not assume this header makes that endpoint safely retryable.
- **`max_concurrent_runs` defaults to 10**; over the cap returns `429`. Back off, don't spin.
- **`X-Hermes-Session-Key`** scopes long-term memory independently of the transcript
  session id. Max 256 chars. Set one stable value for the phone.
- **Multi-profile** (`gateway.multiplex_profiles`) serves profiles under `/p/<profile>/`
  with *per-profile keys* — the default key is rejected on named prefixes. Not needed now;
  don't get surprised later.
- Unconsumed SSE event buffers expire after 5 minutes on a detached client. Reattach via
  `/v1/runs/{id}/events` or poll `/v1/runs/{id}`.

## Gate 0 status (2026-09-16)

- Installed Hermes 0.21.1 returned HTTP 200 for `/v1/capabilities`, `/api/sessions`, and
  `/api/sessions/{id}/messages`. Sanitized list and history responses are in
  `mock-hermes/fixtures/`.
- `POST /api/sessions` initially returned HTTP 500 because live gateway/dashboard
  processes held a deleted SQLite database and WAL while a new empty database existed
  at the same path. The old database (21 sessions, 232 messages) was recovered from
  private snapshots, integrity-checked, and restored during a controlled stop. After
  restart, session listing and creation returned HTTP 200 and 201 respectively.
- The first live streaming request returned SSE frames but no usable assistant reply:
  Fireworks rejected the then-configured `anthropic/claude-opus-4.6` with HTTP 404.
  The live config was changed to the previously used
  `accounts/fireworks/models/deepseek-v4p1-flash`. Hermes picked up the change without a
  restart. A fresh turn returned a nonempty assistant reply, four `assistant.delta` events,
  and a completed terminal event; its sanitized stream is in `mock-hermes/fixtures/`.
