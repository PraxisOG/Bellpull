# Kanban Tree validation

Source base: `7de6b73` (`fix/mobile-bugs-20261102`, includes experimental 0.19.0).
Implementation: `feature/kanban-tree-20261103`.

## Automated checks

- `bash tools/build-local-lab.sh :app:verifyRoborazziDebug :core-model:test :core-hermes:test :app:lintDebug --max-workers=4`
  passed: 740 app tests, 42 model tests, 48 Hermes client tests; one opt-in live-server
  test skipped. All 207 screenshot results verified. Lint passed.
- `:app:assembleDebug :app:assembleDebugAndroidTest -PbellpullDeviceFixture=true`
  built the separate `org.praxisog.hermes.fixture` app and instrumentation APK.
- Native Kanban suite: 17 tests passed on each local image, `2fd023efbb8d`
  (Hermes 0.21.5) and `63bfb6d732f4` (Hermes 0.21.1), using disposable homes and
  a read-only mount of this plugin. No inference or scheduler was started.

Focused coverage includes Tasks plus/menu routing versus Chats, card chat links
in every view, hidden/active search, ancestor context, independent arrows/detail
navigation, persisted expansion, 10,000-level traversal, unique progress counts,
missing hierarchy/parents, archived controls, parent selection, pages beyond the
newest-500 snapshot, changing page revisions, concurrent cycle attempts, atomic
receipt rollback, immutable offline reparent recovery, and plugin downgrade while
an edit is queued. Existing offline create/comment/edit/conflict tests also pass.

## Images

[Before: persistent board/search controls](list-cover-before.png) and
[after: compact controls and linked-chat button](list-cover-after.png).
The former baseline renders the Tasks content alone; the new image includes the
main toolbar as well.

[Phone Tree](../../../android/app/src/test/snapshots/org.praxisog.hermes.KanbanScreenshotTest.treeCover.png)
and [unfolded Tree](../../../android/app/src/test/snapshots/org.praxisog.hermes.KanbanScreenshotTest.treeUnfolded.png)
were rendered at 393×850 and 840×900 dp and visually inspected, along with List,
Board, the parent picker and incomplete/archived states.

Physical Pixel 10 Pro Fold, Android 17: the existing unfolded display configuration
(2076×2152 px; density override 440) was retained. The isolated fixture uses native
Hermes in a disposable local Docker container via ADB reverse on port 18658.
Main navigation, board switching, Today→task, offline creation, encrypted-cache
restart and lost creation response recovery passed on the phone (2 tests).
The dedicated Tree test verifies recursive expansion, return from details, subtask
creation and parent reassignment without a status change or inference.

The personal app and data, unrelated Canvas fixture, main local Hermes container,
and Moe were not modified. Only the disposable Kanban fixture app/container were
used for device acceptance.

Final device result: **3 tests passed** across the regression and dedicated Tree
runs; the last Tree run includes the final parent-picker implementation. All
screenshots were inspected. The test APKs, their temporary install file, ADB
reverse rule and disposable container were removed. The primary app still reports
0.19.1-experimental/build49. No source branch or release was pushed.
