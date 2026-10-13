# Combined OpenCode and planning preview

Branch: `integration/phase6-8-9`, merging `feature/phase6-opencode-activity`
(`091abec`) and `feature/phase8-9-reminders-calendar` (`3deab54`). Both original
branches remain intact. App **0.8.1-integration-preview / build 20**; one Bellpull
plugin **0.4.0-integration-preview**. The combined **fixture app** passed phone
acceptance on October 13. The user then requested the latest personal app; the
signed build20 was installed in place over 0.5.5 with its signer and data preserved.
Development release: [0.8.1 integration preview](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.8.1-integration-preview.1).
The user authorized pushing this branch and publishing the experimental bundle.
The default branch receives documentation only; preview app code remains here.
No personal-server access. The [current phase roadmap](ROADMAP.md) accompanies
the release, including Phase 7's later backend-only scope.

## What was combined

Chat/Activity observes Hermes-owned OpenCode work; Today owns reminders, native
Hermes routine controls and opt-in CalendarContract day/week views. All existing
chat/projects/files/voice behavior remains capability gated. Both workspace
controllers share the application-owned client but retain separate caches/lifecycle.
Disconnect closes both; native profile switches cannot carry either cache across.
Planning navigation does not submit, stop or replace a coding run. The combined
plugin registers one API handler factory, the OpenCode observer hook, sharing and
planning tools, and advertises each capability independently. Coding/planning
storage and API paths are distinct; no native session schema migration is needed.

Eleven conflicting files were resolved deliberately: runtime/client/capabilities,
version metadata, both CI jobs, the shared phone helper and documentation. Both
sets of endpoints, initialization/refresh/shutdown and stock tests are retained.
Pinned CI action/image revisions and compatibility source hashes are unchanged.
No new dependency, runner, scheduler, automatic phone bridge or unattended calendar
write was added.

## Verification completed before asking for ADB

- Full required Android command: `test verifyRoborazziDebug assembleDebug lintDebug`.
  **549 discovered JVM tests: 548 passed, one opt-in live-provider smoke skipped**;
  **170 screenshot baselines verified**, debug build and lint passed.
- New two-width regression drives a real DurableRuns admission through both UI
  destinations using deterministic HTTP responses: Activity → Today → Activity →
  Chat preserves the busy run, exact run ID and unsent draft; one admission, no Stop.
  Both new baseline images were visually reviewed. The first harness attempt waited
  for background state before synchronizing destination composition; the regression
  now also asserts Activity leaves the tree before checking Today data.
- **18 coding and 9 planning Python tests passed** with the combined plugin on
  disposable unmodified stock Hermes `d4d2ab7b5d104166e7e22a02cafd12f1fe456a31`.
  The native run test now creates/updates a reminder while observing an owned child,
  verifies both advertised features, and confirms exactly one admitted run and an
  unchanged ProcessRegistry output sink. Inference and the child executable are
  deterministic fixtures, not a real OpenCode/provider run. Core files are untouched.
- Complete plugin suite on that newer stock source: **125 tests, 29 failures and
  4 errors**. These are failed checks. Legacy branch/pagination and optional host
  run/approval routes are unavailable on that unreviewed source revision. The older
  97-test attempt had two import failures instead of executing its extended-host
  cases; its totals must not be used as an equivalent baseline for this invocation.
- Full **mixed legacy compatibility fixture**: **125 tests, 122 passed, three
  approval failures**. The failing cases are the same parallel-approval, timeout
  and unattended-isolation cases previously reproduced on unchanged 0.5.5. This
  fixture combines current source/plugin loader, archived hash-verified legacy
  adapter/approval modules, optional host v1 overlay and a synthetic 0.21.1 install
  stamp. It is not an exact stock release/Docker CI result and is never deployed.
  No approval policy, compatibility check or test was weakened to turn it green.
- Fixture application and Android test APK compile with the combined version;
  lint passed again. The phone test now includes Today/reminder navigation during
  the same native coding run. Combined phone results are recorded below; earlier
  feature-branch results in their scope documents remain separate evidence.
- Local release build/signature and 16-KiB ZIP alignment passed. APK identifies
  `org.praxisog.hermes`, build 20, with the existing signing lineage. APK SHA-256:
  `84069d410f42cefea93dea8d53f03475e89aed335f767fd747c1e81bab86d92b`.

Logs: `build/integration/{android-full,navigation-record,python-coding,python-planning,
python-full-stock,python-full-legacy,device-build,signing}.log`. Local artifacts:
`build/releases/0.8.1-integration-preview/Bellpull.apk`,
`bellpull-plugin-0.4.0-integration-preview.tar.gz`, and `SHA256SUMS`.

## Stock installation and compatibility

The archive contains `plugins/bellpull/`, `tools/install-plugin.py`, MIT license
and scope/API documentation. Unpack into a fresh directory. Back up each Hermes
profile home; preview the installer before applying:

```bash
python3 tools/install-plugin.py --home /YOUR/HERMES/DATA/HOME
python3 tools/install-plugin.py --home /YOUR/HERMES/DATA/HOME --apply
HERMES_HOME=/YOUR/HERMES/DATA/HOME hermes plugins enable bellpull
```

Disable the separate old `bellpull_gpu` plugin if it is still installed, then restart
your existing Hermes gateway service. The installer retains the previous plugin;
no Docker, core-module replacement, hardcoded host URL or source path is required.
Do not mix individual modules from the two original preview bundles. Refresh
capabilities in Bellpull. Check coding/planning flags separately: modern ownership
hooks are required for coding; per-profile native cron APIs are required for routine
controls. Unsupported capabilities stay unavailable without root-profile fallback.
The existing files/projects data is retained; new `bellpull-coding/` and
`bellpull-planning/` metadata is additive. Restore the whole previous plugin and
restart to roll back. App build 20 exceeds both original preview build codes;
keep its signer/app data, and do not uninstall the personal app for an upgrade.

## Combined phone acceptance — October 13

After the ADB question, the user replied “send it.” Tests used only the separate
`org.praxisog.hermes.fixture` app on the Pixel 10 Pro Fold/GrapheneOS API 37 and
disposable loopback stock Hermes `d4d2ab7b5d104166e7e22a02cafd12f1fe456a31`.
The native API, plugin loader, run ownership hooks, ProcessRegistry and routine
storage were real; inference and the two OpenCode-named child processes were
deterministic fixtures. No personal server/provider was contacted.

| Final device suite | Viewport | Result |
| --- | --- | --- |
| Coding activity + missing-model startup | Unfolded, 2076×2152 | 2 passed |
| Coding activity + missing-model startup | Simulated narrow, 1080×2424 | 2 passed |
| Planning + missing-model startup | Unfolded | 3 passed |
| Planning + missing-model startup | Simulated narrow | 3 passed |
| Actual Android calendar permission denial/grant | Unfolded | 1 passed |

**11 executions passed in the final suites.** The first unfolded attempt failed
with a test-harness null navigation target immediately after closing the diff.
The test now waits for workspace navigation to return before choosing the wide
or narrow path. Both widths passed afterward; no product code or APK bytes changed.

Coding acceptance covers live reasoning/output, read-only diffs, Today/reminder
navigation during the same busy run, preserved drafts, background catch-up,
disconnect/recreation/reconnect, child completion before coordinator completion,
history reconciliation, encrypted cache reopening, compression lineage and ordinary
branch isolation. Each fixture admitted exactly one run, including after recovery.

Planning acceptance covers real reminder create/snooze/complete/reopen, native
routine create/pause/resume/cancel, offline cache and draft preservation. Actual
CalendarContract checks cover a UUID-account LOCAL calendar, immutable retry marker,
create/edit/delete/stale-edit rejection, all-day events, three recurring instances,
overlapping day/week blocks, and UI creation with the keyboard open. Busy-only
sharing omits details; opt-in proposal application survives a dropped result without
inserting a duplicate event. Offline sharing revocation recovers. Zero inference
admissions occurred in either planning fixture.

The separate real permission dialog test denied then granted access and verified
that sharing, writes, calendar selection and destination remained disabled/empty.
Exact test-calendar deletion was asserted after each provider scenario; the final
test confirmed zero LOCAL calendars matching the fixture account prefix remained.
Day/week, Activity/diff and combined Today screenshots were inspected at both widths.

Reproduction: build the fixture APKs from the repository root:

```bash
./android/gradlew -p android -PbellpullDeviceFixture=true \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Run `tools/coding-device-fixture.py` or
`tools/planning-device-fixture.py` with `--hermes-source` pointing to disposable stock
source, and reverse only its loopback port (18648/18649). Use the classes listed
above with the fixture instrumentation runner. Restart the coding host for every
attempt (one admission per lifetime), and planning host for each width. Run the
permission class separately after force-stopping the fixture, revoking both
READ/WRITE_CALENDAR permissions and clearing their `user-set`/`user-fixed` flags.
Never substitute the personal package or a personal host.

Logs and captures: `build/integration/device-2026-10-13/{wide,narrow,permission}/`;
the original failed attempt remains in `wide/coding/tests.log`, with the successful
retry in `tests-retry.log`. Test compilation log: `test-build.log`.

Cleanup completed: fixture app/test package and their captures removed, both test
reverse ports removed, physical resolution restored, existing density override 440
preserved, and local fixture processes stopped. At fixture cleanup the personal
package was still stopped at **0.5.5/code17, UID10260** with saved accounts untouched.
Thermal status was 0 when checked; this acceptance was not a thermal benchmark.

Additional deployment preflight on unmodified official Hermes `v2026.9.24`
(internal `0.21.5`, amd64 digest
`sha256:2fd023efbb8d3d2b0ce1a73d028b07370cff34f567cfe0e999553e8c327ea283`)
passed **43 focused Python tests**: 18 coding, 9 planning, 13 projects and
3 portable-sharing tests. This is a separate stock-image result, not a full-suite
or real-inference pass. It confirms the new process tracking and native plugin
surface needed for coding/planning. Private hash-pinned legacy branch/input/model
extensions remain gated off on this newer stock image. The dev server migration
is a separate action; no success is implied merely by publishing this bundle.

## Personal app installation — October 13

After acceptance, the user explicitly requested the latest app on their phone.
The signed **0.8.1-integration-preview/build20** APK above was installed in place
with `adb install -r`. The installed 0.5.5 APK was first copied locally and its
checksum matched the corrected release. Both APKs passed signature verification
with the same public certificate SHA-256
`c1211fd4dbb2b4c0710fe35858bbc4d19eb2d2d27abb18eb5ad51e8192db55bf`.
The package remains `org.praxisog.hermes`, UID **10260**, with CE/DE data-directory
inodes **117766/116002** unchanged. No uninstall or data clear occurred. Build20
metadata was verified after installation; the app was left stopped for the user
to open, avoiding personal-server access. This is an installed local preview,
not a published release or personal-provider functional test.

Installation/metadata/signer logs and the previous APK are under
`build/releases/0.8.1-integration-preview/personal-upgrade/`. The matching plugin
bundle is for manual server installation only; no personal plugin was deployed.
Do not uninstall to downgrade: Android may reject an older release version code;
prefer a higher-code fix preserving data and the signing lineage.

Still unverified: actual OpenCode/provider behavior and personal-host Stop, real
scheduled inference, account/cloud sync and marker retention through a sync adapter,
calendar-app handoff, physical hinge transitions, exact pinned Docker CI. Reminder
background/offline alerts remain Phase 10. No automatic calendar writes or invitations.
