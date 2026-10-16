# Phase 4 — conversation projects

User-authorized scope, 2026-10-11. Branch `feature/phase4-projects` starts at
the verified 0.3.7 commit `743dbe4`. Target Android version: 0.4.0.
This document supersedes the broader Phase 4 in `Bellpull-Implementation-Prompt.txt`.

Projects organize existing Hermes conversations. A conversation belongs to zero
or one project. The Bellpull plugin owns durable metadata; each device caches its
authenticated profile's snapshot. No model calls or context injection are involved.

Included:

- Create/rename projects, with an optional color/icon and short manual description.
- Move chats between projects or return them to Unfiled without deleting history.
- Start a chat in the selected project; branches inherit the source's project once.
- All conversations, Unfiled and individual projects; remember the selected scope.
- Opening a project shows its description/actions and conversations, without
  automatically choosing a chat. Pinned chats come first, then newest activity.
- Project unread/running indicators, pinned chats first, then recent activity.
- Existing title/preview search with explicit current-scope/all-conversations controls.
- Archive/restore projects. Archived folders leave normal project navigation;
  their chats remain in All conversations and global search.
- Delete a folder with confirmation; its chats become Unfiled. Conversation deletion
  remains a separate operation.
- Verified compression lineage preserves membership and pins across continuation IDs.
- Switching scopes preserves drafts and remote runs; it never cancels inference.
- Settings → About reads the installed app version from Android and lists cached
  plugin versions per saved server, with minimum-version compatibility status.

Excluded: saved answers, project files, model instructions, semantic/full-text search
expansion, model-accessible project tools, memory editing, history imports, task boards,
deadlines, progress percentages and an offline mutation queue.

## Authority and compatibility

Metadata lives under the actual profile data home in `bellpull-projects/<principal>/`.
Preserve it and `bellpull/principal-id` in backups. Different homes/principals never
share organization. Native profile middleware and bearer authentication remain in use.
The phone's encrypted profile cache is overwritten by server snapshots, never merged.
Cached projects can be read offline; mutations require successful discovery/sync.

Current stock Hermes has repository/workspace-oriented CLI projects, but no project
routes in the API adapter. The pinned supported Hermes does not expose that store.
Those projects can carry workspace paths and board links, which are outside this scope.
Bellpull therefore owns its narrowly defined folder metadata in its existing plugin.
It neither modifies native project stores nor invents native Hermes endpoints.

The plugin uses the same bounded compression proof introduced in 0.3.7. Ordinary
branches are distinct logical conversations: inherited membership can subsequently
change without moving the source. Explicit Unfiled decisions survive reconciliation.

## Slices and validation

1. Specify and test the plugin contract, profile isolation, revision conflicts,
   persistence, compression and branch inheritance against real SessionDB/SQLite.
2. Add typed Android transport/cache and project navigation; test draft/run preservation,
   selected scope, deletion, scoped search and safe unavailable/offline behavior.
3. Reuse conversation rows; add create/edit/move/archive controls and screenshots at
   cover/unfolded widths in light/dark modes. Run the repository's Android/plugin checks.
4. Prepare a separate deterministic stock fixture and device acceptance tests.
   Overnight implementation stopped before ADB. The user subsequently returned
   access and authorized installation, testing, fixes and pushing the branch.
   Do not access the personal server.

Primary acceptance: create a project, move several chats into it, pin one, start and
branch a chat there, restart the plugin and client, then verify all organization,
including a compressed conversation. Also test independent branches/profiles,
archive/delete semantics, failed writes and cached reading.

## Manual acceptance on stock, non-Docker Hermes

The user installs the plugin on their own server. No agent access is needed.

1. Back up the actual native profile home, including `state.db`,
   `bellpull/principal-id`, `bellpull-artifacts`, and any `bellpull-projects`.
   Preview/apply `tools/install-plugin.py --home /ACTUAL/DATA/HOME` from the matching
   candidate checkout/bundle. Enable one `bellpull` plugin and restart the usual
   gateway service. Keep its existing authentication and model configuration.
2. Connect to the correct root or served `/p/<profile>` URL with that profile's key.
   About should show app 0.4.0/build 11 and plugin 0.3.0 after reconnect. Discovery
   should advertise `bellpull_projects: true`. A missing plugin must produce a
   visible read-only/unavailable project state.
3. Create a project with a description, color and icon. Move multiple existing
   CLI/TUI/API chats into it, pin an older one, and verify pinned-first/recent order.
   Start a project chat and verify membership before sending anything.
4. Branch from a saved assistant message on a host supporting the existing branch
   contract. The branch inherits the project without inheriting the pin; move it
   to another project or Unfiled and confirm the source remains where it was.
   Hosts without selected-message branch support remain explicitly unsupported.
5. Compact a member conversation through normal Hermes use, including a
   continuation ID change. Refresh/restart both gateway and app: membership and
   pin remain. A new reply can be read without leaving a stale unread project badge.
6. Leave a draft and an admitted run, switch between projects/All/Unfiled, then
   reopen the chat. Verify the draft and run survive. Search within a project,
   then switch explicitly to All conversations and confirm the changed scope.
7. Archive/restore a folder. Its chats remain in All conversations/global search.
   Delete a folder after reviewing the confirmation: chats become Unfiled and
   saved history remains. Conversation deletion stays a separate action.
8. Disconnect networking: cached projects remain readable and edits are disabled.
   Reconnect and refresh. Edit the same metadata from another Bellpull client to
   exercise a visible revision conflict; stale state must never overwrite it.
   An uncertain new-chat response exposes Retry using the same conversation ID.
9. Switch to another saved server/native profile: folders, draft and read/run
   state remain isolated. Check About against an older/missing plugin too; the
   displayed version is last reported, not a background latest-release check.
10. On the Fold, check project overview/editor/organize/search/About at both
    cover and unfolded widths, including large text and keyboard interaction.
    A signed personal-app update must preserve existing profiles and drafts.

The overnight implementation stopped before phone installation. The later ADB
and branch-push authorization does not authorize personal-server access, merging
or publishing a release.

## Local verification — 2026-10-11

- Android: `test verifyRoborazziDebug assembleDebug lintDebug` passed; 396 JVM
  tests passed, one opt-in live smoke skipped, all 140 screenshots verified.
  Lint completed with 45 warnings and 4 hints, no errors. Twelve new baselines
  cover project index/overview, cached reading, editor, organize dialog and About
  at cover light/unfolded dark widths. A further limited-height editor baseline
  protects reachable Save/Cancel controls. Four settings baselines intentionally
  changed for the About footer; their rendered differences were inspected.
- Plugin: all 98 tests passed on the extended pinned-host fixture with the current
  plugin mounted. On unmodified pinned stock Hermes, all 13 project tests and all
  3 portable tests passed. On current non-Docker Hermes `d4d2ab7`, all 17 selected
  portable/project/native-profile tests passed. These use real authenticated HTTP,
  SQLite and SessionDB; inference is deterministic or absent.
- A local non-Docker HTTP fixture also passed create/move/pin/start/retry/native
  fork/real compaction/delete. The fixture was stopped afterward. Installer
  preview/apply/backup passed in a disposable data home; compatibility/license
  copies match. The CI workflow now explicitly runs stock project tests too.
- The separate fixture APK and instrumentation APK compile. The signed personal
  APK builds and verifies with the existing signing lineage. It was installed
  in place over 0.3.7, retaining Android UID 10260; no app data was cleared.

## Fold acceptance and fixes — 2026-10-11

Eight guarded device test executions passed (six distinct checks):

- `PortableFilesDeviceTest`: four immutable PNG downloads match size and SHA-256.
- `ProjectsDeviceTest`: project moves/pin/new chat/native branch/real compaction,
  encrypted cache restoration, remembered scope, draft and profile isolation.
- `ProjectsUiDeviceTest` at cover and unfolded widths: installed version 0.4.0/11,
  current/old plugin statuses, and a real Save touch while the keyboard is open.
- `ConversationSourcesDeviceTest` and `AuditRepairDeviceTest`: all native sources,
  initial SSE failure/reconnect, reasoning/answer deltas, delayed final persistence,
  restart during reconciliation, and one admitted inference after compaction.

On-device review found two bugs and fixed both: the keyboard obscured project
editor controls, and cover startup showed the drawer instead of the remembered
project overview. The editor now consumes IME insets; a new shell regression
asserts the overview opens without selecting a chat. The API 37 Espresso harness
failed before test execution; the guarded UI tests now use public platform
accessibility/touch APIs and passed on both actual displays. Cover/unfolded widths
were selected with temporary device-state overrides, then restored to the base
state. Existing density settings were unchanged.

The actual app About screen also reported plugin 0.3.0 from authenticated stock
fixture discovery. Screenshots/logs and upgrade metadata are in ignored
`build/releases/0.4.0/adb/`. Only disposable non-Docker Hermes and deterministic
inference were used. Temporary fixture apps, forwarding and server processes were
removed. The personal app remains stopped; its saved profiles were not opened.

Still unverified: physical hinge movement, every large-text/keyboard combination,
personal encrypted-profile decoding after the upgrade, and the user's actual
provider/server. The user installs plugin 0.3.0 on that server and performs the
manual checklist. No personal server was accessed and no release was published.
