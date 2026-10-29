> Current experimental pair: **Android 0.15.0 / build 43**, plugin **0.7.0-integration-preview**.
> Includes authenticated phone snapshots, bounded context for new API chats and a read-only context tool.
> Phone context details: authenticated phone snapshots, bounded context
> for new native API chats, and a read-only phone-context tool. Uploads never start
> chats or runs. See [Phase 15 protocol, consent, expiry and limits](../../docs/PHASE-15-ASSISTANT.md#phone-context-and-notifications-folder).
> Deploy this plugin alongside app 0.15.0 to enable the new controls; older app
> grants do not silently opt into server caching. No live server has been updated.

> Previous plugin increment: **Android 0.14.9 / build 41**, plugin
> **0.6.2-integration-preview**. Fixes #27: the verified named route `moe/moe-local`
> advertises Low/Medium/XHigh and Off/On; unsupported choices are rejected before
> saving. An older invalid lock reports how to reset Thinking level to Default.
> Update the complete plugin, restart the Hermes gateway when idle and reopen
> Bellpull to refresh the catalog. The existing 0.14.9 APK works unchanged.
> [Download and install](../../docs/RELEASE-PLUGIN-0.6.2-EXPERIMENTAL.md) ·
> [Evidence](../../docs/evidence/moe-reasoning-20261022/README.md).

# Bellpull integration plugin 0.7.0-integration-preview

This single plugin includes OpenCode activity, reminders/native Hermes routines,
opt-in phone calendar context/proposals, and the existing chat/files/projects features.
Pair it with Android **0.15.0-experimental** (build 43) from
`android-v0.15.0-experimental.1`. The separate Phase 6/8/9 preview plugin directories are
superseded by this combined package; do not overlay individual module copies.

Install the entire `plugins/bellpull/` directory using the stock-plugin procedure
below, restart Hermes, and refresh authenticated capabilities in Bellpull. No core
replacement, Docker, second scheduler or separate OpenCode server is required.
`bellpull_coding_activity` requires modern identity-bearing hooks;
`bellpull_planning` requires the supported API handler surface;
`bellpull_routines` additionally requires the per-profile native cron store.
An older host can advertise only the features it supports. Calendar writes still
require an explicit phone action, with sharing/writes off by default.

Back up each native profile home and the old plugin. New coding/planning metadata
is additive under `bellpull-coding/` and `bellpull-planning/`. Restore the whole old
plugin and restart for rollback; stored chat/session schemas are unchanged.
See [combined integration evidence and acceptance](../../docs/INTEGRATION-6-8-9.md),
[OpenCode scope](../../docs/PHASE-6-OPENCODE.md) and
[planning scope](../../docs/PHASE-8-9-REMINDERS-CALENDAR.md).

## Experimental release limits

The [October 14 audit](../../docs/AUDIT-2026-10-14.md) is a historical baseline.
Current source includes the workspace-filter/hard-link, calendar-generation and
proposal-retention repairs, plus 0.4.2's receipt scrubbing and concurrent-proposal
revocation check. The [October 15 audit](../../docs/EXPERIMENTAL-ISSUE-REPAIRS.md#october-15-re-audit)
records remaining app/device acceptance. Read the current
[release record](../../docs/release.md) before deployment.

## Existing stock features and installation

One Hermes plugin owns Bellpull's branch/edit endpoints, model controls,
persistent artifacts, conversation projects, notification relay and optional GPU telemetry. It registers
through `ctx.register_platform_handler("api_server", factory)` before the native
router freezes. It does not edit Hermes core files or mutate native feature flags.
For live thinking, two feature-detected adapter-instance callback wrappers connect
the native reasoning callback to the native run stream. No run engine or endpoint
is replaced; unsupported adapters retain their native previews.
The native run engine retains authentication, original request fingerprints,
atomic admission, concurrency, cancellation, replay and exact-request approvals.

## Stock Hermes installation

File publishing, authenticated downloads, storage management and GPU telemetry
work through the native `register_platform_handler` plugin surface. They require
**no Docker access, core replacements, pinned source hashes or hardcoded URL**.
Use Bellpull Android 0.3.7 or newer for the complete audit repair. The earlier Phase 4 pair was Android 0.4.0/plugin 0.3.0. This integration package
includes those project and file features; use the matching integration checkout or
plugin archive from the
[current development release](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.8.4-integration-preview.1).
Install on your server yourself:

```bash
# From the matching unpacked integration plugin bundle or repository checkout:
# Source branch: master; use the complete matching release bundle.
python3 tools/install-plugin.py --home /YOUR/HERMES/DATA/HOME
python3 tools/install-plugin.py --home /YOUR/HERMES/DATA/HOME --apply
HERMES_HOME=/YOUR/HERMES/DATA/HOME hermes plugins disable bellpull_gpu
HERMES_HOME=/YOUR/HERMES/DATA/HOME hermes plugins enable bellpull
```

The installer previews by default and backs up an existing plugin outside the
plugin discovery directory before copying. It does not edit core files or config.
Enable `bellpull` through your existing Hermes plugin manager, then restart your
gateway using its existing service manager. Keep the API server's existing bearer
authentication. Do not install multiple plugins providing the same Bellpull routes.
If you explicitly restrict API toolsets, include `bellpull` in that allowlist so
the agent can call the sharing tool. No provider or model configuration changes
are required for sharing outputs.

`--home` is the **data home**, usually `~/.hermes` on a stock install, not the
Hermes source checkout. Pass the real home used by your service/profile. The
plugin is installed under `<home>/plugins/bellpull`. The gateway imports it from
there after updates; no `docker cp` or patch reapplication is needed.

The `bellpull_share_files` model tool has two actions. `prepare` returns this
conversation's private outbox; write/copy the requested PNGs or documents there
with existing Hermes tools. `publish` creates immutable persistent artifact
records and reports any collection failures. The phone reads the records from
the normal bearer-protected plugin API. Session identity comes from native tool
dispatch, not model-supplied arguments. MEDIA tags and browser-scoped temporary
upload links are not the delivery contract. Existing files can be copied into
the outbox and published without generating them again.

The 0.3.7 audit repair retires only unchanged outbox copies that were
successfully stored. Failed files remain for recovery. `publish` reports partial
publication and the staged count; repeat it until the outbox is drained. A lost
response can be retried without duplicate artifacts. Previously published artifacts
remain available after the outbox is empty, until explicitly deleted in Bellpull.
Existing retained outboxes are drained by subsequent publish calls (1000 entries
per call). Other user directories are never cleaned by this tool.

Files follow verified compression continuations in the same native profile,
including files published after the phone's conversation ID became stale. Ordinary
branches inherit only files with proven copied-prefix message associations. No byte
or artifact-ID migration is required. See `docs/AUDIT-0.3.6-REPAIR.md` for the audit
results and manual acceptance checklist.

Acceptance after restarting: plugin capabilities report
`bellpull_persistent_artifacts: true` and `bellpull_artifact_lifecycle: true`;
publish four PNGs with the tool; open Tools in Bellpull, refresh, and preview or
download them. Downloads must match the original byte count and SHA-256. Reconnect
the app to refresh feature discovery. Missing sharing support is now visible.

Native `profile_routes: []` means this listener serves no named profile prefixes.
Use its ordinary root URL. The plugin never invents `/p/<name>` access; Hermes'
native middleware rejects unknown/unserved profiles before plugin handlers run.
The repaired Android URL validator accepts a served `/p/work` prefix and preserves
it for authenticated discovery and subsequent requests. A syntactically valid URL
does not enable a profile on the server; use that native profile's own key.

File downloads are independent of model controls and chat input attachments.
Stock installs advertise `bellpull_run_attachments: false` unless the optional
legacy run extension is present. The app explains this and preserves attachment
drafts instead of silently sending a text-only request. Generated files remain
downloadable. The ordinary native chat/run engine is unchanged.

Before upgrading, back up the existing plugin and the profile data home,
including `bellpull/principal-id`, `bellpull-artifacts`, and `bellpull-projects`. Install the replacement
package and restart normally. Rollback restores the previous plugin directory;
the installer prints its backup location. No core file rollback is involved for
stock installs.

## Conversation projects (Android 0.4.0)

Authenticated discovery reports `plugin_version: "0.4.1-integration-preview"` and
`bellpull_projects: true` when the native auth/session helpers are supported.
Projects use the plugin's `/api/bellpull/projects` routes under the selected
native profile prefix. No core patch, model tool, inference or native schema
change is involved. Settings → About shows the installed app version and the
last reported plugin version of each saved server; compatibility is compared
with the app's minimum, not with the latest GitHub release.

Each logical conversation belongs to at most one folder. Move/Unfiled changes
organization without changing history. Plugin 0.4.1 advertises
`bellpull_conversation_pins`: any conversation, including Unfiled, can be pinned.
Its pin appears in All and applicable filters and survives moves/project deletion.
Archive hides
the folder from normal project navigation but retains its chats in All
conversations and global search. Delete project returns its chats to Unfiled.
A short manually edited description is display-only and is never added to model
context. Search reuses the existing loaded titles/previews with an explicit scope.

Authoritative SQLite metadata lives in
`<profile-home>/bellpull-projects/<principal-scope>/metadata.sqlite` (0600).
Back up this directory together with `bellpull/principal-id` and the native
session database while writers are stopped. Different native profiles and server
identities remain isolated. A future desktop Bellpull client must implement this
contract to share the organization; native Hermes clients do not automatically
read Bellpull's project metadata.

The phone caches complete snapshots in its existing encrypted per-profile store.
Cached folders remain readable offline; edits require a successful refresh.
Concurrent edits return a revision conflict and refresh authoritative state.
Project-chat creation journals one caller-selected session ID before calling the
native create route; uncertain delivery can be retried after an app restart with
the same identity. It never launches inference to recover creation.

Compression continuations retain membership/pins through verified lineage.
Bellpull's selected-message branches inherit membership once, without inheriting
the pin or broadening the exact-prefix artifact rules. Current native forks with
an explicit `_branched_from` marker also reconcile. Older external native forks
without that marker require an explicit move; an arbitrary parent/child relation
does not confer membership. Private selected-message branching remains gated on
the already supported Hermes revisions.

Rolling back to plugin 0.2.2 leaves the new metadata on disk, while older apps and
plugins do not display it. No existing session/artifact migration is required.
Keep the metadata backup if restoring a previous plugin directory.

## Live thinking and conversation visibility

Android 0.3.5 and newer default to **All conversations**, including CLI, TUI and other
platform chats from the same native Hermes profile. Existing API-only defaults
are reset once; optional source filters remain available. No plugin is needed
for listing these native saved conversations.

For token-by-token thinking, update this plugin too and restart the gateway.
`bellpull_reasoning_streaming: true` means the installed adapter accepts the
native reasoning callback (or exposes it on the native agent). The plugin forwards full `reasoning.delta` events
through Hermes' existing authenticated run stream and replay buffer; it suppresses
the later truncated preview when those deltas already arrived. Existing explicit
reasoning callbacks are preserved. Unload restores the adapter callbacks. The
model/provider still has to produce reasoning and support streaming; no synthetic
thinking is generated. See `docs/hermes-api.md` for the checked callback contract.
Android 0.3.6 also accepts stock SSE frames whose event name is inside the JSON
payload; 0.3.5 discarded those frames when the separate `event:` line was absent.

## Optional legacy host extension

| Host | Supported behavior |
| --- | --- |
| Hermes `v2026.9.7`, internal `0.21.1`, with host API v1 | Full tested plugin contract |
| Same unmodified Hermes version | File sharing/lifecycle, telemetry, branch/edit and snapshot paging; run attachments/model overrides disabled |
| Current stock Hermes | File sharing/lifecycle and telemetry through checked native auth/session helpers; private run/branch features remain gated |
| Historical four-overlay image | Keep as rollback; do not install this plugin over it (duplicate routes) |

The tested base image is pinned by digest in
[`server-host/Dockerfile`](../../server-host/Dockerfile). Host API v1 is a small
**proposed local Hermes extension**, not a feature claimed to exist upstream. It
changes three native files, with exact before/after checksums, and adds one generic
contract module. This is retained for old model/input/approval compatibility,
and is **not the installation path for portable file sharing**. Bellpull business logic stays in this plugin. See
[`server-host/README.md`](../../server-host/README.md) before upgrading Hermes.

From the repository root:

```bash
docker build -f server-host/Dockerfile -t hermes-agent:bellpull-plugin-0.1.0 .
docker run --rm --network none -e HERMES_HOME=/tmp/bellpull-tests \
  -e BELLPULL_NTFY_URL= --entrypoint python hermes-agent:bellpull-plugin-0.1.0 \
  -m unittest discover -s /opt/hermes/plugins/bellpull/tests -v
```

The image bundles the plugin in `/opt/hermes/plugins/bellpull`. Enable `bellpull`
in the intended profile's existing `config.yaml`; merge this into the current
allowlist rather than deleting unrelated plugins:

```yaml
plugins:
  enabled:
    - bellpull
```

Disable the legacy `bellpull_gpu` plugin because telemetry is included here.
For a non-Docker installation with the reviewed host API, copy this directory to
`$HERMES_HOME/plugins/bellpull` and enable it normally. Do not install a second
copy with a different name. Hermes' native profile-prefix middleware scopes
configuration, credentials and data homes. A valid profile key authorizes that
profile's artifacts; this is not a new per-chat or multi-user permission system.
Unknown profiles and missing keys fail closed.

Unloading closes wake-publishing tasks, active broker subscriptions and paging
snapshots. Existing frozen HTTP routes return 503 and new Bellpull run preparations
fail visibly. Restart the gateway to install or re-enable handlers; live router
replacement is not supported. Already admitted native runs retain their callbacks
and can finish collecting files while the gateway remains up.

Optional environment variable `BELLPULL_NTFY_URL` defaults to
`http://127.0.0.1:2586` **inside the gateway's network namespace**. Empty disables
notifications. Only loopback HTTP is accepted. Keep the existing ntfy service;
one plugin does not remove that service. Hints carry only run ID, session ID and
status; Android verifies canonical status/history. Telemetry needs only host
sensor visibility and optional NVIDIA tools; it neither executes inference nor SSH.

## Artifact ownership, migration and retention

Each Hermes data home has a random stable owner identity in
`bellpull/principal-id` (0600). Artifact namespaces derive from this identity,
not the bearer key. Preserve it in backups. Key rotation revokes the old key
while retaining downloads for the same profile. Separate profile homes remain
separate authorities; do not copy an identity between unrelated profiles.

Legacy namespaces must be migrated explicitly while **all writers to that
profile are stopped**. Back up the full data home first, including SQLite WAL/SHM
files, keys/configuration, model locks and artifacts. Select only legacy scope
hashes belonging to this profile. The tool never guesses which foreign namespace
to adopt and never prints bearer keys. Multiple explicit scopes support previous
key rotations. Example inside a disposable/offline image with the profile mounted:

```bash
python -m plugins.bellpull.migrate_artifacts --home /opt/data \
  --legacy-scope LEGACY_64_CHARACTER_SCOPE_HASH
# Review the dry-run record count, then, while all writers remain stopped:
python -m plugins.bellpull.migrate_artifacts --home /opt/data \
  --legacy-scope LEGACY_64_CHARACTER_SCOPE_HASH --apply --offline
```

Dry-run is read-only. Apply verifies content, IDs, digests, names, sizes and safe
paths in a staging tree before switching the target directory. Existing legacy
namespaces are kept intact for rollback. Reapplying identical records is safe;
identity conflicts abort. Metadata and unavailable historical references survive.
Do not run migration concurrently with the gateway, CLI or another profile writer.

Persistent files have a **512 MiB quota**, **25 MiB per-file/combined-run limit**,
and no automatic expiry. The app shows usage and explicit deletion. Deletion
tombstones the record and removes bytes across all branch references; it cannot
be undone by retrying an old upload. Original history keeps the unavailable link.
Generated files use per-run output directories, copy immutable bytes, and report
unsafe/quota/collection failures. A run collects up to 1000 outputs with an explicit
warning if more remain; this is not presented as complete collection.

Artifact access triggers housekeeping at most hourly per active profile: orphan
blobs/interrupted upload files older than one day, and completed-run scratch older
than seven days are removed. Unfinished scratch and persistent files are retained.
Collection warnings retain the latest 100 in the response and expire after 30 days
during housekeeping. Branch/edit copies
only attachment associations proven to lie in the copied message prefix; legacy
unassociated files remain accessible from the original chat and attachment browser.

## Legacy host extension upgrade, acceptance and rollback

1. Record the running image ID/digest and deployment definition; keep the existing
   final overlay image. Build/test the plugin image before a maintenance window.
2. Stop writers, take a recoverable data-home backup, and run the migration above.
3. Enable only the new Bellpull package for these features; recreate the gateway
   with the thin image using the existing mounts, UID, secrets and network settings.
   Do not copy patched modules into a running container.
4. Verify authenticated discovery, source-scoped old history, >50-chat/>500-message
   paging, existing-file downloads, new upload/generated-file flows, model lock and
   reasoning, durable replay/restart/stop, exact approvals and ntfy wake reconciliation.
   Test the phone with its existing profile and data. Use an explicitly authorized
   harmless inference test only when needed for production acceptance.
5. Retire the old deployment chain only after parity and maintainer acceptance.

Rollback: stop writers; restore the previous deployment image/config and the
pre-migration data-home backup as a unit. The migration leaves old namespaces
available, but files/runs created after migration are not automatically copied back
to the legacy key-derived namespace. Preserve the new data home separately before
rolling back so new work is not silently lost. Never delete a namespace just to
make a failed migration look clean.

This branch's automated tests use disposable databases and fake inference. They
include real plugin discovery, adapter startup/shutdown, multipart execution input,
plain-prompt persistence parameters, native reasoning transport, branches, artifact
migration/rotation, admission races, restart recovery, approvals and paging.
Live deployment and physical device acceptance are recorded separately in
[`docs/HANDOFF.md`](../../docs/HANDOFF.md).

## Offline conversation endpoints (0.5.0)

`bellpull_offline` advertises authenticated, profile-scoped memory snapshots and
atomic plain-text handoff. GET `/api/bellpull/offline/memory` respects memory enable
flags and propagates deletions. POST `/api/bellpull/offline/handoff` accepts
`operation_id` (32 lowercase hex), optional `source_id`, `title`, `brief`, and
`messages` containing only `role` (user/assistant) and `content`. The same operation
and payload returns the same session; changed payload returns 409. Source history
stays unchanged, and import executes no agent or tools. This narrow SessionDB
contract is exercised on Hermes 0.21.1 and 0.21.5 without requiring run host hooks.
See [Phase 14](../../docs/PHASE-14-OFFLINE.md) for limits and device evidence.
