# Phase 18: native mobile Kanban

Development build 0.18.1-experimental (46), standard plugin 0.8.1-integration-preview.
Branch `feature/mobile-kanban-editing-0181` follows the initial 0.18.0 board slice.
The local test Hermes has the new Kanban contract while preserving its existing
Canvas preview. The user updates Moe manually. This increment is not published.

## Included

- Tasks destination with board selector, list/column views and search of loaded tasks.
- Board owner, snapshot age, task assignee/project, body/result, comments, attempts,
  dependencies, conversation links and file references when native data supplies them.
- Latest authoritative state in the encrypted cache for each saved connection.
- Comments and advertised moves into triage/blocked for unclaimed, inactive work.
- New tasks enter triage without dispatch. Edit title, description, assignee profile
  and priority for inactive, unclaimed tasks that fit the mobile field limits.
- Encrypted form drafts survive restart and retain the revision from when editing
  began. Pending creations appear as local cards until receipts supply native IDs.
- Durable queued, unknown and conflict states. Unsent edits can be discarded or
  edited. Conflicts require explicit review; uncertain delivery retains its exact
  identity and payload until the plugin's receipt resolves it.
- A Today preview of up to five open tasks from the selected board, using the same
  workspace and queue. This does not close the wider issue #28 reliability gate.

The [API contract](hermes-api.md#bellpull-native-kanban-extension--october-31-2026)
documents the new plugin endpoints. They use the existing bearer authentication,
including native profile prefixes. No dashboard authentication, new dependency,
model inference or worker scheduler is needed. Hermes remains authoritative.
Native Kanban boards are shared across profiles within an installation; the phone
still isolates caches and pending operations by saved connection/profile.

Each native board stores a persistent incarnation ID and operation receipts in
plugin-prefixed tables. A create/edit/comment/move and its receipt commit in one native SQLite
transaction. A lost response can therefore be recovered without a duplicate action.
The plugin never launches or cancels execution through a card move.

## Bounds and remaining work

Snapshots contain the newest 500 tasks and disclose incompleteness; search is local
to those tasks. Details retain 50 comments, 20 attempts, 50 file references and
100 dependencies per direction. Ten board snapshots and ten detailed tasks are
cached per connection. Pending edits and saved task forms are each capped at 100.
Titles allow 500 characters, descriptions 16,384, and priorities signed 32-bit integers.
Receipt admission stops at
20,000 operations per board; existing recovery evidence is retained.

Dependency editing, drag-and-drop, pagination, execution start/stop/resume/retry,
review completion and cross-installation aggregation remain follow-ons.
File references show the owning device and path; native data supplies no content
hash, which is explicitly shown as unavailable. File content is not copied.
The twelve Kanban tests also pass on stock Hermes 0.21.5. Other installed versions
need their own compatibility check before rollout.

## Validation

October 31 validation: 789 Android JVM tests passed, one opt-in test skipped; all
199 screenshot baselines matched, including creation forms at cover/fold/keyboard
heights. Debug build and lint passed with zero errors and 89 existing warnings.
The isolated Hermes 0.21.1 plugin suite ran 200 tests with one skip and no failures;
all twelve Kanban tests also passed on stock 0.21.5 and the staged Canvas/Kanban bundle.
All three physical Pixel 10 Pro Fold Kanban tests passed: queue/recovery/native
writes, full MainActivity/Today navigation, and offline creation/editing with restart
and lost-response recovery. The prior 0.18.0 increment also passed ten device
regressions for app chrome, widgets, assistant entry, Today actions and storage isolation.

The repository includes native SQLite/authenticated HTTP tests, Android queue and
model tests, deterministic phone/fold/sidebar/editor screenshot baselines and physical-device
tests. `tools/kanban-device-fixture.py` seeds temporary native boards, serves only
loopback and forbids inference. Both `HERMES_HOME` and `HERMES_KANBAN_HOME` are isolated.

The device test uses `-PbellpullDeviceFixture=true`, package
`org.praxisog.hermes.fixture`, and ADB reverse on port 18658. It touches a native card
move and offline comment, reopens the encrypted cache, checks connection isolation,
drops a committed HTTP response, verifies receipt recovery without another POST,
then simulates a concurrent desktop comment and explicitly reviews the stale edit.
Creation tests also recover a dropped committed response to the native task ID
without another POST. A JVM regression retains unknown delivery even when an
older queued UI item is passed to Edit draft after delivery starts.
The personal Bellpull package and its data are not test fixtures.

The local test gateway runs Hermes 0.21.5 and reports
`0.6.1-canvas-v1-preview+kanban.0.8.1`. Its previous plugin, SQLite databases and
configuration were backed up before installation. Canvas code/capabilities,
configuration hashes and all 68 sessions were preserved. Authenticated temporary
board checks passed for create, duplicate replay, receipt lookup, edit, stale
revision conflict and comment without execution. The validation board was archived.
The standard plugin archive is separate from this Canvas-preserving test deployment.

Signed 0.18.1/build46 is installed in place on the connected Pixel 10 Pro Fold.
The installed APK matches the release bytes; signing lineage, app UID, credential/
device-encrypted data inodes and original installation time are unchanged. Signature
verification and 16 KiB alignment pass. Artifacts and validation logs are under
`/home/praxisog/Bellpull-builds/kanban-editing-development/`.

Useful commands from the repository root:

```sh
bash tools/build-local-lab.sh test verifyRoborazziDebug assembleDebug lintDebug
bash tools/build-local-lab.sh :app:assembleDebug :app:assembleDebugAndroidTest -PbellpullDeviceFixture=true
python3 tools/release.py build
```

Install the matching plugin through the existing `tools/install-plugin.py` workflow
and restart the gateway before using live Tasks. Older servers keep cached content
readable and display the missing-capability explanation.
