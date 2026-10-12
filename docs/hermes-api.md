# Phase 8/9 experimental planning contract — 2026-10-12

These are new **Bellpull-owned** routes, implemented in this branch's
`0.4.0-phase8-9` plugin, not guessed upstream APIs. All require the existing native
bearer authentication; served `/p/{profile}` routes retain that profile's home and
never fall back to root. Discovery advertises `bellpull_planning`; native cron
availability is separately reported as `bellpull_routines`.

- `GET /api/bellpull/planning`: v1 snapshot, `reminders`, `routines`, `proposals`,
  `routines_available`, `observed_at`. Max 1,000 reminders, 500 routines, 100 proposals.
- `POST /api/bellpull/planning/reminders`: `operation_id` (32 lowercase hex), action
  create/edit/snooze/complete/dismiss/reopen/delete. Create uses `title`, `due_ms`
  (absolute epoch milliseconds). Later actions require `id` and exact `revision`;
  edit includes title/time, snooze includes time. Same operation/content is idempotent;
  reused identity or stale revision fails 409. Due/alerted never means completed.
- `POST /api/bellpull/planning/routines`: stable `operation_id`, action create/edit/
  pause/resume/delete. Create/edit accept `name`, `prompt`, native `schedule` string;
  later actions bind native `id` and snapshot `fingerprint`. New routines deliver
  locally. Existing destinations remain visible and unchanged. No scripts, model
  substitutions, tool execution or second scheduler. Advanced scripted/skill/monitor
  routines need Hermes for edits. A crash after intent persistence has an explicit
  uncertain state; retry must not submit another native job. Refresh the native list
  and resolve the outcome before another action. External scheduler races can reject
  stale fingerprints. Native APIs are feature-detected from inspected cron.jobs, requiring its explicit
  `use_cron_store` context for every operation; older unsupported hosts decline routines.
- `PUT /api/bellpull/calendar/{device}`: device ID is stable per phone connection.
  Send `read_at_ms`, `busy_only`, and at most 500 events: `id`, `start_ms`, `end_ms`,
  `all_day`; detailed sharing additionally sends `title` and `fingerprint`. Busy-only
  mode omits those details on the phone and strips any supplied details on the server.
  Snapshot age must be within five minutes. `{revoke:true}` clears this device's
  dedicated server cache and proposals. At most 16 devices per native profile.
- `POST /api/bellpull/planning/proposals`: `id`, owning `device`, `state` applied/
  rejected, `result`. Same result is retry-safe. The phone reports applied only after
  verifying the CalendarContract write; this does not prove upstream account sync.

`bellpull_planning` tool actions: read/create_event/edit_event/delete_event. Reads
are bounded to 20 unfinished reminders and 200 shared events, default next seven
days; `from_ms`/`until_ms` can select at most 31 days. Freshness/truncation is explicit.
A proposal uses stable `operation_id`, device, title, start/end for create/edit,
and event ID/fingerprint for edit/delete. The server validates the shared reference,
then stores a pending proposal. **It does not write the phone calendar.** The user
must apply it in Bellpull. The phone rechecks OS permission, connection calendar
selection/access, current event fingerprint and supported non-recurring scope.
No new inbound phone token/automatic device bridge is implemented in this slice.

Planning SQLite lives under each profile's `bellpull-planning/`; phone policy,
metadata cache, revocation intent and operation receipts use the existing encrypted
per-connection cache. Reminders have no background/offline alert transport yet.
Calendar apps own appointment alerts. There is no automatic context injection;
Hermes explicitly retrieves the bounded tool summary. Full routine result bodies,
advanced delivery editing, automatic phone writes and Phase 10 delivery are deferred.

# Bellpull / Hermes API contract

## Phase 5 voice boundary — 2026-10-11

Android 0.5.3 also uses the existing chat Stop path after confirmed non-echo speech
interrupts a reply. It waits for the original run/history to settle before admitting
one replacement turn. Voice brevity/minimal-thinking/tool-purpose requests are
visible prompt text; no provider reasoning settings or API fields are changed.
Tool/thinking/search announcements derive from the existing live event transcript;
they do not introduce a tool-execution playback acknowledgment or gate.

Android 0.5.0 uses the existing run/history/stop/approval contracts for voice text.
No Hermes audio endpoint is assumed, and no plugin feature/version is added.
Microphone WAVs go to a separately configured private transcription endpoint with
separate credentials and no redirects; JSON `text` becomes the existing user input.
A visible voice preset/playback note can accompany that input. Native installed
TTS handles answer playback on the phone. Per-tool playback acknowledgment/gating
is **not** a verified host capability. See [setup and limitations](PHASE-5-VOICE.md).

## Repair branch contract — 2026-10-10

This section defines the current single-plugin contract. The Phase 2/3 sections
below describe the still-supported legacy overlay deployment and its recorded
production probes. Their legacy storage/discovery statements do not override this
section. Native Hermes endpoints retain their upstream behavior.
Installation/migration/rollback: `plugins/bellpull/README.md`. Generic host changes:
`server-host/README.md`. Current acceptance evidence: `docs/HANDOFF.md`.

Every plugin route requires a configured native bearer key, native profile scope,
and the ordinary API origin. `/p/{profile}` mirrors use Hermes' existing middleware;
unknown/unconfigured profiles fail closed. No new credential or cross-profile
ownership bypass exists. Bellpull plugin 0.2.1 provides portable files and GPU
telemetry on stock Hermes through the native handler factory, authentication and
session lookup helpers. Tests cover original v2026.9.7 and a fresh current
non-Docker checkout (`d4d2ab7b5d104166e7e22a02cafd12f1fe456a31`). Named
profile access exists only when native discovery actually serves those profiles;
`profile_routes: []` does not imply an available `/p/<name>` route.

Private branch/snapshot and optional legacy run extensions still check reviewed
native source hashes. File delivery does not depend on those hashes or on the
host extension. Plugin unload closes wake publishing,
open broker subscriptions and paging snapshots. Frozen plugin routes and new
Bellpull preparations return 503 until gateway restart; native admitted work can
finish without losing its file-collection callbacks.

### Versioned discovery

GET `/api/bellpull/capabilities` returns:

```json
{"version":1,"plugin_version":"0.2.1","hermes_version":"0.21.1","host_api_version":null,"features":{"session_fork_at_message":true,"session_fork_before_message":true,"bellpull_persistent_artifacts":true,"bellpull_model_controls":false,"bellpull_run_attachments":false,"bellpull_reasoning_streaming":true,"bellpull_snapshot_paging":true,"bellpull_artifact_lifecycle":true,"bellpull_notification_events":false},"note":"Persistent file sharing is plugin-owned. Native image inputs and model overrides require the optional run extension."}
```

Android first reads native `/v1/capabilities`, preserving structured native run
idempotency/retention fields, then merges supported plugin flags. A missing plugin
falls back to the legacy/native contract. Only input attachments, model overrides
and run-observer notifications depend on host API v1; file delivery/lifecycle
remain available on stock Hermes. Notification events are false when ntfy is disabled. No native static
feature dictionary is changed by the plugin. Refresh updates action availability.

The native plugin tool `bellpull_share_files` accepts `{action:"prepare"}` or
`{action:"publish"}`. Native dispatch supplies the current session ID. Prepare
returns a profile/session-private outbox; publish safely copies its regular
outputs into persistent records, returning `artifacts` and `warnings`. This
replaces core-patch output collection as the portable stock-server delivery path.
PUT uploads use chunked streaming, bounded at 25 MiB by the plugin; Hermes' native
JSON Content-Length limit remains unchanged.

Android resumes a dropped durable stream with native `Last-Event-ID` using the
last applied `seq`, suppresses duplicate events, and refuses to concatenate a
gapped or `replay.truncated` tail. Complete replay can restore live text after app
restart. Unsequenced/unsupported legacy streams fall back to status and history.
Terminal status does not settle a successful submission until its final saved
assistant row is visible. A truncated legacy session stream also polls saved
history without resubmitting, so reselecting the conversation is unnecessary.

**Stock SSE framing, verified on the Fold 2026-10-10:** the current unmodified
native run endpoint sends `id:` and `data:` lines without an `event:` line:

```text
id: 0
data: {"event":"reasoning.delta","run_id":"run_fixture","timestamp":1791709177.29,"text":"First live update.","seq":0}

```

Android 0.3.6 reads the event name from the JSON envelope when the optional SSE
name is absent. Explicit `event:` names on legacy streams take precedence. This
applies to every native run event, including prose, tools, approvals, terminal
events and replay-gap notices. JSON `seq` remains the replay cursor; `id:` alone
does not authorize joining an unsequenced suffix. Older clients that required
`event:` silently discarded these otherwise valid native frames.

Plugin 0.2.1 bridges the native agent's `reasoning_callback` into the existing
`/v1/runs/{id}/events` transport as `reasoning.delta` with `{event,run_id,timestamp,text}`.
Hermes assigns the ordinary native sequence number and retains/replays these frames.
`bellpull_reasoning_streaming` is true only when this adapter exposes the checked
callback parameters and run-stream map (older factories can use their native
agent's existing `reasoning_callback` attribute). Two adapter-instance callback wrappers,
installed by the platform-handler factory, connect the missing reasoning callback;
they do not edit core files or replace run admission/execution/SSE endpoints.
Existing explicit reasoning callbacks remain intact. The native 500-character
`reasoning.available` preview is suppressed only when real reasoning deltas arrived
for that response. Unsupported adapters retain their native preview behavior.
Unload/cleanup removes the wrappers and fences late reasoning callbacks.

Android renders `reasoning.delta` as thinking, and `message.interim` /
`assistant.commentary` as prose only when `already_streamed` is false. Unknown events
still advance the replay cursor. Local real-adapter tests on the current stock
checkout verify reasoning longer than 500 characters arrives before completion,
without duplicate previews, inference or core file changes. Providers must actually
supply reasoning; this bridge cannot manufacture tokens from a non-streaming provider.

### Stable paging and source visibility

| Method/path | Response and query |
| --- | --- |
| GET `/api/bellpull/sessions` | `source` optional, `limit` 1–200 (default 200), opaque `cursor` optional. Native session rows in `{object:"list",data,limit,offset,has_more,next_cursor}` |
| GET `/api/bellpull/sessions/{id}/messages` | `limit` 1–500 (default 500), opaque `cursor` optional. Resolved-lineage rows in `{object:"list",session_id,data,pagination:{limit,offset,returned,order:"oldest",next_cursor,total}}` |

The first request freezes a bounded snapshot. New activity/compression cannot shift
subsequent page boundaries. Cursors are bound to authenticated key/profile, resource,
source and original session; cross-scope use returns 404. Snapshots expire after
600 seconds (410), including gateway restart or cache eviction. Eight snapshots,
32 MiB combined and 100,000 rows per snapshot are the explicit bounds. Oversized
histories fail visibly with 413; they are not labeled complete. Android deduplicates
IDs, detects repeated/missing cursors and checks message totals.

Without snapshot support, Android requests native offset pages (200 sessions,
500 oldest messages), checks the conversation head for moving recency ordering,
and retries when compression changes the resolved session ID. Continuously moving
legacy history fails visibly rather than claiming a complete result. A newer refresh
wins over older in-flight requests. Loading complete messages retains canonical IDs
and the transcript's existing scroll/follow behavior.

The drawer defaults to **All conversations**: no `source` query is sent, so API,
CLI, TUI and other platform conversations from the authenticated profile appear.
The former API-only default is migrated once on upgrade to Android 0.3.5. Explicit
new source choices persist per Android server profile. This does not import history
or cross native profile boundaries. Nested agent sessions remain omitted by native
`include_children=false` behavior.
On the production default data home, a read-only 2026-10-10 check counted 47 API,
7 CLI and 1 TUI conversations with 545 messages. The former API-only filter excluded
the CLI/TUI rows; no evidence of missing database data was found. Source choice and
paging are separate controls. Global search explicitly searches loaded titles and
previews; chat search and View prompts use the completely loaded saved transcript,
not a semantic search tool or the model's full effective context.

### Phase 4 conversation projects (plugin-owned, 0.4.0 development)

The user authorized a new Bellpull plugin contract for conversation folders.
These are **not native Hermes endpoints** or repository/workspace projects.
Authenticated discovery advertises `bellpull_projects: true` only when the native
auth, SessionDB and session-creation helpers required by this implementation exist.
Existing `/p/{profile}` middleware scopes all requests to that profile's actual home.

| Method/path | Contract |
| --- | --- |
| GET `/api/bellpull/projects` | Complete no-store snapshot: `version:1`, integer `revision`, `projects`, `memberships`. Project rows: `id`, `name`, `description`, nullable `color`/`icon`, boolean `archived`, Unix `created_at`/`updated_at`. Membership rows: canonical `session_id`, compression `session_ids`, `project_id`, boolean `pinned`. Explicit Unfiled records have null `project_id`. |
| POST `/api/bellpull/projects` | Mutation: `operation_id` (32 lowercase hex), `expected_revision`, `operation` (`create`, `update`, `delete`, `move`, `pin`), and the operation's fields below. Returns the current complete snapshot. HTTP 409 requires refresh; no client merge. Repeating the same retained operation/fingerprint is safe; changing its body conflicts. |
| POST `/api/bellpull/projects/{project_id}/sessions` | Create a native empty chat in this folder. Body: caller-chosen `id`, `expected_revision`, optional native `title`, `model`, `provider`, `model_options`, `require_model_lock`. Membership intent is persisted before invoking the native create helper. Retry the identical ID/body after an uncertain response; an existing owned result returns the same session. Never starts inference. Returns the ordinary `{session:...}` envelope. |

`create` supplies client-chosen project `project_id`, `name`, optional `description`,
`color` and `icon`. `update` replaces those display fields and `archived` for an
existing ID. `delete` removes only the folder, returning members to Unfiled.
`move` supplies `session_id` and nullable `project_id`; moving resets its pin.
`pin` supplies `session_id` and boolean `pinned`, requiring an existing project.
Names are 1–120 characters, descriptions at most 1000 characters, colors optional
`#RRGGBB`, and icons optional `folder`, `code`, `home`, `craft`, `book`, or `star`.
Archived projects cannot receive new/moved chats until restored. No operation
deletes native sessions/messages or alters model context.

Each logical conversation has at most one membership. Verified compression aliases
share it; Bellpull branches and native forks with an explicit `_branched_from`
marker receive a one-time inherited membership and remain independently movable.
Older external native forks without a marker need an explicit move. Parent/child
ancestry alone does not confer membership. Explicit Unfiled decisions cannot be
undone by inheritance.
Mutation receipts retain the latest 1024 operations; older stale retries fail the
revision check instead of duplicating creation. Metadata resides in
`<profile-home>/bellpull-projects/<principal>/metadata.sqlite`; back it up with the
stable principal identity. Native `state.db` is never extended with Bellpull tables.

Phone caches are encrypted and profile-scoped. Server snapshots replace cached
organization. Cached reading is allowed offline; writes require a fresh successful
sync and no mutation queue is maintained. Archived chats remain in All conversations
and global search. Search still covers loaded titles/previews, not semantic search.

### Persistent file lifecycle

Existing Phase 3 model/upload/download paths and run-body format below remain valid.
GET `/api/bellpull/artifacts` additionally returns `warnings` and `storage`.
Warnings contain `request_id`, `name`, `message`, and Unix `created_at`; storage
contains `used_bytes`, `quota_bytes`, and retention/housekeeping policy fields.

| Method/path | Contract |
| --- | --- |
| GET `/api/bellpull/artifacts/storage` | Usage and explicit retention policy, authenticated/no-store |
| DELETE `/api/bellpull/artifacts/{id}` | Idempotent tombstone plus byte removal; `{deleted:true}`. Unknown ID 404. Does not erase saved transcript references |

Artifact rows add `deleted`, `session_ids` and `message_ids` (map of branch session
ID to its copied message ID). Canonical IDs/content are immutable. Branch/edit
links only proven copied-prefix messages, including nested branches; unassociated
legacy records remain downloadable from the source. Deleting an artifact affects
all references and cannot be undone by replaying its old upload ID.

The 0.3.7 audit repair expands `session_ids` across verified compression
continuations within the current native profile, in both directions. A compression
edge requires an ended parent with `end_reason: compression`, a non-tool child,
and no branch/delegate/reset marker pointing to that parent. Inherited markers
pointing elsewhere do not block a continuation. Cycles, ambiguous children and
lineages exceeding 1000 rows fail closed. This does not invent message IDs or
change the exact-prefix rules for branch links.

Stock `bellpull_share_files` publication retires unchanged plugin-owned staging
copies after durable immutable storage. Failures remain staged. Its additive
result fields are `status` (`published`, `partial`, `empty`), `published_count`
and `remaining_count`; partial results include warnings and request another publish.
Collection rotates bounded batches so old failures cannot indefinitely starve newer
names. Retrying an interrupted commit deduplicates the original artifact identity;
an empty retry does not imply that previously published files were deleted.

The stable profile owner comes from `$HERMES_HOME/bellpull/principal-id`, independent
of bearer rotation. Legacy key-derived namespaces require an explicit offline
migration; they are never automatically merged. Persistent files stay until deletion,
with a 512 MiB quota. Generated scratch is per run, collection is bounded to 1000
entries with visible partial/quota/unsafe-file warnings, and separate runs retain
separate message associations even for identical output. Orphans/interrupted uploads
older than one day and completed scratch older than seven days are cleaned during
artifact access, at most hourly per active profile. Unfinished scratch is retained.
Android keeps drafts, removes old unreferenced private files, and removes old
download/share copies without deleting server content.

### Native runs and approvals

Host API v1 preparation preserves the original native body fingerprint, authenticates
before plugin use, and resolves a replay before checking current catalog, files or
concurrency. Preparation is read-only/idempotent; start/finish effects run only for
the native atomic-admission winner. Plain user text is persisted separately from
multimodal execution input. Model locks and provider reasoning options apply per
session/request, without global model substitution.

`interactive_approvals` is an explicit Boolean per native run, false by default.
Only that worker context opts into the already-verified native approval machinery;
parallel/default clients remain unattended. Context resets on completion/failure.
Approval decisions still require the native owner and exact current request ID;
replays/stale/cross-run decisions fail. Status hooks emit narrow scoped wake hints
for terminal, interrupted and waiting-for-approval transitions. ntfy is advisory;
canonical status and history remain authoritative.

Android 0.3.7 reconciles completed runs against the native
status `output`, fresh saved assistant rows and server `created_at`/message timestamps
when the prompt was compacted away. A successful history GET alone is insufficient.
Evidence and retry count are journaled across restarts. After 12 unsuccessful history
checks, automatic reconciliation stops with explicit review/retry controls. Releasing
tracking is distinct from verified synchronization and never restores an admitted
prompt to the composer. Transport failures before the first SSE frame retry the same
run subscription with exponential backoff; consumed legacy unsequenced streams retain
the polling fallback. No stream or history recovery submits a new admitted run.

Android accepts root URLs and strict native `/p/[a-z0-9][a-z0-9_-]{0,63}` prefixes,
with an optional trailing slash. Credentials, queries, fragments, escaped paths and
extra segments remain rejected; transport restrictions are unchanged. The selected
prefix stays on every request. Authenticated native capability discovery must succeed;
an unserved profile is an error, with no fallback to the root account.

Android prunes terminal, history-synced journal entries older than 30 days at restore
and after reconciliation. Outstanding, ambiguous or unsynced runs are retained.

## Bellpull Phase 3 contract — 2026-10-10

Implemented by `server-patches/bellpull-phase3`, extending the tested Phase 2
adapter. Native bearer authentication, session/profile context and existing API
origin apply. New routes fail closed without a configured API key and return
`Cache-Control: no-store`. No upstream key or public artifact URL is returned.
Capabilities: `features.bellpull_model_controls` and
`features.bellpull_persistent_artifacts`. Clients must check these before use.

| Method and path | Contract |
| --- | --- |
| GET `/api/bellpull/models` | `{version:1, default:{provider,model,model_options}, models:[{provider,model,configured,vision,reasoning:{efforts,can_disable,can_enable?,note}}], note}` |
| GET `/api/bellpull/sessions/{id}/model` | `{session_id,selection:{provider,model,model_options}}`; effective saved lock or server default |
| PUT same model path | Body is a selection; validates configured route and reasoning, saves native session model lock; same response |
| GET `/api/bellpull/artifacts` | `{data:[artifact...]}`; optional `session_id` filter |
| PUT `/api/bellpull/artifacts/{id}?session_id=...&name=...` | Raw bytes, MIME in Content-Type, URL-encoded filename. Caller supplies stable 32-character lowercase hex ID. Returns 201 artifact; exact retry returns same record, changed identity/body conflicts |
| GET `/api/bellpull/artifacts/{id}/content` | Repeatable authenticated original bytes; attachment disposition, nosniff, exact Content-Length; missing content 410 |

Artifact metadata: `id`, `session_id`, `name`, `mime`, `size` (bytes), `sha256`,
`created_at` (Unix seconds), `source` (`upload` or `agent`), nullable `request_id`
(admitted run ID) and `message_id`, plus `available`. Message-level association
is reserved; this release links files to the conversation/run. No filesystem paths
are sent to the phone. Internal storage is scoped by authenticated principal/profile.
Files persist under `$HERMES_HOME/bellpull-artifacts` with SQLite metadata, a 512 MiB
scope quota, maximum 25 MiB per file/combined turn and eight attachments per turn.
PNG/JPEG/WebP, PDF, plain/Markdown/CSV text, JSON, ZIP, OOXML and binary fallback
are accepted. Empty, oversized, unsafe names/IDs and cross-session files are rejected.
Only the artifact PUT path gets the larger body limit; ordinary JSON remains bounded.

Bellpull adds this immutable field to the existing durable admission body:

```json
{"bellpull":{"version":1,"selection":{"provider":"configured-provider","model":"configured-model","model_options":{"reasoning":{"enabled":false}}},"artifact_ids":["0123456789abcdef0123456789abcdef"]}}
```

Omit `reasoning` for server defaults; `enabled:false` means genuine Off, never minimum
effort. Supported effort requests use `{enabled:true,effort:"..."}`. The exact
selection/IDs join the existing idempotency fingerprint and Android persistent journal.
An accepted replay resolves before fresh model/file discovery. Locked durable runs
now honor the session selection and disable fallback; old unmodified clients remain
compatible. Changing one session or a profile's new-chat default does not edit global
Hermes configuration. Settings defaults are local to the Android server profile.

The catalog reads native Hermes inventory and model capability metadata. A configured
provider does not guarantee every catalog model will accept a request; errors remain
visible. Images require confirmed native vision metadata and enter the selected
model's multimodal request; the persisted user text is separate from base64 content.
Documents retain original bytes and use existing bounded `read_file` extraction.
Every Bellpull run gets a scoped output directory; regular files written there become
the same authenticated artifact records. Arbitrary paths/URLs mentioned by an agent
do not become downloadable. Generated output is collected after normal agent return;
an interrupted process may leave unpublished files until a later successful turn.

Verified reasoning translations: Fireworks DeepSeek V4/V4.1 low/high/max/Off maps
to top-level `reasoning_effort` (`none` for Off); GPT-OSS has low/medium/high and no
Off. The exact custom model `qwen38-flash-next` supports On/Off via
`extra_body.chat_template_kwargs.enable_thinking`. A direct local probe gave 0 versus
23 reasoning tokens for the same prompt. No graded local effort was verified. This
patch does not configure a local route. Other models use their actual provider's
declared supported values; unknown controls remain unavailable.

The separate `plugins/bellpull_gpu` Hermes plugin registers authenticated GET
`/api/bellpull/plugins/gpu` (and the native `/p/{profile}` prefix). Response is
`{version:1,checked_at,refresh_seconds:15,cards:[...],errors:[...],scale_note}`.
Cards carry stable UUID/PCI identity, name/vendor/bus, sample time/stale and typed
sensors with Celsius or null plus fixed display ranges. Local `amd-smi`/`nvidia-smi`
commands run on demand with a shared 15-second cache, bounded timeout and no model
turn. The phone polls only while Tools is visible and foreground. Missing tools/cards
are unavailable, never fabricated four-card data. See the plugin README for commands
and scale semantics. The user explicitly superseded the original Moe/SSH requirement:
telemetry belongs on the Hermes plugin host.

Android copies granted content-provider streams into profile-private durable drafts,
offers retry/cancel, renders native image/PDF previews, and shares only selected copies
via temporary FileProvider read grants. Share-in creates drafts only. HTML has no
embedded browser/bridge. Removing a profile deletes local files, not server artifacts.

## Bellpull Phase 2 contract — 2026-10-09

Live admission probe: POST `/v1/runs` with `input`, `session_id`, stable `Idempotency-Key`
and `X-Hermes-Session-Key: hermes-android` returns 202 `{run_id,status,replayed}`.
Repeating the exact body/header identity returns the same run with `replayed:true`.
The probe completed and saved its user/assistant messages to the requested session.
Status includes `output`, `error`, `approval` when applicable; `interrupted` is terminal.
Durable SSE uses `message.delta` (`delta`), `tool.started/completed` (`tool`, `preview`),
`reasoning.available` (`text`), and terminal `run.completed/failed/cancelled`. Unlike the
legacy transport, it has no sequence or tool-call IDs. After any gap, poll status/history;
do not concatenate an unknown stream suffix or create another run. Retry retention is
86,400 seconds; clients stop retries before expiry or when their clock moves backward.

Inspection uncovered a capability/policy mismatch: `/v1/runs` had an approval callback,
but `approval_context.py` still classified all API runs as unattended. The isolated
`~/hermes-agent/patches/bellpull-phase2/` patch adds explicit boolean request field
`interactive_approvals`, advertised by `features.run_interactive_approvals`. It binds a
context variable only during that admitted run. Legacy/default requests retain their
policy. The choice is part of the persisted request fingerprint, so clients never change
it when retrying. No live approval policy/configuration is relaxed.

Approval status/event: `approval.request` includes a nonblank `request_id`, redacted
`command`, `description`, and `choices`. POST `/v1/runs/{run_id}/approval` uses
`{request_id,choice:"once"|"deny",resolve_all:false}`. HTTP 409 means stale/not pending;
reconcile status rather than targeting the next request. Isolated tests execute the real
approval gate and HTTP handlers with a harmless fixture agent: accept/deny, stale IDs,
repeated decisions, concurrent unattended policy, dedupe and opt-in payload conflicts.

The patch also advertises `features.bellpull_notification_events` and adds authenticated
GET `/api/bellpull/events`. It proxies a scope-specific loopback ntfy subscription through
the existing API origin. SSE `bellpull.wake` contains only `run_id`, `session_id`, `status`.
The phone verifies hints against authenticated canonical status/history. Broker messages
contain no credentials, prose, tool arguments or approval commands. No new public port;
ntfy is still `127.0.0.1:2586`. Scope topics use a full SHA-256 digest as
unpadded URL-safe base64 plus prefix (52 characters; ntfy permits at most 64). Cached hints may repeat; they are not transcript authority.
Application read receipts and notification identities deduplicate delivery. If a hint is
missed, reconnect/periodic history synchronization and the run journal provide catch-up.

Activation is hash guarded and refuses to restart with active agent/background work.
`activate.py apply|rollback|check`; derived image `hermes-agent:bellpull-phase2-20261009`.
See HANDOFF.md for final deployment and device evidence; source/contract tests alone do
not establish a live deployment or locked-screen notification result.

## Bellpull discovery and prompt editing — 2026-10-09

This update supersedes earlier transport-specific assumptions where stated.
The installed gateway reports version `0.21.1` from `hermes_cli.__version__`, image
`nousresearch/hermes-agent:v2026.9.7`; no Git metadata is included in the container.
Existing host remains PowermacG3. Model options report provider `auto` and model
`accounts/fireworks/models/deepseek-v4p1-flash`; `/v1/models` advertises only the
`hermes-agent` alias. No local Qwen/Moe route has been verified or substituted.

`/v1/runs` exposes actual asynchronous admission (`input`, `session_id`, optional
model/options), scoped `Idempotency-Key`, pollable status, stop, steering and genuine
approval requests. Capabilities report durable idempotency with 86,400-second
retention. Installed source `api_server_runs.py` confirms independent executor tasks,
status persistence for idempotent runs, and a dead-owner status of `interrupted` after
restart. `/events` is a consuming in-memory queue which is dropped on disconnect;
there is no event-ID replay contract. Do not treat socket loss as run failure or
resubmit with a new key. Phase 2 now uses these paths for Android sends when durable
idempotency is advertised; see the contract update above.
The old Gate 0c approval limitation remains true for the legacy session stream.

Native memory-write, audio, realtime voice and jobs-admin capabilities are false.
Model options/locks and job routes exist but need scoped behavior probes. Browser
artifact upload/download is gated/scoped, 10 MiB maximum, 300-second TTL; it is not
a suitable persistent user attachment repository. Moe telemetry/voice services and
coding runner interfaces remain unknown pending host access.

Added authenticated `POST /api/sessions/{id}/fork-before-message`, guarded by
`features.session_fork_before_message`. JSON: `{"before_message_id": <positive user message ID>}`.
Only an active user message in the resolved source is valid. It clones the exact
preceding prefix (empty for the first prompt), model config/lock, system prompt and
lineage without modifying the source. It invokes no model/tool. Android opens the
edited text as a draft in the branch; the user presses Send separately. Older servers
leave editing unavailable and never fall back to the legacy whole-session fork.

Patch/build/rollback: `~/hermes-agent/patches/bellpull-edit-fork/`, built image
`hermes-agent:bellpull-edit-fork-20261009`. Sixteen isolated HTTP/SQLite tests pass,
including first/later user prompt, invalid/foreign cutoff, auth, source preservation,
model/prompt retention and the previous assistant-cutoff tests. Activated only after
zero active work and source hash checks. Module changes survive restart; container
recreation must use the derived image. Live device edit created a 0-message branch
from the first prompt; authenticated reads confirmed the source session/messages
unchanged. No inference was used for this test.

# Historical upstream API investigation

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
the live behavior of `/fork`, the non-streaming `/chat`, and every `/v1/runs/*` operation. Verify before
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

### Fork limitation — installed source inspected 2026-10-08

Read-only inspection of `/opt/hermes/gateway/platforms/api_server.py`,
`_handle_fork_session`, in the running `hermes-gateway` container
(`nousresearch/hermes-agent:v2026.9.7`) confirmed:

- `POST /api/sessions/{id}/fork` reads optional `id`/`session_id` and `title` fields.
  There is **no selected-message cutoff field**.
- It ends the source session with reason `branched`, creates a child session with the
  source model/system prompt and `parent_session_id`, and copies **all** source messages.
- It returns HTTP 201 with `{object: "hermes.session", session: {...}}`.

This is source verification, not a live mutation test; no fork request was sent. It does
not provide ChatGPT-style branching at a selected reply while preserving the original.
That behavior needs an explicit server extension and verified contract. Do not send a
guessed cutoff field: the current handler would ignore it and copy the entire history.
The installed database's normal list filter retains children carrying its native
`model_config._branched_from` marker even with `include_children=false`. A selected-reply
implementation must set that marker; it also prevents resume resolution from following
the original into its branch.

### Selected-reply fork — activated and verified 2026-10-08

The isolated, tested patch at
`/home/praxisog/hermes-agent/patches/selected-reply-fork/` introduces:

| Method | Path | Request and behavior |
|---|---|---|
| `POST` | `/api/sessions/{id}/fork-at-message` | `{"through_message_id": 123}`; inclusive saved assistant message, preserve original |

It advertises `features.session_fork_at_message: true`. The app requires that flag. The
separate endpoint makes older servers return 404, rather than ignoring a cutoff and
performing their existing destructive full fork if a cached capability becomes stale.
There is no fallback to `/fork`.

Success returns HTTP 201 with the normal `{object: "hermes.session", session: {...}}`
envelope. The source is resolved as for the history endpoint; only its active saved prefix
through the selected assistant message is copied. Model configuration and system prompt
are preserved. Copied messages receive the branch creation timestamp so the new chat
appears first in the recent list; original message timestamps are unchanged.
Pending tool calls on the selected message are omitted because their
results are outside that prefix. Creation/copy/count updates are atomic; invalid or
foreign cutoffs fail with 400, missing sources with 404, duplicate IDs with 409, and title
conflicts with 400. Failures leave the original and partial destination unchanged/absent.
No inference or tools run during creation. The old `/fork` behavior remains unchanged.

Verified with 12 HTTP/database tests in an isolated container using temporary data and no
network access, including auth, rollback, concurrency, compressed-session resolution,
model/system-prompt preservation, visibility, recent-list sorting, and tool history.
The user explicitly authorized activation; the gateway now advertises the capability.
A real phone branch at the first of two replies copied only the first exchange and left
the original's four messages and metadata unchanged. See the patch's `README.md` and
`activate.sh` for deployment and rollback details. The patch is installed in the current
container; use the prepared image when recreating it.

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
| `GET` | `/v1/runs/{run_id}/events` | Consuming live SSE queue, not replay; Phase 2 verified, status/history recover gaps |
| `POST` | `/v1/runs/{run_id}/stop` | **Verified 2026-09-16 — works, but does *not* settle as `cancelled`. See Gate 0d.** |
| `POST` | `/v1/runs/{run_id}/steer` | Observed in live capabilities 2026-09-16 (`run_steer: true`). Not used by Tier 1; noted so it isn't mistaken for invented later. |
| `POST` | `/v1/runs/{run_id}/approval` | Phase 2 explicit interactive durable runs only; legacy limitation recorded below |

### Discovery

`GET /v1/capabilities`, `GET /v1/skills`, `GET /v1/toolsets`, `GET /health`,
`GET /health/detailed`, `GET /api/model/options`.

## Things that will bite

- **Approvals cannot reach this client, by design.** *Settled by Gate 0c, 2026-09-16 —
  this bullet previously said the opposite.* `/v1/capabilities` advertises
  `approval_events: true` and `run_approval_response: true`, and
  `POST /v1/runs/{run_id}/approval` exists. Before the explicit Phase 2 opt-in patch, this was unreachable because the
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
