# Shared Canvas v1

This branch adds `bellpull_canvas` capability and a native tool of the same name.
The tool is registered for existing conversations; no new chat is required.
Desktop and Android consume the same profile-scoped scene and event stream.

`GET /api/bellpull/sessions/{session_id}/canvas` returns version 1, stable profile
`scope`, canonical `session_id`, compression `session_ids`, `cursor`, `documents`
and the selected `document` (or null). Documents contain id, name, revision,
updatedAt (milliseconds), content, canUndo/canRedo and bounded history. Content
uses the existing desktop name/nodes/edges schema. Viewport and selection stay
local to each device. Ordinary forks remain separate.

`POST` on that path accepts action create/select/edit/undo/redo/restore,
`operation_id`, `canvas_id`, and for content changes an exact `base_revision`.
Create accepts name or a validated content snapshot (desktop import). Edit takes
1..400 operations, validated atomically; each produces an ordered committed scene.
Restore additionally takes target (history revision). IDs are 1..80 ASCII
letters/digits/underscore/hyphen. A matching repeated operation ID returns current
state without another write; changed content under that ID or stale revisions
return 409. An uncertain write must be reconciled with GET; never invent a fresh
operation ID to retry it automatically. Receipts survive restart.

`GET .../canvas/events` is authenticated SSE. JSON data carries event
`canvas.reset` or `canvas.committed`, with `snapshot` in the GET shape.
Every connection first receives an atomic snapshot and cursor. Further committed
states arrive in cursor order, including manual edits. Duplicate cursors are
ignored. A retention gap resets from current state; reconnect never executes old
tool calls. The journal retains 1,000 events per profile. IDs and revisions are
independent of the URL used to reach the server. The native bearer and named
profile middleware protect every route; no canvas credentials are introduced.

Native tool arguments use version 1, action read/create/select/edit/undo/redo,
canvas_id, base_revision, name, operations. Session, conversation-turn and tool-call identities
come from Hermes tool-execution middleware, never model-selected arguments. The server
derives operation identity from the native turn/call. Tool read returns current
state. Call edit once per visible step or small batch; partial tool arguments are
never executed. All edits from an uninterrupted model turn share one Undo frame.
Manual/history edits fence that turn; it cannot resume over newer manual work.
Native interruption is checked before commit. Completed edits remain saved.

Storage is profile-private SQLite under `bellpull-canvas/<principal>/`; 1,000
documents/profile, 100/conversation, 200 nodes/400 connectors, 20 Undo/Redo frames.
Old desktop drawings are imported explicitly into their bound existing chat with
stable IDs and a deterministic import receipt. The local original and its history
remain available as a backup. Imported content begins a new server revision chain.
Historical drawing text is display-only on shared-capable servers.

## Development preview and validation

This worktree is based on mobile commit ed99b43. The default app identity remains
unchanged. Build with `-PbellpullCanvasFixture=true` for the separate **Bellpull
Canvas** preview (`org.praxisog.hermes.canvasfixture`, 0.16.0-canvas-preview).
It preserves the primary app and its data. The current phone preview reaches the
local Docker gateway through `adb reverse tcp:18647 tcp:8642`; the USB connection
is required for this development setup. No server port was exposed externally.

Validated against the real local Hermes gateway: authenticated/profile-isolated
HTTP and SSE, native tool middleware, stable operation receipts, grouped Undo
across separate internal model requests, interruption, stale edits, compression
lineage, ordinary forks, replay gaps, and plugin unload. A real model run updated
two desktop clients during the reply. Pixel instrumentation reuses that same
conversation and checks touch editing, remote changes, cache/reconnect, Undo/Redo,
capability discovery, and access through the full conversation menu.

The desktop's original drawing files and history remain unchanged after import.
The desktop 0.5.0 launcher and local canvas extension are installed; this branch
is a separate mobile preview so concurrent Kanban development is preserved.
