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

