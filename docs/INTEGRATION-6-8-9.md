# Combined OpenCode and planning preview

Branch: `integration/phase6-8-9`, merging `feature/phase6-opencode-activity`
(`091abec`) and `feature/phase8-9-reminders-calendar` (`3deab54`). Both original
branches remain intact. App **0.8.1-integration-preview / build 20**; one Bellpull
plugin **0.4.0-integration-preview**. Local development only: no push, default-branch
merge, release publication, personal-server access or combined phone installation.

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
  the same native coding run. **It has not run on the phone yet.** Earlier feature
  branch device results are recorded in their scope documents and do not establish
  combined-build acceptance.
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

## Combined phone acceptance — awaiting ADB

The user explicitly requested an ADB availability question after local integration.
Do not connect to/use the phone until that reply. Once returned:

1. Use only `org.praxisog.hermes.fixture` and disposable loopback Hermes. Run the
   combined `CodingActivityDeviceTest` plus startup check at unfolded/narrow widths.
   During one native run, open Today, create a reminder, return to Activity, then
   verify drafts, reconnects, diffs, independent child completion and one admission.
2. Run `PlanningDeviceTest`/startup on a fresh planning fixture, then the separate
   permission flow. Use only a UUID-account LOCAL test calendar; verify removal,
   proposal retry without duplicate writes and offline sharing revocation.
3. Preserve personal app/accounts/signer/data and the personal server prohibition.
   Remove test calendars/packages, our ADB forwarding and display overrides afterward.

Still unverified: actual OpenCode/provider behavior and personal-host Stop, real
scheduled inference, account/cloud sync and marker retention through a sync adapter,
calendar-app handoff, physical hinge transitions, exact pinned Docker CI. Reminder
background/offline alerts remain Phase 10. No automatic calendar writes or invitations.
