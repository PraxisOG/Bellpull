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
