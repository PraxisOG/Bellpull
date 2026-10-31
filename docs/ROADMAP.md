# Bellpull phase roadmap

Updated **October 31, 2026**. Scope follows the user's October 11 roadmap and
later decisions. This is a plan with implementation status, not a completion claim.
The original [October 11 text](../Bellpull-Updated-Phase-Roadmap-2026-10-11.txt)
is retained unchanged. **Phase 7's later scope supersedes its original Quick Chat plan.**

Current experimental release: app **0.18.2-experimental / build 47**, plugin
**0.8.1-integration-preview**, on `fix/kanban-reliability-0182`.
The October 31 implementation includes native Kanban reading, creation and field editing.
Phase 18 is a new identifier; existing phase numbers and release references are
retained. Delivery order is **18A → 18B → 18C → 18D**, ahead of deferred Phase 16
and final-polish Phase 17. Published as [android-v0.18.2-experimental.1](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.18.2-experimental.1).
[Kanban implementation, validation and remaining work](PHASE-18-KANBAN.md).
[Phone assistant, phone context and offline banner repair](PHASE-15-ASSISTANT.md).
[Reply-notification and assistant-settings repair](RELEASE-0.15.1-EXPERIMENTAL.md).
[Today implementation and acceptance scope](PHASE-11-TODAY.md).
[Phone notification context implementation and limits](PHASE-12-NOTIFICATION-CONTEXT.md).
[Local LLM experiment and measurements](PHASE-13-LOCAL-LLM.md).
[Voice, image and reconnect repairs](evidence/voice-media-20261022/README.md).
[Kokoro sentence streaming](evidence/kokoro-sentences-20261022/README.md).
[Native Hermes and vLLM reasoning controls](evidence/vllm-reasoning-20261022/README.md).
[Direct thinking submenu](RELEASE-0.14.8-EXPERIMENTAL.md).
[Startup replay memory repair](RELEASE-0.14.9-EXPERIMENTAL.md).
[Moe named-route thinking repair](RELEASE-PLUGIN-0.6.2-EXPERIMENTAL.md).
[Phone loudspeaker repair and manual checklist](release.md#phone-loudspeaker--01410--build-42).
[Current reliability evidence](EXPERIMENTAL-ISSUE-REPAIRS.md#october-15-re-audit).

## At a glance

| Phase | Focus | Current status |
| :--- | :--- | :--- |
| 0–3 | Contracts, everyday chat, durable runs, models and files | Implemented foundation; host-specific compatibility limits remain |
| 4 | Projects as conversation folders | Implemented and fixture-phone tested |
| 5 | Conversational voice | Implemented; acoustic/car/background acceptance remains open |
| 6 | Hermes-owned OpenCode activity and read-only diffs | Combined preview; deterministic native integration/phone tests passed |
| 7 | Hermes prompt optimization and latency | Rescoped to backend work; no Quick Chat app feature planned |
| 8 | Persistent reminders and native Hermes routines | Preview; background/offline reminder delivery belongs to Phase 10 |
| 9 | Phone calendar management and visual day/week blocks | Preview; selected calendars, separate write opt-in, explicit proposal Apply |
| 10 | Local notification inbox, offline reminders and delivery | Experimental framework; real Doze/reboot acceptance remains open |
| 11 | Today dashboard | Implemented overview and shared-cache native task preview; the reliability work in issue #28 remains open |
| 12 | Phone notification context | Early implementation: opt-in encrypted history, exclusions, retention and per-server bounded context |
| 13 | On-device LLM feasibility | Experimental local chat lab; E2B CPU works, Vulkan fails correctness checks |
| 14 | Offline conversations and reconnection | Experimental integrated fallback, local compaction and reviewed resync |
| 15 | Phone assistant replacement and device actions | First experimental slice: default-assistant picker/entry, bounded phone commands, speech and chat handoff |
| 18 | Shared native Hermes Kanban | Implemented: authenticated plugin contract, board/list/detail cache, task creation and field editing, comments, non-executing moves, durable drafts/queue and a Today preview. Broader lifecycle controls remain open. |
| 16 | Android Auto | Separate deferred investigation |
| 17 | Public release polish, widgets and Quick Settings | Final polish |

Phase 5 does not yet establish reliable tool-announcement playback ordering,
Bluetooth/car echo behavior or sustained screen-lock acceptance. Telecom screen-off,
hold/resume/end and streaming CPU transcription now have [experimental evidence](evidence/car-streaming-20261020/README.md). Phase 6 observes verified
Hermes-owned children; it does not provide arbitrary terminal access or an OpenCode
approval API. Phase 8 has not verified real scheduled inference. Phase 9's local
CalendarContract tests do not establish account/cloud synchronization; complex
recurring edits and invitations are deferred. Known compatibility and approval-test
failures are recorded in the integration evidence rather than hidden by these statuses.

Scope and priority reconciliation from the original implementation prompt and
the subsequent phase-by-phase discussion. This is a roadmap companion, not a
replacement for the full original implementation instructions or a claim that
any phase has been completed. Where scope conflicts, the decisions recorded here
supersede the original scope. Verify actual implementation status in the repository.

## Priorities

Existing foundation: Phases 0–3. Preserve and repair rather than rebuild.
Active roadmap: Phases 4–15. Phase 15 was activated October 28.
Active feature: Phase 18, native Hermes Kanban, selected October 31. Implemented scope covers
the plugin contract, board reading, task creation, field editing, comments,
non-executing moves and offline reconciliation, with a shared-cache task preview in Today.
The local test Hermes is updated; the user handles Moe deployment manually.
Broader lifecycle, reliability and other deployed-host acceptance work remains open.
Deferred work: Phase 16; broader assistant capabilities remain follow-on work.
Final polish: Phase 17. Widgets and Quick Settings belong here.

The deferred numbering is organizational, not a strict dependency chain.
Android Auto can be investigated once phone voice/background audio are reliable;
it does not require an on-device LLM. Assistant replacement now has an initial activity-based implementation. Offline conversation now builds on local caching and the measured CPU inference path.

## Existing foundation

### Phase 0 · System inspection and integration contracts
Inspect the actual app, installed Hermes version, plugin, data ownership, and
supported capabilities. Preserve working behavior. Do not infer completion from
this roadmap or promise APIs that have not been verified.

### Phase 1 · Branding, navigation, and everyday chat
Bellpull identity, fold-aware navigation, per-chat drafts, prompt editing/branching,
quoted follow-up, search, thinking display, and grouped tool cards. The original
home-screen widget requirement is moved to Phase 17.

### Phase 2 · Reliable runs, recovery, unread state, and approvals
Durable request identity, reconnect/replay, truthful run status, unread tracking,
existing reply/attention notifications, and genuine approval handling. Phase 10
extends this foundation; it must not replace reliable existing behavior blindly.

### Phase 3 · Models, files, previews, sharing, and GPU metrics
Supported model/reasoning controls, uploads, PDF/image previews, artifact browsing,
share-in/share-out, and read-only GPU telemetry. Resolve remaining audited repair
items against the actual code before dependent features rely on them.

## Active roadmap

### Phase 4 · Projects as conversation folders
Create/rename projects; organize chats with one project per chat; create a chat
within a project; move/remove membership; pin important chats; archive projects.
Provide All conversations and Unfiled. Deleting a project returns its chats to
Unfiled rather than deleting their content. Preserve drafts, active work, branch
membership, and identity across compaction/continuation. Scope metadata correctly
to the Hermes connection/profile. Prefer one metadata authority with a phone cache.
Optional manually edited project description only. No memory editor, semantic
search system, history imports, or project knowledge engine. Native task boards
are a separate Phase 18 surface; they do not change Phase 4's folder semantics.

### Phase 5 · Conversational voice
The deliverable is actual hands-free conversation, including turn detection,
interruption, echo handling, clear listening/processing/speaking states, and early
speech playback. Dictation/read-aloud alone do not complete the phase.
Build in small slices:
- **5A.** Shared capture, STT, TTS, streaming playback, and cancellation foundation.
- **5B.** Conversational loop, tolerant pauses, interruption, echo handling, voice UI,
      and a concise, friendly voice-specific prompt/preset.
- **5C.** Background and car-audio reliability: screen lock, app switching, Bluetooth,
      route changes, navigation audio, calls, and connection recovery. Design the
      session ownership for these requirements from the outset.
Announce meaningful tool work briefly before execution; ensure actual playback
ordering rather than relying on prompt text alone. Use tools and reasoning
economically without suppressing necessary verification. Discard stale speech
after interruption. Distinguish stopping speech from canceling agent work.
Keep typed-chat settings independent. Additional voice customization is optional
polish. Android Auto has its own deferred milestone.

### Phase 6 · Hermes coding and activity view
A CLI-style view of the existing Hermes conversation/run: chronological tool
activity, expandable command output, truthful states, approvals/questions, stop
and follow-up controls, reconnect recovery, workspace identity, and basic read-only
changed-file/diff inspection. Reuse existing sessions and running-work navigation.
Expose steering only if supported. Label workspace changes honestly when they
cannot be attributed to the run. Show delegated child activity if Hermes exposes it.
No separate Codex/Claude CLI harness, arbitrary terminal attachment, mobile IDE,
automatic worktree manager, or response-comparison system. External coding agents
may later be Hermes integrations; their runner implementation is outside Bellpull.

### Phase 7 · Hermes prompt optimization and response latency
Updated October 12: the user fixed backend caching. Phase 7 is now Hermes-side
prompt optimization and latency verification, not a Quick Chat app feature.
Measure cold requests, same-chat follow-ups and new conversations on the actual
model/provider. Keep the stable prompt prefix intact, reduce unnecessary context
and tool overhead, and measure time to first useful output. No separate direct-model
route, new Quick Chat button or automatic model substitution is planned.

### Phase 8 · Reminders and Hermes routines
Expose real one-time and recurring Hermes jobs: list, create, edit, pause/resume,
cancel, next run, last result, and delivery destination. Use existing scheduling
authority and deterministic controls rather than model turns for simple UI actions.
Support ordinary timed reminders without requiring inference at alert time.
Add persistent reminders as a bounded follow-on if the existing data model makes
it manageable: pending/completed/dismissed state, due time, snooze, and explicit
completion. Firing an alert is not completing the user's task.
Provide a short overdue/upcoming unfinished-reminder summary to full Hermes context,
after the stable prompt prefix. Avoid putting the entire backlog in every request;
exclude it from Quick Chat by default. Do not turn this into full task management.
Calendar events belong in Phase 9; delivery/local notification behavior in Phase 10.

### Phase 9 · Calendar management
Use Android's phone calendar storage as Bellpull's provider-independent source.
Existing account sync remains responsible for upstream synchronization, including
Nextcloud via a configured sync adapter. Do not build a new CalDAV sync engine or
require a direct calendar connector on every Hermes server.
Reading/sharing is opt-in per Hermes connection with selected calendars. Writing
is separately enabled, easily disabled, and limited to permitted calendars with
a default destination. Optional busy-only sharing should avoid exposing details.
Read agenda, event details, and availability. Support ordinary event create/edit/
delete and simple recurrence creation. Handle recurring occurrences, exceptions,
all-day dates, timezones, and event alert settings correctly. Hand complex series
edits to the existing calendar app initially. Defer invitations/account management.
Refresh on app foregrounding, return from multitasking, Today/calendar entry,
calendar questions, and relevant local changes. Show cached content immediately;
coalesce overlapping refreshes. Distinguish phone-read freshness from cloud sync.
Hermes sends structured operations; the phone validates permissions, executes,
and returns verified results. Stable operation identity prevents duplicate writes.
Validate stale event references before changes. Clear user requests do not require
a blanket extra confirmation; resolve ambiguous dates, targets, or series scope.
If the phone is unavailable, expose a pending proposal and revalidate before later
execution. Never claim a completed local write proves upstream synchronization.
Hermes's calendar copy is a bounded cache. Revocation stops sharing and clears that
dedicated cache; existing chat history is a separate retention concern.

### Phase 10 · Local notification inbox, offline reminders, and delivery
This phase covers Bellpull's own notifications, separate from Phase 12's reading
of notifications posted by other apps.
Persist received results, reminders, and interaction state locally. The inbox opens
without contacting Hermes; synchronize incrementally with durable event IDs.
Categories: reminders, needs attention, completed work, routine results, and quiet
service status. Preserve system notification controls and useful deep links.
Pre-schedule known simple timed reminders on the phone so alerts can fire offline.
Record delivery ownership per occurrence: later server updates must not trigger a
second alert. New remote completions still need incoming delivery; a local cache
does not eliminate this need. Inspect/test existing transport before adding push.
Keep received/displayed/opened/resolved states separate. Dismissal does not approve
work or complete a task. Snooze differs from rescheduling. Store edits/cancellations
and reconcile them. An offline phone can temporarily have an old schedule.
Restore pending reminders after reboot/update; handle timezone changes, missed
alerts, retry, retention, and per-connection isolation. Group stale catch-up results.
Suppress token/tool spam and redundant alerts for content currently being viewed
or spoken. Calendar apps own appointment alerts by default to avoid duplication.
Verify actual Android idle/background delivery and scheduling precision on-device.
Local caching is current work; it does not depend on future on-device inference.

### Phase 11 · Today dashboard
A small calendar/reminders dashboard with a compact agent-attention/work section.
Show today's events, due/overdue reminders, pending decisions, running work, and
unread completions. Read established local records; refresh in the background.
Complete/snooze/open actions use existing handlers. No model call to render Today.
Empty and stale/unavailable states remain distinct. Keep source links and avoid
duplicated databases or inferred commitments. Optional Brief me is later polish.
Today is a chosen tab, not a forced landing screen on every launch.
The follow-on [Today profile (#29)](https://github.com/PraxisOG/Bellpull/issues/29)
adds notification attention and native Kanban tasks. Its task section reuses the
Phase 18 snapshot/cache, and the combined screen depends on
[calendar, notification and action reliability (#28)](https://github.com/PraxisOG/Bellpull/issues/28).

### Phase 12 · Phone notification context — EARLY EXPERIMENTAL IMPLEMENTATION
Updated user intent: access all phone notifications available through Android's
supported notification-access mechanism, after explicit opt-in, so Hermes can use
them as context. Do not limit the product to a small initial app allowlist.
This runs with server-side Hermes and does not depend on an on-device model.
Keep a local record of observed notification content, source, time, updates, and
removal state; send/retrieve relevant context without dumping the whole history
into every prompt. Broad access and bounded prompt context are compatible.
Implemented defaults and remaining limits are in [Phase 12](PHASE-12-NOTIFICATION-CONTEXT.md).
Audit coverage includes retention, server-sharing controls, foreground/
background refresh, update/group deduplication, treatment of ongoing/system alerts,
revocation/clear-history behavior, and representation of missing/redacted content.
Do not promise content the OS does not expose or historical notifications never
observed. Notification text is contextual data, not permission to execute actions.
Reading/context access does not itself authorize auto-replies or notification
action execution. Per-app exclusion controls can remain available without making
restricted app selection the default requirement for the requested broad mode.

## Local experiments and assistant

### Phase 13 · On-device LLM feasibility — EXPERIMENTAL IMPLEMENTATION
The user authorized this experiment after publishing 0.12.0. See
[the local chat lab and measurements](PHASE-13-LOCAL-LLM.md). CPU chat, local GGUF
import, benchmarks, cancellation/background unloading and a conservative GPU
correctness gate are implemented. E2B CPU passed device checks; Vulkan did not.
Long-duration battery/thermal acceptance remains open.

CPU-first bounded experiment on the actual phone with the intended model/release.
Measure quality, startup, prompt processing, generation, memory, thermal/battery
behavior, cancellation, and lifecycle. Give a usable-scope or no-go result before
building a full local agent. Do not quietly substitute a model or cloud fallback.

### Phase 14 · Offline conversations and reconnection — EXPERIMENTAL
The 0.14.1 experiment integrates Gemma E2B CPU into the conversation shell. Settings
configure automatic loading, thinking defaults and 2K/4K/8K contexts. The 0.14.3
update exposes the editable local system prompt and an explicit Hermes request
for a short personal profile that the user can copy into it. Losing the
server connection while the app is visible loads the model; a red banner becomes
yellow when ready. Offline + creates a local chat; long-press + offers one online.
Chat menus create encrypted local forks with resumable chunk compaction and a
warning for sources exceeding the selected context. Local history compacts as
needed, while original messages remain searchable. Memory snapshots sync locally
without bulk summarization. Approved, idempotent resync imports searchable Hermes
history; the main list reconciles the local/server copies into one entry.
See [implementation and acceptance](PHASE-14-OFFLINE.md).

Further acceptance: very large histories, sustained thermals/battery, broader
summary quality and local tool adherence. Automatic merging into the original chat
and changes to Hermes permanent memory notes are outside this version.

### Phase 15 · Phone assistant replacement and device actions — FIRST EXPERIMENTAL SLICE
Activated October 28: [implementation, supported commands and limits](PHASE-15-ASSISTANT.md).
The initial slice adds assistant selection/entry, direct phone commands, foreground
speech and new-draft chat handoff. The same increment adds opt-in phone notification
and calendar snapshots to the plugin for new API chats, independent local-context
controls, and a separate Notifications view for background conversations. Phone
calendars are the source; Nextcloud sync is independent. Uploading never starts a
chat or a model run. Broader model-driven actions remain future work.

Long-term goal: Bellpull replaces the user's phone assistant. Combine default-
assistant integration with useful phone actions once voice and local capabilities
are mature enough. Candidate actions: alarms/timers, app launch, navigation, URLs,
and deliberate handoff to messaging/dialer/share flows. Use supported capabilities;
no broad screen automation or root requirement by assumption. Local and Hermes
routes can coexist; local inference need not handle every task.

## Next feature phase

### Phase 18 · Shared native Hermes Kanban — READING AND EDITING IMPLEMENTED

**Outcome:** open the phone and see the same native task board owned by the
selected Hermes installation, create a task, edit its fields, inspect its work, move a card, and comment.
Use a compact list on the cover screen and a board with task details on the
unfolded display. Reading, navigation and ordinary card edits require no model call.

Scope comes from [mobile Kanban (#30)](https://github.com/PraxisOG/Bellpull/issues/30)
and the [desktop task design](https://github.com/PraxisOG/bellpull-desktop/blob/20bcc1c93e4370a9c91370dbb1138f88e714019e/docs/BELLPULL-DESKTOP-BRIEF.md#7-tasks-across-devices).
The desktop source inspected at `20bcc1c` reserves a Tasks tab; it does not yet
implement this board. Shared ownership and contracts are the compatibility target.

**Settled decisions:**

- [Decision #35](https://github.com/PraxisOG/Bellpull/issues/35#issuecomment-6536300924):
  read and mutate through the Bellpull plugin under the normal gateway bearer.
  The infrastructure work supplies the endpoint contract in
  [hermes-api.md](hermes-api.md) before the app consumes it. Dashboard Kanban
  endpoints have a different authentication boundary and are outside this contract.
- [Decision #36](https://github.com/PraxisOG/Bellpull/issues/36#issuecomment-6536303450):
  render last-known state with its snapshot age always visible; explicitly queue
  offline mutations with a pending state; unknown delivery never authorizes
  re-execution. File references carry device, path and hash rather than file content.
- Every task has one home board; every attempt has one executor. Display board
  ownership and execution device separately. Task intent, execution attempt and
  Hermes run remain distinct. The phone displays and requests changes to owned
  records; the owning Hermes installation remains the authority.

**18A · Establish the plugin contract.** Infrastructure dependency, first.
Implemented in plugin 0.8.1 against native Hermes 0.21.1 and 0.21.5 fixtures,
with authenticated mutation checks on the local 0.21.5 test gateway.
[The contract](hermes-api.md#bellpull-native-kanban-extension--october-31-2026)
defines discovery, board incarnation and installation ownership, bounded snapshots,
freshness, errors, revision conflicts and retained atomic receipts. Native boards
are installation-wide; phone caches and pending edits remain connection-isolated.
Validation against each deployed host remains a rollout check.
Creation, field edits, moves and comments use stable operation identity and a documented way to determine
whether an operation was applied after its response is lost. Specify which native
transitions can dispatch work before exposing them as a simple card move.
Capture synthetic response fixtures for supported and unavailable servers.
Acceptance: the documented contract and fixtures support both clients, preserve
profile isolation, and demonstrate lost-response recovery without duplicate work.
The implementation document identifies tested routes, limits and remaining gates.

**18B · Read boards and task details.** First usable app milestone; depends on 18A.
Add a Tasks destination with board/list views and a board selector scoped to the
current connection/profile. Show native columns/statuses, title, project and owner,
plus execution status when the contract provides it. Task details expose supported
comments, dependencies, attempts/runs, conversation links, file references and
results. Use tap/menu navigation and accessible move controls in the later edit
slice; drag-and-drop is optional polish. Refresh on entry, foreground and explicit
request with bounded polling while visible if the contract supplies no event feed.
Persist the last authoritative snapshot within the existing encrypted cache,
isolated by connection/profile/board. Cached reading works after restart/offline;
show age, loading, empty, unavailable and incomplete states distinctly. A failed
refresh retains cached content with an explanation.
Acceptance: phone and fold layouts work with fixtures for a Moe-owned board and a
desktop-owned board, including identical task IDs on different boards, restart,
offline reading, missing capability, incomplete paging and authentication failure.

**18C · Move cards, comment and reconcile offline edits.** Depends on 18B and the
verified mutation/recovery contract from 18A. Offer only supported native moves
and comments. Show optimistic changes as pending overlays over the authoritative
snapshot, then confirm with server state. Persist the user's exact intended edit
and operation identity before submission. Offline edits remain visible, editable
or cancellable while unsent, and survive app restart. On reconnection, refresh
authority and revalidate unsent operations before delivery; conflicting changes
require review. An operation that may have reached the server stays outcome-unknown
until the documented status/snapshot evidence resolves it. Reuse its identity only
where the contract establishes retry safety; never generate a replacement operation
or transfer it to another executor to resolve uncertainty. Deleting a local pending
record cannot cancel an operation already sent. Rejection keeps the user's text
available and shows the authoritative card state.
Acceptance: concurrent desktop/phone edits surface conflicts; dropped responses
and process death produce no duplicate comment, transition or task execution;
queued edits survive offline/restart; switching profiles cannot deliver an edit to
the wrong board. Include real plugin round-trips before claiming end-to-end parity.

**18D · Open tasks in Today.** Depends on 18C and issue #28's reliability work.
Add a compact native-task section using the same cache and ownership badges, with
open/pending work and links into Tasks. Keep reminders and Kanban tasks distinct;
an alert, read receipt or dismissed notification does not complete a task. Show
snapshot age and pending/unknown edits consistently. Acceptance: board and Today
agree online and offline without a second task database or model call.

**Creation and field editing — implemented in 0.18.1.** New tasks enter triage
without dispatch. Title, description, assignee profile and priority can be edited
for inactive, unclaimed tasks. Encrypted form drafts retain their original task
revision through refresh/restart; pending creations remain visibly local until a
receipt supplies the native task ID. Creation and editing use the same atomic
receipt and conflict-review contract as moves/comments.
The 0.18.2 audit fixes queue blocking by deleted tasks, concurrent queue-capacity
checks, stale form callbacks, Unicode text limits and native priority decoding.
Explicit execution controls remain the next feature increment.

**Follow-ons:** explicit claim/start/stop/retry controls, dependency editing,
cross-installation board aggregation, and approval-queue integration need
their own verified contracts and scope. Card moves that trigger execution must use
explicit action semantics rather than silently starting work. The voice approval
queue in [#37](https://github.com/PraxisOG/Bellpull/issues/37) remains separate;
Kanban does not introduce blanket approval grants. Direct authenticated Tailscale
connections can select either installation; unified cross-device orchestration,
board replication and automatic execution failover are not established by this UI.

## Deferred and final polish

### Phase 16 · Android Auto — SEPARATE DEFERRED MILESTONE
Investigate permitted integration/distribution and practical microphone/session
behavior before committing to a dashboard implementation. Build on reliable phone
voice and background/car audio. It does not require Phase 13 or 14 to be complete.
Do not promise system-assistant or steering-wheel-button takeover.

### Phase 17 · Public release polish — LAST
Home-screen bar widget and Quick Settings tile, designed after notification and
assistant behavior are clearer. Reuse chat/voice entry points; preserve drafts and
avoid duplicate sessions. Other explicitly selected accessibility/onboarding and
release refinements may live here, but do not resurrect deferred features solely
because they appeared in the original brief.

## Outside the current scope

- Chat/history imports.
- Built-in memory editor and semantic memory search; existing Hermes memory/wiki
  facilities cover the user's needs.
- Project knowledge base, project files/instructions engine, or saved-answer system.
  Folder-style projects remain Phase 4; native task boards are Phase 18.
- Codex/Claude CLI runner harness, arbitrary terminal attachment, full mobile IDE,
  and response comparison as Phase 6 requirements.
- Widgets/Quick Settings as early prerequisites.
- Always-listening wake words, screen automation and unrestricted model-driven device actions.

## Implementation notes

Preserve existing app behavior and verified plugin contracts. Check the actual
installed version and repository before assuming earlier audit findings still
apply. Keep model/endpoint selection explicit, isolate Hermes connections, and
never require an LLM to render cached deterministic UI. Use small reviewable slices
and honest on-device validation. The scope list does not authorize deployment or
changes to live infrastructure by itself.
