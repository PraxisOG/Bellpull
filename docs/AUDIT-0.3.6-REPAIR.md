# Bellpull 0.3.7: repair of the 0.3.6 audit — 2026-10-11

Local repair of `repair/bellpull-plugin-migration`, audited baseline
`8597dbd83af926e28b2a39cb621a0658410e0eab`. The initial worktree was clean;
local HEAD and the remote repair branch both matched that commit. All six
findings applied; none was disproved by later code. The initial audit excluded
phone installation and pushing; the user subsequently authorized ADB installation,
device tests and pushing **0.3.7** with updated instructions. The personal Hermes
server remains off limits. Android is now **0.3.7/code 10**, paired with plugin
**0.2.2**. Existing previously prepared 0.3.6 APKs do **not** contain this repair.
No personal-server access, real provider inference, merge or public release was used.

## Findings and changed behavior

| Finding | Resolution and production files |
| --- | --- |
| 1. Completed compaction run stays busy | `DurableRuns.kt`, new `RunCompletion.kt`, `RunStatus.kt`, `RunControls.kt`: journal native final output and server start time. Match a unique fresh final assistant row to that output, excluding known old IDs, tool requests and other prompt segments. When the submitted prompt was compacted away, require a saved reply timestamp at/after server admission. Old cloned rows cannot pass that fence. Delayed transactions remain pending. Twelve unsuccessful history checks stop automatic retries with persisted **Needs review**, **Check status**, and an explicit **Stop tracking** dialog. Releasing tracking preserves a newer draft, does not claim history was synchronized, and never restores an admitted prompt for resubmission. A verified match clears busy state and permits a distinct follow-up. |
| 2. Failure before first SSE frame | `DurableRuns.kt`: inspect the stream result. Recoverable failures retry the same subscription, without a fabricated cursor or another POST. Backoff starts at five seconds and grows to forty seconds. Consumed unsequenced streams keep the safe polling fallback; sequence gaps, replay truncation and duplicate suppression remain enforced. The stream is a child of run tracking, so terminal state/profile shutdown cancel retries, including an opening-stream race. |
| 3. Outbox retains published copies and starves new files | `sharing.py`, `artifacts.py`: serialize publication per conversation, durably save immutable bytes, then remove only an unchanged staging copy in the plugin's own outbox. Failed, changed and unpublished files remain. Deterministic IDs preserve interrupted-commit retry safety and deletion tombstones. A persisted rotating batch cursor prevents old failures from monopolizing the 1000-entry bound. Tool results explicitly report partial/empty/published state, counts and scoped warnings. Blob directory entries are fsynced before metadata commit and staging retirement. |
| 4. Compression hides conversation files | New `lineage.py`, `artifacts.py`: expand existing `session_ids` across verified compression ancestry/continuations in the same profile, for list/filter/access checks. Both older and newer IDs see files created before or after compression, across multiple generations. Parent must be ended by compression; tool children and branch/delegate/reset markers bound to that parent are excluded. Inherited markers pointing elsewhere do not prevent a branch's own later compression. Ambiguous/cyclic/excessively deep graphs fail closed. Exact-prefix branch inheritance and message maps remain unchanged. Android's existing `Artifact.forSession` consumes these aliases; no broader client-side parent matching was added. |
| 5. Native profile URL rejected | `HermesEndpoint.kt`: retain a validated `/p/profile` prefix, normalize one trailing slash and keep root URLs working. Match Hermes' lowercase profile-ID syntax. Reject embedded credentials, queries/fragments, escaped/dot/extra path segments and insecure public HTTP. Existing authenticated capability discovery rejects an unserved prefix; requests never fall back to another account. |
| 6. Android CI setup failure | `.github/workflows/checks.yml`: explicitly request supported `platform-tools`, avoiding the action's obsolete `tools` default. Install the catalog's actual `platforms;android-37.0` and existing `build-tools;37.0.0`. All action revisions, required validation steps and failure propagation remain pinned/intact. |

`HermesStore.kt` also stops projecting the archived prompt as a temporary user
bubble after verified completion or explicit tracking release. The physical
screen check found that stale bubble below the final reply; store-level and
device assertions now guard against it.

The history snapshot implementation did not need a production change: real
SessionDB tests confirm active snapshots can legitimately omit the archived
prompt while retaining the final output. Reconciliation handles that condition
without exposing compacted archives as ordinary chat messages.

## Regression coverage

- `DurableRunsTest.kt`, `RunCompletionTest.kt`: compaction/compression history,
  delayed final persistence, old/cloned/identical prompts and answers, ambiguous
  matches, journal migration/round-trip, restart while waiting, bounded review,
  retry/release preserving a newer draft, a usable composer, and admission counts.
  `HermesStoreTest.kt` checks that settled/released compacted prompts are not
  displayed as pending user messages after reloading the saved reply.
  SSE tests cover failure before headers followed by thinking/answer deltas, safe
  legacy fallback, terminal/shutdown cancellation, existing replay gaps and duplicates.
- `test_pagination.py`: real SessionDB `archive_and_compact`, archived original
  prompt, a fresh delayed final response and bounded snapshot refresh.
- New `test_sharing.py`: publish/retry/delete/40-day housekeeping, more than 1000
  historical outputs, quota partial failure, interruption after commit, disk-sync
  failure, changed-source retention, outside-folder retirement rejection and
  starvation by 1000 unsafe files.
- New `test_artifact_lineage.py`: multiple compression generations, old-ID/new-file
  visibility, explicit forks/reset/delegates, branch-prefix links, a branch's own
  continuation, separate profiles/scopes and ambiguous ancestry.
- `HermesEndpointTest.kt`, new `NativeProfileContractTest.kt`,
  `BellpullFilesContractTest.kt`: strict URL validation, authenticated rejection
  of unserved profiles, root/profile prefixes on native and plugin requests,
  and Android filtering of server-supplied compression aliases.
- `RunRecoveryScreenshotTest.kt`: two new completion-review dialog baselines,
  folded light and unfolded dark. Existing UI baselines remain unchanged.
- `test_portable.py`: flush newer Hermes' background runtime-status writer before
  removing the disposable test home. This fixes a fixture-cleanup race without
  suppressing errors or changing the running Hermes implementation.

## Checks actually executed

| Check | Result |
| --- | --- |
| Android `./gradlew test verifyRoborazziDebug assembleDebug lintDebug` | Passed. 371 tests declared: 370 passed, one opt-in live-provider smoke skipped. App 323; core-hermes 34 including that skip; core-model 14. All 127 screenshot baselines verified. Debug APK built. Lint: 0 errors, 41 warnings, 4 hints. |
| Full plugin suite on pinned extended-host fixture, network disabled, fake inference | 84 passed. Real adapter, SQLite, artifact lifecycle, branches, profiles, run admission and subscription cleanup exercised. |
| Unmodified pinned Hermes `v2026.9.7` image with only the mounted plugin, network disabled | 26 passed: stock portability plus artifacts, sharing, compression aliases and paging/compaction. Core source hashes checked unchanged. |
| Non-Docker stock checkout `d4d2ab7b5d104166e7e22a02cafd12f1fe456a31`, Python 3.14 | 20 portable/artifact/sharing/lineage checks passed, fake inference. Core hashes unchanged. A prior run reached all functional assertions but failed temporary-home removal because native status writing was still queued; the explicit flush fixed that cleanup race. |
| SDK catalog and explicit SDK install command | Passed locally for `platform-tools`, `platforms;android-37.0`, `build-tools;37.0.0`. SDK catalog does not list the old `platforms;android-37` identifier. |
| Compatibility manifest and bundled license copies; `git diff --check` | Passed. |

Physical Pixel 10 Pro Fold / GrapheneOS Android 17 acceptance uses only the
separate `org.praxisog.hermes.fixture` sandbox and disposable non-Docker stock
Hermes checkout above. Three explicitly selected instrumentation tests check
byte-identical PNG downloads, all conversation sources, and the audit repair.
`AuditRepairDeviceTest` fails the first SSE request with HTTP 503, verifies live
thinking over 500 characters and answer deltas after reconnection, then uses real
`archive_and_compact` and delays final transcript persistence until native status
is completed. Closing/recreating the tracker from encrypted recovery data must
reconcile the delayed final reply, clear busy state and suppress a stale pending
prompt. Fixture counters prove **one inference**, **two SSE requests** and
**one injected failure**. These are deterministic fixture tests, not a real provider.
An unfolded physical UI hierarchy check also verifies the final reply is visible,
Working/Stop and the stale prompt are absent, and the composer is available.
Both temporary fixture packages and the ADB reverse rule are removed afterward.

The personal app update uses `tools/release.py build` and the existing signing
lineage. ADB `install -r` upgrades **0.3.4/code 7 → 0.3.7/code 10**; Android UID
**10260** is retained. The personal app is kept stopped, so its server/profile is
not contacted. UID retention and successful signature-compatible installation
do not prove that every encrypted draft/profile can be opened: that check remains
for the user. No app data was cleared and the personal app was not uninstalled.
The final signed APK SHA-256 is
`c0d586c3b7219681099b066d328deea3eb700bf58913d9851e4e1812abefeb6b`.
Local APK, checksum, test logs and private fixture capture are under
`build/releases/0.3.7/` and are excluded from Git.

An exploratory run of the pinned snapshot HTTP tests against the newer non-Docker
checkout **did not pass**: five paging tests failed (three assertions, two JSON
decode errors) because their routes returned 404. That checkout's source revision
is outside the existing private-route compatibility gate, so the plugin deliberately
does not advertise/register snapshot paging there. Portable file and stream checks
above pass on that checkout. This repair does not relax that gate or claim paging
verification on an unsupported source tree. Native paging remains the Android fallback.

## CI evidence inspected, separate from local results

The [audited Actions run](https://github.com/PraxisOG/Bellpull/actions/runs/38128068879)
belongs to the baseline commit. Plugin validation passed. Android failed while
`setup-android` ran `sdkmanager tools` with “Failed to find package 'tools'”.
Its explicit SDK install, Gradle tests/screenshots/build/lint were skipped, not passed.
The pinned action accepts a `packages` input and defaults to `tools platform-tools`;
the workflow now overrides that default. The user subsequently authorized pushing
0.3.7; the resulting hosted run must be inspected separately from local verification.
No hosted pass is inferred from the local SDK install or Gradle results.

## Compatibility and limits

- No added dependencies, new endpoints, core-module replacements or wire-version
  changes. New run-journal fields have defaults; old journals load. The server
  adds only a collection-cursor table and derives compression aliases at read time.
  Existing immutable artifact IDs, bytes and explicit branch links are preserved.
- Install both repaired app and plugin for all six fixes. File membership and
  outbox retirement are server-side; existing clients already understand aliases.
  Android-only repairs do not depend on enabling private host extensions.
- Existing published files left in old outboxes retire on subsequent publish calls,
  in bounded batches. Successfully published immutable files persist until deletion.
  Failed/unpublished staging is intentionally retained for recovery and is not part
  of the immutable-artifact quota total. An empty publish retry returns no new records;
  previously published records remain in the authenticated file browser.
- Missing final output/timestamps, an expired native run record, or ambiguous saved
  replies can require explicit review instead of automatic completion. The repair
  fails closed, exposes recovery and never resubmits an admitted run. It does not add
  an authoritative run-to-transcript ID to Hermes core.
- Compression aliases require unambiguous verified lineage within 1000 generations
  and at most 1000 children at an edge. Unprovable associations remain globally
  accessible within their original profile but are not shared into another conversation.
- Legacy unsequenced SSE cannot safely resume consumed text; it continues to use
  status/history fallback. Providers still need to produce live reasoning.
- Device acceptance uses a separate app sandbox and deterministic stock fixture.
  Personal-server and real-provider acceptance remain outstanding. The local signed
  APK is installed as authorized, but no public release is created. Signing material
  and private phone captures stay outside Git.

## Manual acceptance on your stock, non-Docker server

1. The signed phone update is installed via ADB; open your existing profile yourself
   and check that connections, drafts and settings survived. Back up the current
   plugin and actual service/profile data home, including
   `bellpull/principal-id`, `bellpull-artifacts` and `state.db`. Build/package this
   repaired source with your existing signing lineage for future installs; do not
   install the ordinary debug APK over a release installation. Keep the previous
   app/plugin for rollback.
2. Preview then apply `python3 tools/install-plugin.py --home /YOUR/DATA/HOME`
   (second call adds `--apply`). Enable the single `bellpull` plugin and restart your
   normal Hermes service. No Docker, core copying, model/provider changes or legacy
   host patch is needed. Reconnect Bellpull to refresh authenticated discovery.
3. Verify `/api/bellpull/capabilities` using your existing bearer key:
   `bellpull_persistent_artifacts` and `bellpull_artifact_lifecycle` must be true.
   Confirm the drawer still lists CLI/TUI/API conversations for that profile.
   Keep keys in your existing secret mechanism, not screenshots or shell history.
4. Start a multi-step chat that naturally reaches Hermes context compaction, or use
   a disposable conversation with your usual supported compression workflow. Let it
   finish. The final saved reply must appear, Working/Stop must clear and a distinct
   follow-up must send once. Repeat an identical prompt in a later turn. Check Hermes'
   run/log evidence that recovery did not submit another inference for either turn.
5. Repeat while closing/reopening Bellpull after Hermes finishes but before its saved
   transcript appears. It must recover the same run. If authoritative matching is
   unavailable, wait for Needs review, use Check status, then review the saved chat
   and Stop tracking. A newer draft must survive; the admitted prompt must not be
   reinserted into the composer. The conversation must become usable again.
6. Interrupt connectivity before the first stream event, then restore it while a
   reasoning-capable run is active. Thinking and answer deltas must resume in the
   open chat without selecting it again. Check one run admission. Repeat a later
   interruption to exercise replay deduplication; switching/disabling the server
   profile must cancel stream retries.
7. Ask Hermes to prepare, stage and publish several files with `bellpull_share_files`.
   Successful outbox copies should disappear; downloads must match source SHA-256
   and length. Retry publish and restart the gateway: immutable downloads must remain
   identical without duplicates. Delete one artifact and verify it stays unavailable
   after housekeeping/retry. Keep separate original files outside the outbox intact.
8. In a disposable conversation, stage 1001 small files. First publish should report
   partial/counts; add a later-sorting new filename and publish again. The remainder
   and new output must arrive. For quota/disk failure, use a disposable test profile:
   failed files must stay staged, partial publication must be explicit, and retry
   after resolving the failure must recover them. Avoid filling production storage.
9. Publish a file, compress the conversation, publish another, and compress again.
   Both files must remain visible from the continued conversation and from a client
   still holding the older ID. A separate branch/profile must not receive them except
   where an exact copied-prefix message proves an explicit branch association.
10. If your listener actually serves a native profile, add `https://HOST/p/work/`
    with that profile's key. The normalized prefix must survive reconnect and all
    requests. An unserved prefix and wrong profile key must fail without showing
    root-account data. If native `profile_routes` is empty, use the root URL; do not
    interpret syntactic URL acceptance as server-side profile setup.
11. Inspect the resulting Actions run after pushing: SDK setup
    and install, Android tests, screenshot verification, APK assembly and lint must
    all execute and pass. Consult the final task handoff for the observed run result.
