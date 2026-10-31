# Phase 18: native mobile Kanban

Development build 0.18.0-experimental (45), plugin 0.8.0-integration-preview.
This is the first implemented slice on `feature/mobile-kanban-0180`, based on
0.15.1. It has not been deployed to a personal Hermes server or published.

## Included

- Tasks destination with board selector, list/column views and search of loaded tasks.
- Board owner, snapshot age, task assignee/project, body/result, comments, attempts,
  dependencies, conversation links and file references when native data supplies them.
- Latest authoritative state in the encrypted cache for each saved connection.
- Comments and advertised moves into triage/blocked for unclaimed, inactive work.
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
plugin-prefixed tables. A comment/move and its receipt commit in one native SQLite
transaction. A lost response can therefore be recovered without a duplicate action.
The plugin never launches or cancels execution through a card move.

## Bounds and remaining work

Snapshots contain the newest 500 tasks and disclose incompleteness; search is local
to those tasks. Details retain 50 comments, 20 attempts, 50 file references and
100 dependencies per direction. Ten board snapshots and ten detailed tasks are
cached per connection. Pending edits are capped at 100. Receipt admission stops at
20,000 operations per board; existing recovery evidence is retained.

Task creation, assignment editing, drag-and-drop, pagination, execution start/stop/
resume/retry, review completion and cross-installation aggregation remain follow-ons.
File references show the owning device and path; native data supplies no content
hash, which is explicitly shown as unavailable. File content is not copied.
The eight Kanban tests also pass on stock Hermes 0.21.5. Other installed versions
need their own compatibility check before rollout.

## Validation

October 31 validation: 780 Android JVM tests passed, one opt-in test skipped; all
196 screenshot baselines matched (five new Kanban/navigation views); debug build and lint
passed with zero lint errors. The isolated Hermes 0.21.1 plugin suite ran 196 tests
with one skip and no failures; all eight Kanban tests also passed on stock 0.21.5.
Both physical Pixel 10 Pro Fold Kanban checks passed: queue/recovery/native writes
and full MainActivity navigation, board selection and Today-to-task navigation.
Ten additional device regressions passed for app chrome, widgets, assistant entry,
Today actions and encrypted storage isolation.

The repository includes native SQLite/authenticated HTTP tests, Android queue and
model tests, five deterministic phone/fold/sidebar screenshot baselines and a physical-device
test. `tools/kanban-device-fixture.py` seeds a temporary native board, serves only
loopback and forbids inference. Both `HERMES_HOME` and `HERMES_KANBAN_HOME` are isolated.

The device test uses `-PbellpullDeviceFixture=true`, package
`org.praxisog.hermes.fixture`, and ADB reverse on port 18658. It touches a native card
move and offline comment, reopens the encrypted cache, checks connection isolation,
drops a committed HTTP response, verifies receipt recovery without another POST,
then simulates a concurrent desktop comment and explicitly reviews the stale edit.
The personal Bellpull package and its data are not test fixtures.

Useful commands from the repository root:

```sh
bash tools/build-local-lab.sh test verifyRoborazziDebug assembleDebug lintDebug
bash tools/build-local-lab.sh :app:assembleDebug :app:assembleDebugAndroidTest -PbellpullDeviceFixture=true
python3 tools/release.py build
```

Install the matching plugin through the existing `tools/install-plugin.py` workflow
and restart the gateway before using live Tasks. Older servers keep cached content
readable and display the missing-capability explanation.
