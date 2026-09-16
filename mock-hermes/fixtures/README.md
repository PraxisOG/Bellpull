# Gate 0 fixtures

Captured from the installed Hermes 0.21.1 API on 2026-09-16.

- `session-list.json`: one real `GET /api/sessions` response envelope, filtered to
  `source=api_server` and `include_children=false`.
- `session-messages.json`: the matching session's four-message history, including
  one assistant tool call and one tool result.
- `session-chat-stream.sse`: a successful fresh-session turn with four token deltas,
  one progress event, assistant completion, and a completed terminal event.

The response structure and field types are preserved. Session and tool IDs, message
IDs, timestamps, model name, title, preview, message text, reasoning, tool arguments,
tool names, and SSE token text were replaced with synthetic values. Token counts and
other numeric metadata remain from the responses. No bearer key is stored here.

The first SSE attempt received a provider 404 from an invalid model configuration;
it was discarded. See `docs/hermes-api.md` for Gate 0 status.

## Gate 0c additions (2026-09-16)

- `terminal-tool-results.json`: the three `role: "tool"` messages from the Gate 0c approval
  probes, in the `GET /api/sessions/{id}/messages` envelope. Captured live. The middle one is
  the reason this fixture exists — it carries the `approval` audit note that smart approval
  attaches when it auto-approves a flagged command:

  ```json
  {"output": "", "exit_code": 0, "error": null,
   "approval": "Command was flagged (recursive delete) and auto-approved by smart approval."}
  ```

  Session/message/tool-call ids and timestamps are synthetic. `output`, `exit_code`, `error`
  and `approval` are verbatim from the live responses. Token counts and `display_kind` are
  as returned.

**Not captured, deliberately:** the `status: "blocked"` shape for a *denied* command. Getting
it live would mean running something dangerous enough that smart approval refuses it, which
isn't worth doing on the live instance. Its fields are known from source
(`tools/terminal_tool.py` `_run_approval_guards` → `_error_json(..., status="blocked")`) but
have **not** been observed on the wire. Do not write a fixture for it pretending otherwise —
if Slice 3 needs one, derive it from source and label it source-derived.

## Slice 2 additions (2026-09-16)

- `capabilities.json`: the full live `GET /v1/capabilities` response — 29 feature flags and
  28 endpoints, verbatim. It contains no host-specific or secret values (`platform`, `model`
  and `runtime` are generic strings), so nothing needed sanitizing. This is what makes the
  app's existing connection probe testable without a live Hermes.
- `session-create.json`: a live `POST /api/sessions` 201 response. Note the envelope is
  `{object, session}` — a single object under `session`, **not** under `data` like the list
  and history endpoints. Id, title and `started_at` are synthetic; every field and type is
  preserved.
- `error-responses.json`: the error shapes, five of them captured live —

  | Case | Status | Notes |
  |---|---|---|
  | `auth_failed` | 401 | Missing header, wrong key and non-Bearer scheme are all this one shape |
  | `session_not_found` | 404 | JSON envelope with `code: "session_not_found"` |
  | `unknown_path` | 404 | **Plain text `404: Not Found`, not JSON** |
  | `missing_message` | 400 | `{"message":""}` and `{}` both produce it |
  | `invalid_json` | 400 | `code` is `null` here |
  | `rate_limited` | 429 | **Source-derived, not observed** — see below |

  The `unknown_path` row is the trap: a client that assumes a JSON error envelope on every
  non-2xx response will throw a parse error instead of reporting the 404.

  `rate_limited` is the one shape not reproduced live — triggering it would mean running 11
  concurrent turns against the homelab. Its body and its `Retry-After: 1` header are read
  from `gateway/platforms/api_server.py:3529` (`_concurrency_limited_response`) and it is
  labelled `observed: false` in the fixture. Honour `Retry-After` rather than inventing a
  backoff curve.

