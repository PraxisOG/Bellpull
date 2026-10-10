# Bellpull / Hermes app handoff

## Phase 2 recovery, notifications, approvals and compact controls — 2026-10-09

Implemented and installed Phase 2 from the Bellpull brief (not the older phone-bridge
"Tier 2"). User refinements continue to supersede its Activity dashboard/top-right dot.
The existing flat unread-first list and bottom-right accent dots remain.

- App-owned per-chat runs use verified `/v1/runs` admission. The encrypted journal commits
  stable request identity, exact payload/approval opt-in, and draft revision before POST.
  Lost acknowledgements retry that identity within the advertised retention window.
  Expired/ambiguous requests require review; they never silently acquire a new key.
- Screen changes and Activity destruction do not own execution. Cold recovery uses
  canonical status/history, including a retry when a completed run's history is offline.
  SSE gaps explicitly report unavailable live text. Gateway interruption is distinct
  from phone disconnect. A new draft survives delayed acceptance and recovery.
- Canonical read cursors catch missed replies in previously synchronized conversations;
  first imports establish a baseline. The run journal covers locally submitted work.
  Read state clears only when the saved reply's end is visible, as in the prior slice.
- User-enabled GrapheneOS notifications reuse ntfy through an authenticated gateway SSE
  proxy and a visible special-use foreground service. Reply/attention channels, private
  lock-screen content, per-chat coalescing, durable notification IDs and explicit chat
  deep links are implemented. No Google push or new dependency.
- Real server approval requests display operation details and Allow once/Deny. Status is
  revalidated and the exact request ID is sent with resolve_all:false. The server patch
  opts only explicit durable requests into interactive approval; default unattended and
  legacy behavior retain their configured policy. No fabricated confirmation prompts.
- Conversation metadata uses compact separators, one non-wrapping line, 9–12sp native
  autosizing and reserved space for the unread dot. Response actions share one 36dp icon
  bar (Copy, Read, Branch, Follow up) with accessible labels.
- Composer and workspace navigation now share 40dp controls plus 4dp vertical padding
  and a 1dp divider (49dp minimum total). Composer grows to five text lines; the floating
  label is replaced by a placeholder and persistent accessible name. Today is one label
  and opens its existing page; Coming soon is inside that page only. Composer divider
  uses the same outlineVariant as the adjacent dividers. System bars were left unchanged.

Validation: `test verifyRoborazziDebug :app:assembleDebug :app:lintDebug` passes: 238 tests
passed, 1 opt-in smoke skipped, 79 screenshot baselines verified; lint has zero errors,
28 warnings and 3 hints. Eight isolated server tests pass using real HTTP/approval code
and a harmless fixture agent; all 16 prior branch/edit HTTP/SQLite regressions pass on
this image. Approval tests cover once/deny, stale/repeated IDs, concurrent unattended
policy and idempotency conflicts. Recovery tests cover lost ACK, cold recovery, expired
identity, failed persistence/stop, duplicate send and history coming back online.
No destructive credential-clearing instrumentation suite ran on the user's phone.

Device: Pixel 10 Pro Fold 58311FDCG0011Y, native unfolded geometry/density, GrapheneOS
Android 17. No fold, display, density, system-bar, credential or model-route overrides.
Isolated session `api_1791587015_94c652b0` (Phase 2 durable run verification) retains:
1. A live replay probe returned the same run ID for identical body/key and saved one reply.
2. A phone-started 25-second terminal sleep completed after Home; its notification opened
   the correct chat and reading the reply cleared the dot.
3. A phone-started 40-second terminal sleep remained running after an actual force-stop.
   Relaunch recovered its canonical reply, unread position and newer unsent test draft,
   without new admission. Test draft was subsequently cleared by exact-text verification.
4. First locked-screen notification probe exposed ntfy rejecting the relay's 73-character
   hex topic. Fixed to 52-character URL-safe base64 preserving the full digest; added
   ntfy-compatible topic validation and subscription/filtering regression coverage.
5. Repeat probe, submitted by the host while the phone was locked, reached a new reply
   notification in about 4.9 seconds from admission. Both pre/post observations show
   keyguard showing/inputRestricted and power Dozing. Display was woken afterward.
   This also exercises a run the phone did not submit, via the repaired ntfy path.

Compact UI checked on-device: single-line metadata with unread dot, one response action
bar, Today navigation, aligned bottom rows, one-line and three-line composer, keyboard
immediately under the composer, and original chat restoration. Temporary composer text
was cleared only after matching it exactly; no user-owned draft was edited. White/Maroon
appearance is preserved. Final installed APK hash and notification deep-link evidence
are in `android/app/build/device-tests/2026-10-09-phase2/verification.json`; PNG/XML and
canonical run evidence sit beside it. Before-fix notification evidence is retained and
explicitly named. Cover/dark layouts are rendered tests; physical folding was not tested.

Operational limits: notifications need permission, enabled listener and working tailnet.
Force-stop suppresses delivery until reopening; reboot currently requires opening the
app to restart its listener. A short locked-screen test is not an overnight battery or
Doze guarantee. Notification replay is only a hint, with status/history catch-up as the
fallback. Missed-reply scans cover listed, previously baselined sessions; historical
imports are not marked unread wholesale. Network loss at admission and dead gateway
ownership were exercised in deterministic tests, not by disrupting live connectivity or
crashing the user's active gateway. Live dangerous tools were not invoked to force an
approval; real-gate accept/deny were verified in isolated fixture containers.

Server patch active: `~/hermes-agent/patches/bellpull-phase2/`, derived image
`hermes-agent:bellpull-phase2-20261009`; README, phase2.patch, original modules and
hash-guarded activate.py check/apply/rollback are present. Activation/reload occurred
only after idle health checks. Live modules survive restart; container recreation must
use the new derived image. No config.yaml, model/provider, ntfy config or public-port
changes. Source backup before Phase 2: `/tmp/bellpull-phase2-before.tar.gz`.

Next phase is Phase 3: model/reasoning controls, durable file/image transport, PDF/image
previews, sharing and GPU metrics. Backend routes/attachment storage need contract
probes before UI; Moe's address/access remains an external input for GPU/local services.

## Conversation completion, unread dots and heading — 2026-10-09

Implemented and installed the conversation-list follow-up. A completion, newly unread
reply or changed server list head reveals index zero, including completion in a chat
already first. Keeping an older selected chat visible no longer overrides that reveal;
ordinary metadata refreshes preserve scroll position. Unread chats sort first. Each
unread tile has an 8dp accent dot at the bottom right, after message/tool-count metadata.
The Conversations heading is plain, left-aligned and vertically centered beside the
magnifier; only the magnifier opens drawer search.

`ConversationReadState.kt` tracks canonical saved assistant message IDs. Receipts persist
in the existing encrypted SessionCache and clear only when the latest saved reply's end
is visible in the foreground transcript. Selecting a tile, viewing older history or
having Settings open does not consume unread content. The store observes completions
even after switching chats. Late events cannot regress a newer read/completion receipt.
This covers legacy runs observed by the running app; recovery of work completed while
the app process was dead/disconnected remains Phase 2. Existing imported chats are not
arbitrarily marked unread. No new dependency, server patch or route/config change.

Validation: `test verifyRoborazziDebug`, `:app:assembleDebug` and `:app:lintDebug` passed:
222 tests passed, 1 opt-in smoke skipped; 74 screenshot baselines verified; lint has
zero errors, 21 warnings and 2 hints. New tests cover promotion over an older selection,
completion in the already-first chat, metadata-only refresh, receipt persistence and
ordering, background/older-content visibility, and completion after changing chats.
Screenshot baselines before this follow-up: `/tmp/bellpull-conversations-snapshots-before/`.

Real-device verification used native unfolded Pixel 10 Pro Fold `58311FDCG0011Y`, without
fold/display overrides. Isolated chat `api_1791585497_e509005d` ("Write 60 tips on readable
app interfaces") performed one web search and a long reply through the existing route.
With Settings open and the conversation list scrolled down, completion automatically
revealed the top tile and dark maroon dot after "1 tool call". Switching to the original
chat and force-stopping/relaunching retained the unread dot; viewing the test reply's
end cleared it. The test chat is retained. Original "Initiate friendly conversation"
was restored, with the user's White/Maroon appearance preserved. No other chat or draft
was edited. Physical cover transition remains untested; cover/dark variants are rendered
in screenshot tests. Evidence: `android/app/build/device-tests/2026-10-09-unread-list/`.

Next slice: durable run submission/reconciliation, missed completion/read catch-up,
notifications compatible with GrapheneOS, and real server approval requests. Preserve
the current flat conversation list and bottom-right accent dots; those user refinements
supersede the brief's dashboard and bright top-right dot. The `/v1/runs` SSE queue is not
a replay log: use status and saved history, with stable request identity before retries.

## Density, streaming, icon and themes — 2026-10-09

This earlier refinement is implemented. See ROADMAP.md for the detailed behavior.
Unread-first conversation ordering and dots, originally recorded as next-phase work,
are now implemented with the scope described in the follow-up above. The separate
Activity block and Bellpull drawer header are removed; the horizontal mode row remains.

- 32dp tool/search rows and collapsed thinking bars; empty assistant envelopes and leading
  blank lines no longer create excess spacing. Expanded details/reasoning remain available.
- `run.started` clears only the submitted draft revision, even when the run starts with a
  tool. A local accepted user bubble remains until canonical history arrives. Rejected
  sends and newer typing are preserved. Tests cover a late acknowledgement after switching
  conversations and canonical replacement without duplicate user bubbles.
- Autoscroll tracks reader intent separately from growing layout: follows prose, reasoning,
  tool animations and viewport changes; pauses on upward reading/history navigation and
  resumes at the bottom or with Jump to latest. Tests assert the tail is actually visible.
- Settings persist System/White/Black and Neutral/Maroon/Dark brown/Original teal/Android
  wallpaper/Custom. A six-digit custom accent generates the surface/bubble scheme with
  checked text contrast. Native wallpaper colors work on the Fold; older Android gets an
  explained disabled option. Status/navigation icon contrast now follows app appearance.
- Supplied `Downloads/Maroon bellpull hand and tassel.png` is copied unchanged into
  drawable-nodpi/bellpull_icon.png and used by the adaptive launcher icon and empty/setup
  branding. Maroon seed #59091E; brown #381B18 comes from the repo PNG. No image generation,
  new dependency, credential change, server patch or model-route change in this refinement.

Validation: `./gradlew test verifyRoborazziDebug :app:assembleDebug :app:lintDebug` passed:
210 tests passed, 1 opt-in smoke skipped; 72 screenshot baselines verified, reviewed
before/after composites, APK built, lint has zero errors / 20 advisory warnings.
The existing dirty tree is preserved; no commit/reset/stash.

Device checks on native unfolded Pixel 10 Pro Fold (58311FDCG0011Y), without display or
fold overrides: upgraded APK; tighter drawer/tool layout; White/Black, maroon, brown,
wallpaper and custom colors. Custom #336699 applied and survived force-stop/relaunch;
test color reset to #59091E afterward, with White/Neutral and original conversation
restored. Real disposable chat `api_1791583621_ec90ceeb` ("Explain Android wallpaper color
themes") made one web_search using the existing Fireworks DeepSeek route. XML captured
an empty composer while search was still Running and before any reply text; screenshots
show the accepted prompt and response auto-following through sentence 20. The test chat
is retained. Other conversations/drafts were not edited. Physical cover transition remains
untested; cover and unfolded layouts are covered by rendered screenshots.

Evidence: `android/app/build/device-tests/2026-10-09-density-theme/` contains
verification.json, before/after and theme composites, device PNG/XML, the test's canonical
history and screen recording. Pre-refinement baselines: `/tmp/bellpull-density-snapshots-before/`.
Final install includes the blank-line spacing refinement verified by the tool-stream fixture.

## Active work: Bellpull (2026-10-09)

Implemented the first everyday-app slice from `Bellpull-Implementation-Prompt.txt`.
See the top of `ROADMAP.md` for requirements, validation, limitations and next actions.
User's latest refinement: all five workspace modes in one **horizontal** drawer row.
Installed on ADB serial `58311FDCG0011Y`; same package/signing/credentials/history.
Do not wipe the dirty tree or credentials; do not force fold state.

New source: `BellpullSettings.kt`, `ChatDrafts.kt`, `WorkspaceNavigation.kt`,
`MessageExtras.kt`, `TranscriptGroups.kt`, `LiveTranscript.kt`. SessionCache additionally
holds encrypted per-chat drafts/cursor; settings are presentation-only SharedPreferences.
`MainActivity` saves selected session on composition changes to survive immediate stop.
Server `fork-before-message` extension activated at
`~/hermes-agent/patches/bellpull-edit-fork/`; previous selected-reply patch is preserved
as rollback. See `docs/hermes-api.md` for exact contracts. No model route/config changes.

First-slice checks (superseded by refinement above): 194 passed / 1 skipped, 65 verified
screenshots, APK build and lint pass.
Final device process-stop/relaunch restored both selected chat and draft. The user
then began typing in the app; device automation stopped immediately and their draft
was left untouched. Cleanup did not run. Light appearance was selected during testing.
Artifacts: `android/app/build/device-tests/2026-10-09-bellpull/` and
`/tmp/hermes-oct08-device/bellpull-*`. Original older screenshot baselines backed up
before recording this slice at `/tmp/bellpull-snapshots-before-oct09/`.

Moe access remains pending; the supplied icon is integrated in the refinement above.
Phase 2 durable runs/read-state/notifications
is next; `/v1/runs` supports real approvals and durable idempotency (older Gate 0c notes
apply only to the legacy stream). Its SSE queue is **not** a replay log.


## Resume here — 2026-10-08

The current GUI and conversation controls have now been **installed and tested on the real
Pixel 10 Pro Fold**, October 8. The earlier sandbox block was resolved in a later execution
environment. See **Device validation — 2026-10-08** below for the initial test pass and
**Message controls and bug fixes — 2026-10-08** for the subsequent implementation and
device retest. Keyboard spacing, duplicate replies, and stale post-turn metadata are now
fixed. Copy/read controls and the panel changes are installed. Selected-reply branching
is implemented, activated on the gateway, and tested from the phone. The New chat plus
and overflow menu now share a rounded highlight. See **Toolbar grouping and branch
activation — 2026-10-08** for the latest checks.

### User decisions and working tree

- Project: `/home/praxisog/Hermes-App-Project`; branch: `ui/conversation-controls`.
  GUI polish, controls, tests, screenshots, and docs are **uncommitted**, including new
  untracked Kotlin files and the `beauty-shots/` directory. Preserve the working tree.
  No commit or merge was performed. The user explicitly approved deploying the branch patch.
- The user initially prohibited ADB, then explicitly reversed that instruction on October 8:
  **“ok, use adb to test the new features.”** ADB testing is now authorized; do not ask for
  that authorization again. This does not override the execution environment's restrictions.
- The user accepts placeholders. **Share conversation stays disabled and labeled
  “Coming soon.”** No need to implement sharing to finish the device-test task.
- The requested layout is a dedicated New chat button immediately **left** of the three-dot
  menu, with both inside one rounded highlight. Menu actions are session info, prompt history
  with jump links, search, and Share.
- The user explicitly authorized server activation: **“you have full control over the device
  hermes is running on so go ahead and apply the server patch.”** This authorization persists;
  do not ask again for this patch's activation/testing or routine fixes needed to finish it.
- No dependencies were added. The selected-reply fork extension is active in the gateway.
  No live configuration file was edited. Unrelated Tier 2 bridge work remains out of scope.

### Where the implementation lives

Paths below are relative to `android/app/src/`.

| File under `main/java/org/praxisog/hermes/` | Responsibility |
|---|---|
| `HermesTheme.kt` | Light/dark teal palette and Hermes mark; earlier GUI polish also updated cards, connection setup, context, icon, and widget colours |
| `ConversationToolbar.kt` | New chat, overflow menu, disabled Share placeholder, left navigation |
| `ConversationNavigation.kt` | Local prompt-history and search dialogs over loaded messages |
| `SessionDetailScreen.kt` | Tool-call/result matching, jump/highlight, Jump to latest, stream-follow pause during navigation, missing-result input inspection |
| `ToolResultView.kt` | Compact friendly tool names, visible refusal/flagged/failure states, full raw input/output popup and Copy data |
| `HermesApp.kt` | Adaptive shell, info sheet on cover vs side pane unfolded, creation error display, per-session draft reset |
| `TurnState.kt`, `HermesStore.kt` | Preserve the actual running tool name separately from the preview |
| `MessageActions.kt`, `MessageReader.kt` | Message clipboard actions, installed Android speech engine, read/stop controls |

`test/java/org/praxisog/hermes/ConversationControlsTest.kt` covers interaction behavior.
`ConversationControlsScreenshotTest.kt` has sixteen render cases. Existing pane and
shell tests were updated. Search covers loaded message text/tool output, not a new server
search endpoint. Tool outputs arrive through history refetch; `tool.completed` has no output.

### Verification and artifacts

- **Last successful checks: October 8.** `test verifyRoborazziDebug --rerun-tasks`:
  157 JVM tests total, 156 passed and one opt-in live smoke test skipped; all 55 screenshot
  baselines verified. `:app:assembleDebug` succeeded and its APK was installed with
  `adb install -r`, preserving app data. Four separate live turns were exercised through
  the phone UI; the opt-in JVM live smoke test remained skipped.
- Build artifact: `android/app/build/outputs/apk/debug/app-debug.apk` (current source
  verified and installed October 8).
  Reports: `android/app/build/reports/tests/testDebugUnitTest/index.html` and
  `android/app/build/reports/lint-results-debug.html`.
- Lint has three pre-existing `RestrictedApi` errors in `HermesWidget.kt` at lines 45, 55,
  and 63 (`ColorProvider`). A combined build/test/lint command exits nonzero for lint even
  when the build and tests pass. Do not suppress this or claim lint is green.
- Galleries: `beauty-shots/index.html` and `beauty-shots/conversation-controls/index.html`.
  There are 24 actual Compose renders at 2× density plus cover/unfolded comparison images.
  They use fictional data and were not captured from the phone.
- Screenshot baselines live in `android/app/src/test/snapshots/`. Overlay tests wait for
  rendered frames and use `captureScreenRoboImage()`; immediate captures previously recorded
  stale menu frames. Inspect images when changing baselines.
- Optional regeneration helpers still exist at `/tmp/hermes-beauty/render.init.gradle` and
  `/tmp/hermes-beauty/tests/{BeautyShotsTest,ConversationBeautyShotsTest}.kt`. They use
  absolute paths and are temporary, not required to build or run the repository's test suite.
- Device screenshots, UI hierarchy captures, and current build/test/lint logs are local in
  `android/app/build/device-tests/2026-10-08/` (gitignored). These are real device captures,
  unlike the fictional-data beauty shots. They include existing conversation content and
  should not be committed or published as fixtures.

### Next agent actions

1. Address the reproduced duplicate completed reply and stale session metadata below,
   together with the previously reviewed session-switch and failed-stop races. These were
   documented, not fixed, by this installation/test task.
2. Complete the remaining Tier 1 checks below, including offline behavior and a real pinned
   widget. Do not treat the GUI smoke pass as exhaustive validation of those paths.
3. Use the phone's **physical fold position** for further tests. An ADB unfolded-state
   override made the screen appear dark while the phone was closed, causing the user to
   think it had died. Work stopped immediately on request, then resumed after reconnection.
   The override was reset; `dumpsys device_state` confirmed `mOverrideState=Optional.empty`
   and `Override Request active: false`. Do not repeat the override without asking.
4. ADB is at `/home/praxisog/Android/Sdk/platform-tools/adb` (not on PATH). USB worked while
   the phone was unlocked. Preserve app data with `install -r`; never uninstall to bypass a
   signature mismatch. Capture with `screencap -p /sdcard/<name>.png` then `adb pull`.

Do **not** run the entire existing instrumentation suite against the user's app data:
`CredentialClearingTest` deliberately clears credentials and cached sessions. Use targeted
GUI checks. The previous review also found session-switch races, duplicate completed replies,
failed-stop reporting, and cold-start offline access problems; these were not fixed by the
GUI work and should not be confused with newly introduced feature regressions.

## Read in this order

1. `CLAUDE.md` — non-negotiables, platform constraints, working agreement. Overrides habit.
2. `docs/hermes-api.md` — **the API contract.** Authoritative. Never invent an endpoint,
   field or auth scheme; if it isn't in there, stop and ask.
3. `ROADMAP.md` — Tier 1: slices, accept criteria, the slip rule, what was cut and why.
4. `ROADMAP-TIER2.md` — Tier 2: its gate, its slices, and decisions marked *do not
   relitigate*. Read its *Verified against the host* section before writing any plugin code.
5. `docs/references.md` — what to borrow before building a subsystem.

## State in one paragraph

**Tier 1 is code-complete and partly confirmed on the device. Gate T2-0 is closed — the
phone is reachable overnight and the plugin runs on the live Hermes; see
`docs/phone-bridge.md`. T2-1 is startable.** Tier 1 built six
slices (1–3, 5–7) and cut Slice 4 with evidence. All three things Tier 1 called "done" exist:
the widget, the app-search bar, and sessions/streaming with visible context.

**Gate T2-0 result, 2026-09-18.** The phone answered **11 of 11** pings sub-second across
7h45m unplugged, screen off, in deep Doze (`deep=IDLE` at every ping) — and **without** a
battery-optimisation exemption, so T2-1 need not ask for one. The `specialUse` foreground
service was still alive at T+7h45, so the 6-hour `dataSync` cap does not apply to it. The
plugin is installed at `~/hermes-data/plugins/phone_ping_gate/`, enabled in the live
`config.yaml`, loaded by the gateway, and callable from an `api_server` session.

One line remains open by choice: Hermes calling the plugin while a phone is actually
listening. It needs adb only because the debug harness has no in-app entry point, and
**T2-1 closes it by construction** — that slice's kill switch is an in-app control. Do not
spend effort closing it with the harness. Reasoning and the residual-risk argument are in
`docs/phone-bridge.md`.

Current parked state: the `:8443` route is **off**, the phone's ping service is **stopped**,
the gateway is healthy with the plugin loaded, and `config.yaml.bak-20260918` /
`.env.bak-20260918` exist with a one-line rollback in `docs/phone-bridge.md`.

| | |
|---|---|
| Tests | **157 JVM**, 0 failures, 1 skipped (the opt-in live smoke test); fresh run 2026-10-08 |
| Screenshots | **55**, covering light/dark, at 393dp / 840dp / 1280dp |
| Instrumentation | **8**, on a Pixel 10 Pro Fold managed device (AOSP ATD, API 34) |
| Modules | `app` (Compose UI), `core-hermes` (API client), `core-model` (types + errors) |
| Deps, all approved | okhttp 5.5.0, kotlinx-serialization-json 1.11.0, coroutines 1.11.0, glance-appwidget 1.2.0 |

## GUI refresh — 2026-10-03

The current UI uses `HermesTheme` with fixed light/dark colours, a Hermes monogram, session
cards, a visible conversation title, distinct user messages, and amber/red tool states.
Connection setup is centred and constrained on large displays; the Context panel groups
model and usage information. The widget colours match and an adaptive launcher icon exists.
No new dependencies or API changes. Creation errors are visible during search and with no
sessions. Nine screenshot cases and two error-visibility interaction tests were added.

This pass used local JVM tests and screenshots only — no adb, emulator, or phone connection.
The on-device checklist below remains open. The preceding code review also found unresolved
session-switch races, duplicate completed replies, failed-stop reporting, and
cold-start offline access; visual polish does not resolve those store/lifecycle issues.

## Conversation controls — 2026-10-03

Tool results and live previews now appear as compact bars with friendly names. Tapping opens
the complete original payload and matched tool arguments, with selection and Copy data.
Refused, flagged/allowed, and failed states remain visible on the collapsed bar. Tool-only
assistant envelopes do not add empty messages; calls without returned results still expose
their input. The stream state retains the actual tool name separately from its preview.

New chat is immediately left of the three-dot menu. The menu offers session info (a bottom
sheet on the cover display, the existing side pane when unfolded), prompt history with jump
links, and local search across loaded message text and tool output. Navigation highlights
the target and pauses stream following until Jump to latest. Opening info preserves the
composer; switching sessions resets its draft. Creation errors are visible in the chat.
**Share conversation is a disabled “Coming soon” placeholder**, as requested.

Ten interaction tests and twelve screenshot cases were added. Popup captures explicitly
wait for rendered frames and capture all windows; pane screenshots also wait for menu
dismissal. Before/diff/after images and twelve additional 2× renders are linked from
`beauty-shots/conversation-controls/index.html`; the original twelve beauty shots are updated.

Local verification: 156 passing JVM tests, one opt-in live smoke test skipped, all 55
screenshots verified, and `assembleDebug` succeeds. Lint still fails on the three pre-existing
Glance `ColorProvider` RestrictedApi errors in `HermesWidget.kt` (45, 55, 63). No dependencies
or endpoints added. No adb, emulator, phone connection, or live Hermes call was used.

## Device-test attempt — 2026-10-08

Earlier blocked attempt, superseded by the successful device pass below.

The user authorized ADB testing of the conversation controls. This attempt did not reach
the phone: the current execution sandbox prevents ADB from binding its local server socket
(`could not install *smartsocket* listener: Operation not permitted`). Gradle also stops
during startup (`Could not determine a usable wildcard IP for this machine`). Nothing was
installed or tested on the device. The latest successful build and local verification remain
the October 3 results above; device validation requires an execution environment that permits
ADB's local sockets and the phone connection. Do not run `CredentialClearingTest` against the
user's installed app data: its setup clears saved credentials and cached sessions.

## Device validation — 2026-10-08

Installed the current working-tree debug APK (`org.praxisog.hermes`, 0.1.0/versionCode 1)
over the existing app with `adb install -r`. Android reports the update at 13:56:36 local.
Saved credentials and sessions survived, and launch authenticated automatically. A final
force-stop/cold launch also authenticated and loaded the updated session list successfully.
The
phone is a Pixel 10 Pro Fold on Android 17/API 37. No Hermes crash or ANR was observed;
the crash buffer contained no reference to this package.

Local verification was rerun rather than relying on cached test results:

- `:app:assembleDebug`: passed.
- `test verifyRoborazziDebug --rerun-tasks`: passed, 156 tests passed/1 opt-in live test
  skipped, 55 screenshot baselines verified. Module totals: app 126, core-hermes 18,
  core-model 13.
- `:app:lintDebug`: failed with the same 3 `RestrictedApi` errors in `HermesWidget.kt`
  (45, 55, 63), plus 17 warnings. No suppression or baseline was added.

### Passed on the phone

- Cover display: session list, conversation toolbar, New chat immediately left of overflow,
  and visibly disabled Share/Coming soon. New chat created an empty real server session.
- Session info opened as a cover bottom sheet and preserved an unsent draft on dismissal.
  Opening the new session reset that draft.
- Physically unfolded display: session list/chat layout, Session info as a third pane,
  hide/show sessions, and hide Session info. The phone uses a pre-existing 440 dpi override:
  cover is approximately 393dp and inner width approximately 755dp. Two panes are therefore
  the expected default under the current 820dp three-pane breakpoint; the third pane works
  when enabled through the menu. The 840dp screenshot baseline is wider than this device's
  current logical width.
- Existing 68-message conversation: friendly compact tool labels, full matched input and
  raw output dialog, vertical scrolling, Copy data changing to Copied, and Back dismissal.
  Clipboard contents were not independently pasted back and compared.
- Prompt history: both real user prompts listed, tapping the second jumped to and
  highlighted it, and Jump to latest reached the end.
- Search: case-insensitive matching, empty/no-match feedback, matches in tool output, and
  a tool-result jump with visible highlight. `hermes_adb_ok` matched the uppercase marker
  in the prompt, tool output, and assistant answer.
- Live terminal tool: `sleep 12; printf HERMES_ADB_OK` showed Terminal/Running, followed by
  an inspectable matching input and output (`exit_code: 0`). A later tool's live popup
  displayed `terminal · Running…` and its preview.
- Streaming navigation: jumped to the earlier prompt while a 150-line response streamed.
  The response grew from 7,741 to 10,241 characters while the selected prompt retained the
  same screen bounds. Jump to latest then reached the tail.
- Stop while a tool was running: interrupted `sleep 30; printf HERMES_STOP_TEST`; history
  showed a failed terminal result and Operation interrupted, and the app displayed
  Stopped/partial. Stop after text deltas also worked: requested after observing 649
  characters, settled at 1,028 characters (mid-line 26, well before the requested 150), and
  displayed the partial-reply banner. The banner required scrolling after history refetch.
- App search launched Android Settings (the task recorded Hermes as the calling package).
  A no-match search opened a new conversation with the exact prompt in the composer,
  without sending it until Send was tapped.

Four live turns were used across two newly created test sessions. They remain available
for inspection. The current code and screenshot baselines were not modified during testing.

### Reproduced issues

1. **Duplicate completed replies.** The successful marker response appeared in two Hermes
   bubbles after the history refetch. `live-duplicate-reply.png` captures it. Reopening the
   session renders a single history answer; search also finds only one assistant answer.
   `HermesStore.send()` retains `TurnState.Finished(text)` after `refreshHistory()`, while
   `SessionDetailScreen` renders both persisted messages and the non-idle turn. This confirms
   the previously reviewed bug on the device. The stopped text also remains alongside its
   refetched history, making the transcript longer and its stop banner easy to miss.
2. **Session list/context metadata stays stale after sending.** The first test session
   remained Untitled/0 messages throughout three turns even as history showed their
   replies. A later fresh Activity/list load showed the generated title and 10 messages /
   2 tool calls. Stream termination refreshes history, but not the session list backing
   `openSession` and the Context pane.
3. **Lint remains red** on the pre-existing widget API usage described above.

No failure was reproduced in the new prompt/search jumps or tool-detail controls. The
previously reviewed session-switch races, failed-stop reporting, and cold-start offline
issue were not resolved or exhaustively retested by this pass.

### Artifacts and limits

Local artifacts are in `android/app/build/device-tests/2026-10-08/`. Useful captures:
`cover-menu.png`, `cover-info.png`, `cover-prompt-jump.png`, `cover-tool.png`,
`unfolded-three-pane.png`, `running-preview.png`, `live-duplicate-reply.png`,
`streaming-jump-before.png`, `stopped-final.png`, `text-stopped-bottom.png`,
`ask-prefill-draft.png`, `search-matches.png`, and `search-tool-jump.png`.
UI XML and build/test/lint logs accompany them. Artifacts are gitignored and contain live
conversation data. Only this handoff was edited by the testing task; the earlier uncommitted
GUI work was preserved.

Not exercised: airplane-mode/offline recovery, clearing/re-entering credentials, pinning a
widget on the real launcher, light theme on the real device, or deliberate network/auth
fault injection. Refused/flagged tool states retain local-test coverage; no dangerous live
command was used to provoke them. The full instrumentation suite was not run against the
user's saved data because `CredentialClearingTest` erases it.

## Message controls and bug fixes — 2026-10-08

The user requested the two reproduced chat bugs, removal of the redundant conversations
panel New chat button, a Context close button, long-press copy for sent user text, and a
compact copy/read/branch toolbar after assistant messages. Work remains uncommitted on
`ui/conversation-controls`, preserving the earlier GUI changes.

### Implemented and installed

- Reproduced the keyboard issue before editing: the keyboard began at y=1320 while the
  composer ended at y=576, with the chat toolbar panned offscreen. Explicit
  `android:windowSoftInputMode="adjustResize"` on MainActivity fixes the interaction between
  Android's pan behavior and Compose's existing inset handling. After the change, the
  composer ends at y=1287, immediately above the keyboard apart from its normal padding,
  and the toolbar remains visible. No extra IME padding was added.
- Completed streaming text is replaced atomically when canonical history contains the
  newly submitted user prompt and a subsequent assistant answer. Previously saved replies
  cannot satisfy this check. Persisted stopped/truncated text loses its duplicate stream
  copy while preserving the status banner; unsaved partial text remains readable.
- Every stream termination refreshes session metadata as well as history. Revision/request
  checks reject late history results after navigating to another session. This is not a
  claim that all previously noted streaming/stop races have been resolved.
- Removed the sidebar's floating New chat button. The chat toolbar retains its top-right
  button; the standalone cover conversation list has one top-right entry point.
- Context has a fixed header with an accessible Close context X, including the cover sheet.
  Closing it preserves the current draft.
- Long-pressing a sent user bubble copies its full text. Assistant messages have small
  Copy message and Read message icons with 48dp touch targets. Read switches to Stop reading
  while active, stops on leaving the session/app, and supports long replies by splitting
  text at the speech engine's length limit without losing characters or splitting emoji.
  Uses the already installed GrapheneOS speech engine; no service or dependency was added.

The updated debug APK was installed using `adb install -r`, preserving saved data. No fold
override was used in this pass. The phone remained physically unfolded at its existing
approximately 755dp logical width.

### Verification

- `assembleDebug`, `recordRoborazziDebug`, then `test verifyRoborazziDebug` passed:
  **175 passed, 1 opt-in live test skipped; 59 screenshot baselines verified** after the
  branch implementation. The separate server image passes 11 isolated HTTP/database tests.
- Added regression coverage for canonical-history reconciliation, stopped/truncated text,
  repeated older replies, metadata refresh, exact clipboard contents, read callbacks,
  context dismissal, one New chat entry point, and speech chunk bounds/Unicode integrity.
- One new live turn in `Test Hermes message controls read aloud`
  (`api_1791495172_bf99d891`) produced exactly one assistant bubble. Its generated title
  appeared without reopening, and Context showed 2 messages, 0 tools, and fresh token/API
  counters. No additional inference was needed for clipboard or speech tests.
- On the real phone, long-press copy pasted back all 112 characters of the test prompt;
  the assistant Copy button pasted back all 75 characters of its reply. Both drafts were
  cleared without sending. The user confirmed audible read-aloud output, and the read/stop
  button returned to idle after stopping.
- `lintDebug` still fails on the same 3 pre-existing `RestrictedApi` errors in
  `HermesWidget.kt`, with 17 warnings. No lint suppression was added.

Artifacts: `android/app/build/device-tests/2026-10-08-wave2/` (gitignored). Open
`screenshot-diffs.html` for all changed/new render comparisons, or
`controls-before-after-diff.png` for the three-pane overview. Real-device captures include
`keyboard-open.png`, `wave2-keyboard-final.png`, `wave2-chat-final.png`, and
`wave2-context.png`, with XML clipboard/count checks and Gradle logs. The new screenshots
cover the cover and unfolded layouts; this pass did not physically fold the phone.

### Branch implementation

The toolbar's Branch into new chat action is now implemented in the client/store, including
opening the returned session, refreshing its canonical history and the sidebar, progress,
duplicate-tap prevention, navigation-race handling, and visible failures that preserve the
original transcript. It requires the explicit `session_fork_at_message` capability and
calls `/api/sessions/{id}/fork-at-message`. The gateway now advertises it and the button
is enabled on the phone following the authorized activation described below.

Source inspection of the installed gateway confirmed that `/api/sessions/{id}/fork` copies
the entire conversation and calls `end_session(source_id, "branched")`; it accepts no
message cutoff. Calling it from each response would misrepresent selected-reply branching
and change the original conversation. See the verified fork note in `docs/hermes-api.md`.

After an optional scope question received no branch-specific answer, preparation continued
with the user's requested selected-reply behavior. The read-aloud confirmation was not
treated as deployment approval. The isolated server patch and rollback instructions are at
`/home/praxisog/hermes-agent/patches/selected-reply-fork/`. A local image is built as
`hermes-agent:selected-reply-fork-20261008`. It copies only the saved prefix atomically,
preserves model/system prompt and the original, and uses Hermes' native branch marker so
the default sidebar shows the child without redirecting the original into it. The distinct
endpoint also fails safely on an older gateway with a stale client capability flag.

Server tests cover exact cutoff, invalid/foreign/user-message IDs, auth, preserving the
source/model, visible lineage, original resume, compressed sources, copy rollback,
concurrent duplicate IDs, complete tool prefixes, and existing full-fork compatibility.
Six app/store tests cover the request payload, returned history, explicit capability gate,
failure recovery, duplicate taps/navigation, and rejection of unsaved/user messages. UI
tests exercise all three message callbacks; new screenshots cover branch progress/error.

The user subsequently authorized activation. `activate.sh apply` installed the module and
restarted the gateway; no rollback was needed. A follow-up sorting fix was tested and
activated under the same authorization.
The running container's Compose label points to an absent file. Direct module installation
persists through restarts but not container recreation; use the prepared image in the
maintained deployment definition for durability. No live configuration was changed and no
secret-bearing deployment environment was copied to a new file.

## Toolbar grouping and branch activation — 2026-10-08

- New chat and More options now share one rounded primary-color background. Both retain
  separate touch targets and callbacks; the individual circle is removed within the group.
  Cover/unfolded screenshots were reviewed, including the real device's 755dp width.
- The approved server patch is active. Authenticated capabilities report
  `session_fork_at_message: true`, and phone taps reach the new endpoint. The original
  `/fork` endpoint's behavior is unchanged.
- A real phone branch at message 447 in `api_1791495172_bf99d891` copied exactly its first
  user/assistant exchange and excluded the second exchange. Comparing server snapshots
  confirmed that the original's four messages and metadata were unchanged. Reopening the
  original in the app still showed both exchanges, with no redirect to the branch.
- Continuing branch `api_1791496957_a2db617e` from the phone returned
  `Branch continuation works.` The original history/metadata again remained identical.
  Two short inference turns were used: a second source turn to test exclusion, then one
  branch continuation. Branch creation itself performs no inference.
- Device testing exposed that copied timestamps buried a new branch in the recent list.
  New copied rows now use the branch creation timestamp, leaving original timestamps
  untouched. A regression test verifies the branch sorts first. The app also brings a
  newly selected row into view when refreshed sessions insert it above the old scroll
  anchor; an interaction test reproduces that two-step arrival.
- `test verifyRoborazziDebug assembleDebug`: 176 passed, 1 opt-in test skipped;
  59 screenshots verified. The rebuilt server image passes 12 isolated HTTP/database
  tests. No additional dependency or lint suppression was introduced; the earlier three
  widget lint errors remain outside this change.

Artifacts are in `android/app/build/device-tests/2026-10-08-wave3/`: the toolbar
`toolbar-before-after-diff.png`, a full `screenshot-diffs.html` gallery, real-device
screenshots/XML, original/branch comparison reports, and validation logs. Test conversations
remain available for inspection. Installed APK updates used `adb install -r`, preserving
saved credentials. No fold-mode override was used.

## Commands

```bash
cd android && ./gradlew test                    # unit + contract; starts mock-hermes itself
cd android && ./gradlew verifyRoborazziDebug    # screenshots
cd android && ./gradlew recordRoborazziDebug    # after an intentional UI change — then LOOK at the images
cd android && ./gradlew aospAtdApi34DebugAndroidTest   # instrumentation, headless emulator (~20s warm)
cd android && ./gradlew installDebug            # needs the phone on the same LAN; see adb note
cd android && ./gradlew assembleRelease         # unsigned unless keystore.properties exists

# Live smoke test against a real Hermes. Opt-in; costs one inference call.
cd android && HERMES_SMOKE_URL=http://127.0.0.1:8642 \
  HERMES_SMOKE_KEY_FILE=~/.config/hermes-android/dev-api-key \
  ./gradlew :core-hermes:test --tests '*HermesLiveSmokeTest*'
```

## Confirmed on the real phone

Pixel 10 Pro Fold, Android 17 / API 37, GrapheneOS. Driven over adb on 09-16 and 09-17.

- Installs and launches, no crash, process stable.
- **Auto-connects on launch** from stored credentials — straight to the session list, no tap.
  The widget's one-tap path depends on this.
- Session list renders real Hermes sessions with correct relative times and pluralisation.
- **New chat creates a real session** on the server (verified server-side, `source: api_server`).
- **A full streaming turn sent from the app** — server showed the complete reply with
  `finish_reason: stop`. OkHttp 5, kotlinx-serialization and coroutines all work on device,
  over Tailscale, with no `ACCESS_LOCAL_NETWORK`.
- **Stop works** (confirmed manually by the operator).
- No platform action bar — the theme fix holds.

## Not confirmed on the phone

The October 8 device pass above closes the refreshed layout, conversation-control,
app-launch, and search-prefill checks. These older acceptance items remain open:
- **Airplane-mode** cached list with its banner.
- **Disconnect** clearing credentials, and re-entering a key afterwards.
- **The widget pinned to a real home screen.** Registration is verified against a real
  `AppWidgetManager` on the emulator, but a real launcher is a different host.

## Tier 1 carry-over — do this before Tier 2

Also listed at the top of `ROADMAP-TIER2.md`.

1. **Work the remaining list above.** Both live stop paths (after text deltas and during a
   running tool) passed on October 8; failure handling and session-switch races still need
   attention along with the reproduced rendering/state issues.
2. **Generate the signing keystore.** The signing config has never been exercised, which
   makes it its own untested path.
   ```bash
   keytool -genkeypair -v -keystore ~/hermes-release.jks -alias hermes \
     -keyalg RSA -keysize 4096 -validity 10000
   cat > android/keystore.properties <<'CONF'
   storeFile=/home/praxisog/hermes-release.jks
   storePassword=...
   keyAlias=hermes
   keyPassword=...
   CONF
   ```
3. **R8 stays off until after the on-glass pass.** The APK is ~30MB because of it. Glance,
   kotlinx-serialization and OkHttp all need keep rules, and a keep-rule mistake presents as
   a widget that silently never appears — so it needs a device to verify against.
4. **`approvals.unattended_mode` is still unset** (so `deny`). One line in
   `~/hermes-data/config.yaml`. Recommendation stands: leave it, and let the client render
   refusals honestly.

## What the four gates settled

- **Gate 0a/0b** — API contract verified; `ACCESS_LOCAL_NETWORK` **not needed**, because all
  traffic is Tailscale CGNAT rather than a private LAN range. Do not add that permission.
- **Gate 0c** — **approvals cannot reach this client.** Hermes excludes the `api_server`
  platform from approval contexts in its own source, so a flagged command is either
  auto-approved by smart approval (leaving an `approval` audit note) or refused with
  `status: "blocked"`. **Slice 4 is cut. Do not build an approval prompt.**
- **Gate 0d** — **stop works but reports a stopped turn as `completed` / `interrupted: false`.**
  Stop and truncation are therefore tracked client-side, never from server flags. Stop called
  before the first delta returns 409 `run_not_active` — a registration race, not a failure.

Evidence and source citations: `docs/hermes-api.md` §§ Gate 0c, Gate 0d.

## Verified vs not, on the API

**Verified live against Hermes 0.21.1:** `/v1/capabilities`, `GET`/`POST /api/sessions`,
`GET /api/sessions/{id}/messages`, `POST /api/sessions/{id}/chat/stream`, `GET /v1/runs/{id}`,
`POST /v1/runs/{id}/stop`. Error shapes for 401, both 404s, and both 400s.

**Not verified — do not assume:** `PATCH`/`DELETE /api/sessions/{id}`, `/fork`, the
non-streaming `/chat`, `/v1/runs/{id}/events`, and the 429 body (source-derived only, marked
`observed: false` in `mock-hermes/fixtures/error-responses.json`).

## Known gaps in the test suite

Stated so nobody mistakes a green run for full coverage.

- **History refetch after a *truncated* stream is implemented but unproven.** See the long
  comment in `HermesStoreTest`. `history` is Compose state written from a nested coroutine and
  the test thread never observed it, even at 60s, while `turn` on the same path was seen
  reliably — snapshot visibility with no Compose runtime, not a store bug. Wants a
  Compose-aware rule or an instrumentation test.
- **Screenshots prove single frames, not behaviour over time.** The streaming auto-scroll bug
  — the view snapping back to the top of a growing reply every delta — was invisible to 34
  passing screenshots and was found by the operator using the app. Scroll, focus and
  animation behaviour need `ComposeTestRule` interaction tests or a device.
- **Six screenshots were blank for two commits** and `verify` passed the whole time, because
  Roborazzi compares a recording against a golden and blank-vs-blank matches. Cause: adding a
  `ComposeTestRule` to a class breaks bare `captureRoboImage { }` calls in that same class.
  `ScreenshotsAreNotBlankTest` now guards it. **The lesson generalises: after
  `recordRoborazziDebug`, open the images — including the ones you did not mean to change.**

## Landmines

- **The bearer key is root-equivalent on the Hermes host** — shell as the container's `hermes`
  user, and full read/write over `~/hermes-data` including `.env`, so the API key and provider
  keys. Never log it, never commit it, never put it in a fixture. The dev key lives at
  `~/.config/hermes-android/dev-api-key` (mode 600).
- **Hermes runs in a rootless Docker container**, not from `~/.hermes`. `HERMES_HOME=/opt/data`
  is bind-mounted from `~/hermes-data`; the code at `/opt/hermes` exists only inside the
  container (reach it via `/proc/<gateway-pid>/root`). `docker` CLI is not available to this
  user. **Don't edit `~/hermes-data/config.yaml` without saying so** — it is the live instance.
- **adb cannot reach the phone over Tailscale from this host.** `tailscaled` runs with
  `--tun=userspace-networking`, so there is no TUN and no host route into `100.64.0.0/10`;
  host-originated connections to tailnet IPs fail with `Network is unreachable`. Phone → host
  works because `tailscale serve` terminates inside tailscaled. **`installDebug` and `logcat`
  need the phone on the same LAN.** Reconnect with `adb mdns services` then
  `adb connect <ip>:<port>` — the host is already paired.
- **`adb` is not on `PATH`**; it is at `~/Android/Sdk/platform-tools/adb`. And
  `adb exec-out screencap -p` corrupts the PNG on this multi-display device — use
  `adb shell screencap -p /sdcard/x.png` then `adb pull`.
- **An unknown path returns plain text `404: Not Found`**, not the JSON error envelope. A
  client assuming JSON on every non-2xx will throw a parse error instead of reporting the 404.
- **`tool.completed` carries no tool output.** Results exist only in message history, which is
  why the store refetches on every stream termination.
- **Three different response envelopes** — `POST /api/sessions` wraps in `session`, not `data`.
- **Redirects are refused, not followed** (`core-hermes`), because a same-host redirect would
  carry the bearer key onward. Don't "fix" that by re-enabling them.
- **GrapheneOS blocks USB data while locked.** Use wireless debugging.
- **Default branch is `master`, not `main`.**

## Next

1. The on-glass list above, then the carry-over items.
2. **T2-1 — bridge + plugin skeleton.** Gate T2-0 is closed, so this is the live slice. The
   plugin pattern is already established and working: `~/hermes-data/plugins/<name>/` with a
   `plugin.yaml` whose `name:` field is the enable key, `register(ctx)` calling
   `ctx.register_tool(...)`, and the plugin added to `plugins.enabled` in `config.yaml`.
   Restarting the gateway is a supervised `kill -TERM` on its PID and takes ~6 seconds.
   Full mechanics in `docs/phone-bridge.md`.

   Four constraints for this slice, all already decided:
   - A **separate token** from `API_SERVER_KEY`, independently revocable.
   - A **capability allowlist on the phone**, plus an **on-device audit log** and a
     **kill switch** — the kill switch being an in-app control is what removes the harness's
     adb dependency.
   - The bridge service must **not** be `exported` without a guard, and must take its
     endpoint from stored configuration rather than from the intent that starts it. The gate
     harness does the opposite; see the security note in `docs/phone-bridge.md`.
   - No battery-optimisation exemption is needed — a foreground service with an ongoing
     notification keeps network access through deep Doze. That notification is the mechanism
     *and* a cost; decide on it deliberately rather than inheriting it from the harness.
