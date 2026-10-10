# Bellpull prompt editing extension (2026-10-09)

Build: `docker build -t hermes-agent:bellpull-edit-fork-20261009 .`.
Tests: 16 isolated aiohttp/SQLite tests pass using disposable databases, no live
mounts or model/network access. Adds `session_fork_before_message` capability and
authenticated `POST /api/sessions/{id}/fork-before-message` with a positive integer
`before_message_id` referencing an active user prompt. Copies only the preceding
history, including an empty prefix for the first prompt; preserves model configuration,
system prompt, lineage and original conversation. No inference and no auto-send.
Existing selected-assistant-reply behavior is unchanged.

Activated with `activate.sh apply` after hash verification and health reported zero
active API runs, agents and delegations. Original here is the Oct 8 selected-reply
patch, not the image's unpatched module. `activate.sh rollback` restores that version.
Live capabilities now advertise both exact-cutoff branch forms. Module survives a
container restart; recreation requires this image (compose definition remains absent).
Do not regenerate deployment configuration from secret-bearing container environment.
