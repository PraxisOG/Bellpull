# Hermes API contract

**Source:** Hermes Agent official docs (API Server, Programmatic Integration, Web Dashboard),
read 2026-09-16. **Not yet verified against our installed version.** Verify first (below),
then mark each endpoint as confirmed. Claude Code must not use any endpoint not listed here.

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

```
assistant.delta     token text
tool.started        tool call begins
tool.completed      tool call ends
run.completed       terminal — also run.failed / run.cancelled
```

The terminal payload carries real `completed` / `partial` / `interrupted` flags. A run is
never `completed` with `completed: false`. Render the distinction — a truncated or
interrupted turn must not look like a finished one.

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
- **`Idempotency-Key` header on `/v1/runs`** makes a send safely retryable — an identical
  retry returns the original `run_id` with `Idempotency-Replayed: true`, surviving gateway
  restarts, retained 24h. Use it for every send from the phone. It's the flaky-network
  resend problem already solved server-side.
- **`max_concurrent_runs` defaults to 10**; over the cap returns `429`. Back off, don't spin.
- **`X-Hermes-Session-Key`** scopes long-term memory independently of the transcript
  session id. Max 256 chars. Set one stable value for the phone.
- **Multi-profile** (`gateway.multiplex_profiles`) serves profiles under `/p/<profile>/`
  with *per-profile keys* — the default key is rejected on named prefixes. Not needed now;
  don't get surprised later.
- Unconsumed SSE event buffers expire after 5 minutes on a detached client. Reattach via
  `/v1/runs/{id}/events` or poll `/v1/runs/{id}`.
