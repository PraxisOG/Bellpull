# Hermes API contract

> **Naming:** this file documents **Hermes Agent**, NousResearch's self-hosted agent
> software (`github.com/NousResearch/hermes-agent`,
> `hermes-agent.nousresearch.com/docs`) — API server, dashboard, gateway, sessions.
> It is a different artifact from NousResearch's **Hermes** LLM family (Hermes 2/3/4,
> model weights). Hermes Agent's inference backend is whatever model its config points
> at — not necessarily a Hermes-branded model. Never pull endpoint, field, or auth
> details from a Hermes-model doc/card/benchmark; only `hermes-agent.nousresearch.com/docs`
> and this file's own verified fixtures count as sources here.

**Source:** Hermes Agent official docs (API Server, Programmatic Integration, Web Dashboard),
read 2026-09-16. **Partially verified against installed Hermes 0.21.1 on 2026-09-16.**
Confirmed live: `/v1/capabilities`, `GET /api/sessions`, `POST /api/sessions`,
`GET /api/sessions/{id}/messages`, `POST /api/sessions/{id}/chat/stream` (two full turns,
including a `terminal` tool call). Still unverified: `PATCH`/`DELETE /api/sessions/{id}`,
`/fork`, the non-streaming `/chat`, and every `/v1/runs/*` operation. Verify before
implementing. Claude Code must not use any endpoint not listed here.

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

**This deployment is a container, not a `~/.hermes` install.** Verified 2026-09-16 by reading
the live gateway process (pid on `127.0.0.1:8642`). Hermes runs as a rootless Docker
container, s6-supervised, as the `praxisog` host user. Inside it `HERMES_HOME=/opt/data`,
which is bind-mounted from the host path `~/hermes-data`. So the files that matter are:

| What | Host path | In-container path |
|---|---|---|
| Main config | `~/hermes-data/config.yaml` | `/opt/data/config.yaml` |
| Env / keys | `~/hermes-data/.env` | `/opt/data/.env` |
| Session DB | `~/hermes-data/` | `/opt/data/` |

`/opt/hermes` (the code) exists **only** inside the container — there is no such host path.
To read it, go through `/proc/<gateway-pid>/root/opt/hermes`. There is no compose file on
disk and `docker` CLI is not accessible to this user, so the container is managed outside
this repo. `~/.hermes/` does not exist on this host; earlier drafts of this file said the
config lived there and that was wrong.

The env keys themselves are unchanged:

```bash
API_SERVER_ENABLED=true
API_SERVER_KEY=<long random>
API_SERVER_HOST=<tailscale IP>   # default 127.0.0.1 — must change or the phone can't reach it
API_SERVER_PORT=8642
```

Then `hermes gateway`. It logs `[API Server] API server listening on http://…:8642`.

Auth is `Authorization: Bearer $API_SERVER_KEY` on every endpoint. Same key for `/v1/*` and
`/api/*`. No CORS needed (native client, not a browser).

> **This key grants shell execution inside the Hermes container.** The API server exposes
> the full toolset including `terminal`, and Gate 0c confirmed live that a bearer-authenticated
> request executes shell commands (`id -un` → `hermes`, exit 0).
>
> Scope it accurately rather than over- or under-stating it. Commands run as the **`hermes`
> user inside a rootless container**, not as host root — `sudo` is not even installed
> (`sudo: command not found`, exit 127). But `/opt/data` is bind-mounted from
> `~/hermes-data`, so the key confers full read/write over the Hermes data directory,
> including `config.yaml` and `.env` and therefore **the API key and the provider keys**. A
> leaked key is a full compromise of the Hermes instance and everything it can reach; it is
> not a host root compromise.
>
> Bind to Tailscale only, never a public interface. Store the key in
> EncryptedSharedPreferences, never in a build file or a committed env. Have a rotation path
> for a lost phone.

## Verify before building

```bash
curl -s -H "Authorization: Bearer $KEY" http://$HOST:8642/v1/capabilities | jq
```

`/v1/capabilities` reports `auth` (`{"type": "bearer", "required": true}`) plus
`platform`, `model`, `runtime`, `features`, `endpoints`. Flags observed live on 2026-09-16
that this file had not recorded: `approval_events`, `run_approval_response`, `run_steer`,
`run_status`, `tool_progress_events`, `session_model_lock`, `session_resources`,
`session_continuity_header: X-Hermes-Session-Id`, and
`runs_idempotency: {supported: true, durable: true, retention_seconds: 86400}`. **That response is the real contract for our installed
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

### Response envelopes — three different shapes

Confirmed against live 0.21.1. Do not model these as one generic wrapper:

| Endpoint | Envelope |
|---|---|
| `GET /api/sessions` | `{object, data: [...], limit, offset, has_more}` |
| `GET /api/sessions/{id}/messages` | `{object, session_id, data: [...], pagination: {...}}` |
| `POST /api/sessions` | `{object, session: {...}}` — single object under `session`, **not** `data` |

Session objects in the list carry 26 fields (`id`, `source`, `model`, `title`, `started_at`,
`message_count`, token/cost counters, `parent_session_id`, `preview`, `pinned`, `archived`,
`hidden`, …). Message objects carry `id`, `session_id`, `role`, `content`, `tool_call_id`,
`tool_calls`, `tool_name`, `timestamp`, `token_count`, `finish_reason`, `reasoning`,
`reasoning_content`, `display_kind`.

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

**`tool.completed` carries no tool output.** Verified 2026-09-16: the event's payload is
`message_id`, `tool_name`, `preview`, `args`, `session_id`, `run_id`, `seq`, `ts` — and on
completion `preview` and `args` are both `null`. The actual result lives only in the message
history (`GET /api/sessions/{id}/messages`), as a `role: "tool"` message whose `content` is a
JSON string like `{"output": "...", "exit_code": 0, "error": null}`. A client that wants to
show tool output cannot get it from the stream alone.

In the installed 0.21.1 stream, `assistant.completed` carries `completed` / `partial` /
`interrupted` flags; `run.completed` carries `completed` and usage. Render the distinction
— a truncated or interrupted turn must not look like a finished one.
The provider-404 attempt still produced `run.completed` with zero usage and no persisted
assistant message, so the client must also recognize an empty or failed inference.

### Run control (applies to session/chat/stream runs too)

| Method | Path | Notes |
|---|---|---|
| `GET` | `/v1/runs/{run_id}` | Status without holding SSE open. **Verified 2026-09-16** |
| `GET` | `/v1/runs/{run_id}/events` | SSE, attach/detach without losing state. Still unverified |
| `POST` | `/v1/runs/{run_id}/stop` | **Verified 2026-09-16 — works, but does *not* settle as `cancelled`. See Gate 0d.** |
| `POST` | `/v1/runs/{run_id}/steer` | Observed in live capabilities 2026-09-16 (`run_steer: true`). Not used by Tier 1; noted so it isn't mistaken for invented later. |
| `POST` | `/v1/runs/{run_id}/approval` | Exists, but **unreachable from this client** — see "Approvals cannot reach this client" below |

### Discovery

`GET /v1/capabilities`, `GET /v1/skills`, `GET /v1/toolsets`, `GET /health`,
`GET /health/detailed`, `GET /api/model/options`.

## Things that will bite

- **Approvals cannot reach this client, by design.** *Settled by Gate 0c, 2026-09-16 —
  this bullet previously said the opposite.* `/v1/capabilities` advertises
  `approval_events: true` and `run_approval_response: true`, and
  `POST /v1/runs/{run_id}/approval` exists. None of that applies to us, because the
  **`api_server` platform is explicitly excluded from being an approval context.**
  `tools/approval_context.py:166-170` is unambiguous:

  > Unattended programmatic platforms (webhook, msgraph_webhook, api_server) are excluded
  > […] those adapters have no `send_exec_approval` and no way to receive `/approve`
  > replies. Submitting a pending approval there blocks the session for the full approval
  > timeout (60-300 s) with no human who can resolve it. Their dangerous-command handling
  > is governed by `approvals.unattended_mode` config (default deny), mirroring cron.

  So a session created through `/api/sessions` will **never** emit a pending approval for
  the phone to answer. The feared hang-forever failure mode is what this exclusion exists
  to prevent. A flagged command resolves instantly, one of two ways:

  1. **Smart approval auto-approves it.** Observed live: `rm -rf /opt/data/gate0c-probe`
     executed, and the tool result carried an audit note —
     `"approval": "Command was flagged (recursive delete) and auto-approved by smart approval."`
  2. **`unattended_mode` denies it.** Not set in `~/hermes-data/config.yaml`, so it runs on
     the default from `hermes_cli/config_defaults.py:1538` — `"unattended_mode": "deny"`.
     The tool result comes back with `status: "blocked"` and a `Command denied: <desc>.`
     message, and the command does not run.

  **Client consequence:** do not build an approval prompt. Do render `status: "blocked"` as
  a distinct, honest state — "Hermes refused this command" — not as a generic tool error,
  or the user will think the app broke when the server deliberately said no. Render the
  `approval` audit note too when present, so an auto-approved destructive command is
  visible rather than silent.
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

## Gate 0c status — approvals settled (2026-09-16)

Ran against the live container, session `api_1789554464_8413b4c7`, model
`accounts/fireworks/models/deepseek-v4p1-flash` via Fireworks.

**Probe 1 — benign shell.** `id -un` → `run.started` → `message.started` → `tool.started`
(`tool_name: "terminal"`, `preview: "id -un"`) → `tool.completed` 190 ms later →
5× `assistant.delta` → `tool.progress` (`tool_name: "_thinking"`) → `assistant.completed`
(`completed: true, partial: false, interrupted: false`) → `run.completed` → `done`. Whole
turn 1.94 s. **No approval event of any kind.** Tool result: `{"output": "hermes",
"exit_code": 0, "error": null}`.

**Probe 2 — destructive + privileged.** A real `rm -rf` against a throwaway directory
created for the test, and `sudo -n true`. Both executed, no approval event, turn completed
in 8.8 s. The directory was really deleted. Results:

- `rm -rf /opt/data/gate0c-probe` → `{"output": "", "exit_code": 0, "error": null,
  "approval": "Command was flagged (recursive delete) and auto-approved by smart approval."}`
  — so the classifier **did** fire; the policy auto-approved rather than gating.
- `sudo -n true` → `{"output": "/usr/bin/bash: line 5: sudo: command not found\nsudo exit=127",
  "exit_code": 0, "error": null}` — no `sudo` in the container image.

**Why no prompt, from source** (`/proc/<pid>/root/opt/hermes`): `terminal_tool.py:836`
`_run_approval_guards` has four outcomes — `pending_approval` (gateway ask mode), `blocked`
(denied), `user_approved`, `smart_approved`. The `pending_approval` branch is unreachable for
us because `approval_context.py:172` returns False for unattended platforms and
`api_server` is one of them. Flagged commands therefore fall to
`approvals.unattended_mode`, which is **not set** in `~/hermes-data/config.yaml` and
defaults to `"deny"` (`hermes_cli/config_defaults.py:1538`). `_yolo_active()` is not in play.

**Conclusions.**

1. **No approval UI is needed or possible on this transport.** Tier 1 does not need an
   approval prompt — see the rewritten bullet above.
2. **The client must render `status: "blocked"` honestly**, because deny-by-default means a
   command the user genuinely wanted can be refused with no prompt and no retry path.
3. **Open decision for the operator, not the client:** leave `unattended_mode` at `deny`
   (safe; some commands the user wants will be refused) or set it to `approve` (the phone
   becomes able to run anything smart approval flags, unattended). Leaving it at `deny` is
   the recommendation. Either way it is a one-line change in
   `~/hermes-data/config.yaml` under `approvals:`, not app work.

## Gate 0d status — run control (2026-09-16)

`docs/hermes-api.md` previously claimed run control "applies to session/chat/stream runs
too" and that stop "settles as `cancelled`". The first is true. The second is false.

**`GET /v1/runs/{run_id}` — verified.** Returns
`{object, run_id, status, updated_at, created_at, session_id, model, last_event}`, adding
`usage` once finished. Statuses observed: `running`, `stopping`, `completed`.

**`POST /v1/runs/{run_id}/stop` — verified, with two caveats that change how a client uses
it.**

*1. There is a registration race.* Called immediately after `run.started`, stop returns
**409** `{"code": "run_not_active", "message": "Run is not active in this gateway process"}`
even though `GET /v1/runs/{id}` simultaneously reports `running`. The streaming path does pass
`active_run_id` (`api_server.py:3134`), but registration into `_active_run_agents` happens
inside the executor thread *after* the agent is constructed (`api_server.py:3655`), while
`run.started` is emitted before that. Called 1.5 s in, the same request returned
**200** `{"status": "stopping"}`.

> **Client rule:** don't offer stop until the first `assistant.delta` arrives, and treat 409
> `run_not_active` as "not stoppable yet", never as a hard error.

*2. A stopped turn is indistinguishable from a finished one.* The stop works — generation
halted 40 ms after the 200 — but every signal the server sends says the turn finished
normally:

| Signal | Value after a successful stop |
|---|---|
| `assistant.completed` | `completed: true`, `partial: false`, `interrupted: false` |
| `run.completed` | `completed: true` |
| `GET /v1/runs/{id}` | `status: "completed"` |
| persisted message | `finish_reason: null` |

Proved by asking for a count from 1 to 400 and stopping at delta 16; the persisted assistant
message is exactly `"1\n2\n…\n16"`. Nothing in the response distinguishes that from a turn
that genuinely finished. (This says nothing about whether `interrupted` is ever set by *other*
interruption paths — only that an API stop does not set it.)

> **Client rule:** the `interrupted` flag cannot be used to detect a user-initiated stop. The
> client must remember that it asked. `ROADMAP.md`'s "a truncated turn must not look finished"
> is therefore satisfiable from two client-side facts — a dropped stream, which surfaces as
> `HermesError.StreamTruncated`, and a stop the client itself issued — and **not** from server
> flags.

**Decision: ship the stop button anyway.** The instruction was to ship without it if it did
not behave as documented. It does stop the turn, reliably and fast, which is the part that
matters; only the labelling is wrong, and the client can label it correctly from what it
already knows. Shipping a working stop with client-side state is better than no stop.
