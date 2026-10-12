## Phase 6 local implementation — October 12

Independent corrected-0.5.5 branch `feature/phase6-opencode-activity` now has a
read-only OpenCode activity journal, Android timeline/recovery and workspace diffs.
APK preview version `0.6.0-preview`/18; plugin `0.4.0-phase6`. Not installed or pushed.
[Implementation, actual checks, limitations and acceptance](PHASE-6-OPENCODE.md).
The separate combined planning branch is at `/home/praxisog/Bellpull-phase8-9`.
No personal server access or additional ADB use after the launch repair.
Signed local preview APK/plugin archive are in `build/releases/phase6-0.6.0-preview/`.
Release build, signature and ZIP alignment checks passed; APK SHA-256
`3279e8bd617050bccd6b5f59934f167946785c394e132b9d6dd505a27653a62e`.
These are local artifacts, not an installed or published update.
Full Android checks passed. All 18 focused coding Python tests passed on stock.
The local legacy compatibility fixture had 113/116 passing, with three approval
failures also reproduced on the unchanged 0.5.5 plugin; exact Docker CI is unrun.

# Bellpull / Hermes app handoff

## 2026-10-12: immediate 0.5.5 launch repair

The first 0.5.5 candidate crashed when chat composition initialized VoiceOutputLab:
the new folder-ID validator rejected the shipped `qwen-0.6b` ID. Fixed at
`c913243` on `feature/local-voice-models`, preserving existing model paths and
still rejecting traversal/unsafe prefixes. Eight focused JVM tests passed, and a
new fixture-only phone startup/preflight/Activity test passed. Corrected signed
0.5.5/code 17 installed in place; signer, UID and both data-directory inodes
preserved. No personal server access; personal app left stopped. Fixture packages
removed after the check. Pocket synthesis and the other pending acceptance were
not tested by this regression.

Corrected APK SHA-256:
`1e7121c0a32658ded36fcb02122b7b581f8c5dd0a8993646b0f25f28f20415d5`.
Evidence: ignored `build/launch-crash-*` logs and
`build/releases/0.5.5/launch-crash/`. Both independent development branches now
include the repair: `feature/phase6-opencode-activity` and
`feature/phase8-9-reminders-calendar` (worktree `/home/praxisog/Bellpull-phase8-9`).
The user authorized building both while away; no further phone access or push.

## 2026-10-12: 0.5.5 installed on request

Built and signed Phase 5 commit `c5dceec` from `feature/local-voice-models` and
installed **0.5.5/code 17** over personal 0.5.3 with `adb install -r`. The Phase 6
prototype is excluded. Signature and 16-KiB ZIP alignment passed. Android UID
**10260**, credential-encrypted data inode **117766** and device-encrypted data
inode **116002** are unchanged. The personal app was left stopped; no personal
server access or app-data clearing. Removed only the fixture app/test packages,
including their test weights. No new functional tests, screenshots or benchmarks
were run; Pocket and recovery acceptance remains pending.

APK: `build/releases/0.5.5/Bellpull.apk`; SHA-256
`42c5d4ff4c943bdef7f36bcd064491b374602c9d5fa77143d54e4762a7c847cb`.
Ignored package/install evidence is under `build/releases/0.5.5/personal-upgrade/`.
Neither branch pushed. Returned to `feature/phase6-opencode-activity` afterward.

## 2026-10-11 overnight: Phase 5 offline and Phase 6 prelim

- Phase 5: `feature/local-voice-models`, commit `c5dceec`, 0.5.5/code 17.
  Pocket CPU download/selection/tuning, exclusive model ownership and explicit
  audio/startup recovery implemented. Compiles ARM64/x86_64. **Further tests,
  screenshots/lint and device acceptance deferred at user request.**
- Phase 6: `feature/phase6-opencode-activity`, based on that Phase 5 commit.
  Source audit and unconnected bounded CLI timeline prototype for OpenCode
  started by Hermes. No live adapter, network route, runner or plugin update.
  Pure model and APK compilation passed; regressions remain pending.
- **Personal phone still 0.5.3/code 15**, stopped. ADB disconnected before
  the signed 0.5.4 update. Tested signed 0.5.4 APK is retained unchanged locally;
  it does not contain the newer Pocket implementation. Fixture app/test package
  and Qwen weights remain pending cleanup when ADB returns.
- Neither branch pushed. No personal server access, additional phone access,
  merge or release publication. Push remains gated on the user's green light.

Details: [voice output/evidence and remaining Phase 5 acceptance](VOICE-OUTPUT.md),
[Phase 5 scope](PHASE-5-VOICE.md), [Phase 6 contracts and acceptance](PHASE-6-OPENCODE.md).
Actual pre-tool playback ordering still needs a verified host coordination
mechanism; observed tool announcements cannot provide that guarantee.


## 0.5.0 conversational voice preview — 2026-10-11

Branch **`feature/phase5-voice`**, based on Phase 4 commit `a8d49cc`. Android
**0.5.0/code 12** adds private transcription, installed offline TTS, a
service-owned conversational loop, reviewed dictation and push-to-talk. It reuses
the existing conversation, durable admission/recovery, tools and approvals.
There is no plugin change; projects still require Bellpull plugin 0.3.0.
See [setup, behavior, limits and manual acceptance](PHASE-5-VOICE.md).

The drawer puts projects and API/CLI/TUI source choices in one filter menu
immediately left of Search. Compact titles occupy two lines in the existing row
height, source badges are removed and chat actions sit nearer the right edge.
Selection, unread/running state, projects, drafts and ongoing runs are retained.

Actual local Android checks: **444 JVM tests passed, one opt-in live smoke
skipped; 150 screenshot baselines verified; debug/release builds and lint passed**.
Lint reports 50 warnings and 4 hints, no errors. New tests exercise initial
playback ordering, streaming sentences, corrections, interruption, queued turns,
transcription failure/retry, admission persistence/restart, one-run submission,
push-to-talk release, profile isolation and drawer interaction. The API 29 service
type guard is source/compile checked; no API 29 device was tested.

Seven isolated device tests passed on the unfolded Pixel 10 Pro Fold / GrapheneOS
17: installed offline TTS callbacks, actual microphone capture/release/restart,
foreground-service background survival and notification pause/stop, exact WAV
transcription wire shape, encrypted settings/progress isolation, keyboard Save,
installed About version/plugin statuses and drawer touches. Native probes report
offline `en_US`, available/enabled AEC and no on-device speech recognizer.
The loopback transcription fixture returns fixed text; it is **not** recognition
or provider acceptance. No personal server or real inference provider was used.

The cover-display attempt reached keyguard and all three UI tests timed out;
those are failed/unexecuted UI assertions, **not** a cover-device pass. Cover and
unfolded screenshot checks passed. The display override was reset; fixture apps,
ADB reverse ports and local fixture processes were removed afterward.

The signed APK was installed in place over 0.4.0 with the existing signing lineage,
retaining UID **10260**, without uninstalling/clearing the personal app. Package
metadata confirms 0.5.0/build 12 and `stopped=true`; the personal app was never
launched or connected during these checks. Signed APK SHA-256 and all local
evidence live under ignored `build/releases/0.5.0/`, including a before/after
screenshot gallery and `verification.json`. APK SHA-256:
`f83306349dcee524c149d2cf5e99be933e847473937d8509bf4372107c4e58bd`.

This is a preview, not completion of Phase 5. The user must configure a private
STT URL/key/model. Real recognition, acoustic echo/barge-in, perceived latency,
physical lock/fold, Bluetooth/car/navigation/calls and real-network behavior need
manual acceptance. Specific per-tool speech finishing before execution lacks a
verified host handshake; the implemented guarantee is actual general preamble
playback before run submission. No fake approval or core patch was added.
Push the development branch only; no merge or public release. Remote CI evidence
must be inspected separately; preceding Phase 4 plugin results are baseline
evidence, not a newly executed plugin test run.

## 0.4.0 Fold acceptance and branch push — 2026-10-11

The user returned ADB and authorized install/test/tweak/push. Android **0.4.0/code
11** is installed over 0.3.7 through the existing signing lineage, with Android
UID **10260** retained and no clear/uninstall of the personal app. The personal
app stayed stopped; personal profiles were not opened and the personal Hermes
server was not accessed. The server update to **Bellpull plugin 0.3.0** remains a
manual user action. No merge or public release is part of this work.

Eight guarded test executions passed on the Pixel 10 Pro Fold / GrapheneOS 17:
files, projects/cache/branch/compaction, two UI checks at each display width,
conversation sources, and initial-SSE/compaction/restart recovery. The latter
asserts one admitted inference. A disposable current stock non-Docker Hermes
checkout and deterministic agent supplied the server; no personal credentials or
provider calls were used. The actual About screen displayed 0.4.0/build 11 and
plugin 0.3.0 from fixture discovery, and synthetic old/current plugin statuses
passed on both displays.

Device review fixed keyboard-obscured editor Save/Cancel and cover startup hiding
the remembered project overview. New tests cover constrained editor height and
cover shell startup. Public Android touch/accessibility APIs replaced the new
UI test's Espresso harness after its Android 17 InputManager reflection failed
before execution. The real keyboard/Save checks then passed at both widths.
Final local Android checks: **396 JVM tests passed, one opt-in smoke skipped;
140 screenshots verified; assemble and lint passed** (45 warnings, 4 hints).
Plugin implementation is unchanged from the preceding 98/13/3/17 passing runs.

Temporary state overrides selected the actual cover/unfolded displays and were
reset to the original base state. Density settings were unchanged. Fixture apps,
forwarding and local server processes were removed. Evidence and signed/plugin
artifacts are under ignored `build/releases/0.4.0/`; APK SHA-256:
`b347081c0ca87874b23826c0cb853059bc5e99f947659cbb6cccbe049e90cf53`.

Push target is **`feature/phase4-projects`**, leaving `master` and the 0.3.7 repair
branch unchanged. The normal Android/plugin workflow validates the pushed branch;
inspect its result for remote CI evidence. See [Phase 4](PHASE-4-PROJECTS.md) for
the full verification and stock-install checklist. Actual-provider behavior,
personal encrypted-profile decoding and physical hinge movement remain user
acceptance; no compatibility expansion or new dependency was introduced.

## Phase 4 projects — 2026-10-11, ready for device acceptance

Local branch **`feature/phase4-projects`**, based on pushed 0.3.7 commit
`743dbe4fe758d36d08c02f8c633bd788aa1535e6`. Android **0.4.0/code 11**;
Bellpull plugin **0.3.0**. The user narrowed Phase 4 to conversation projects,
then added Settings → About. See [scope, verification and manual acceptance](PHASE-4-PROJECTS.md).

Implemented plugin-authoritative folders with encrypted per-device snapshots,
single membership, create/rename/description/color/icon, moves/Unfiled, project
new chats, pinning, archive/restore and safe folder deletion. Projects open to
their overview; pinned chats precede recent activity. Search explicitly switches
between the selected scope and All conversations. Archived chats stay globally
visible. Switching scopes preserves drafts/runs. Compression keeps membership,
pins and read receipts; ordinary branches remain independently movable.
Revision conflicts refresh instead of merging stale edits. Uncertain chat
creation retains its identity across restart for explicit recovery without
duplicate inference. About reads Android's actual installed version and cached
plugin versions per saved profile, comparing with app minimum 0.3.0; it performs
no latest-GitHub-release polling or background connections to other profiles.

Actual local checks: **394 JVM tests passed, one opt-in live smoke skipped;
139 screenshot baselines verified; Android assemble/lint passed** (45 warnings,
4 hints, no errors). **98 plugin tests passed** on the extended pinned fixture;
**13 project + 3 portable tests passed** on unmodified pinned stock Hermes;
**17 portable/project/native-profile tests passed** on current non-Docker Hermes
`d4d2ab7`. The primary HTTP workflow survives an adapter restart and real
compaction/continuation. An additional local stock HTTP acceptance and installer
preview/apply/backup check passed. Compatibility/license copies match.

The guarded `ProjectsDeviceTest` and fixture APK/test APK compile; device tests
have **not run**. The personal APK is signed/verified with the existing lineage.
The user has the phone overnight: **no ADB commands or installations were made**.
No personal-server access, Phase 4 push, merge or release. No Phase 4 remote CI
evidence; 0.3.7 CI is previous-baseline evidence only. Local candidate APK, plugin
bundle, checksums and logs are under ignored `build/releases/0.4.0/`.

Next: return ADB access, run a fresh disposable current-stock helper with
`--projects`, install only `org.praxisog.hermes.fixture` and its test APK, select
`ProjectsDeviceTest` explicitly, then inspect both Fold widths, keyboard and About.
Keep the personal app stopped during fixture tests. Upgrade the personal app only
with explicit resumed authorization, preserving its UID/data/signing lineage;
the user installs the plugin on their own server. Physical upgrade, actual provider
behavior and personal-profile access remain unverified.

Compatibility: metadata is additive in `bellpull-projects/<principal>/` and must
be backed up with the stable principal/native DB. Old apps/plugins ignore it.
Current native forks with explicit branch markers inherit; older external forks
without markers need a move. Selected-message branching retains its existing
source compatibility gate; no broad native compatibility claim was added. Native
Hermes clients do not automatically share Bellpull folder metadata. No project
files, model instructions, task boards or offline write queue were added.

## 0.3.7 audit repair — 2026-10-11

All six audited findings at `8597dbd` were verified and repaired locally on
`repair/bellpull-plugin-migration`. See [the audit repair report](AUDIT-0.3.6-REPAIR.md)
for behavior changes, regression results, compatibility limits, CI evidence and
the stock non-Docker acceptance checklist. The existing prepared 0.3.6 release APK
predates these repairs. The user subsequently authorized ADB installation and
pushing 0.3.7. Android is **0.3.7/code 10**, paired with **plugin 0.2.2**. The signed
in-place phone update preserves the Android UID; three isolated stock-fixture
device tests cover files, source visibility and stream/compaction/restart recovery.
The personal app remains stopped during tests. No personal-server access, merge
or public release is authorized. Actual-provider and personal-profile acceptance
remain for the user.

## Stock SSE framing caught on the Fold — 2026-10-10

Bellpull **0.3.6 / code 9**, paired with the unchanged **bellpull 0.2.1**
plugin, supersedes the prepared 0.3.5 APK. ADB acceptance on the separate
`org.praxisog.hermes.fixture` app exposed an additional client bug: current stock
`/v1/runs/{id}/events` sends `id:` and `data:` lines, with the event name in JSON,
and no separate SSE `event:` line. The previous reader silently dropped every
such frame. The parser now accepts the JSON name when an explicit SSE name is
absent; legacy explicit names take precedence. JSON sequence validation and
native `Last-Event-ID` replay remain unchanged. Multi-line data joins with newlines.

Physical Pixel 10 Pro Fold / GrapheneOS Android 17 acceptance used a temporary,
non-Docker stock checkout at `d4d2ab7b5d104166e7e22a02cafd12f1fe456a31`,
one Bellpull plugin, and a deterministic test agent. No real inference, personal
credentials or personal-server access. `tools/stock-device-fixture.py --streaming`
adds authenticated loopback-only `/fixture/state` and `/fixture/advance` test
controls so the native run can pause between callbacks. They are not plugin or
production routes. Native gateway auth, durable run admission, SSE and history
remain in use. The gateway core files are unchanged.

On the actual unfolded display:

- Real encrypted preferences migrated the old API-only default to All conversations;
  an explicit CLI selection persisted. API, CLI, TUI and Telegram titles were visible.
- One native run grew the already-expanded thinking block from **130 to 560
  characters**, without a conversation selection or duplicate completed preview.
  The saved native reasoning matched the displayed text byte for byte.
- Interim prose, a running/completed Read file row and the live answer appeared
  in the same open chat. The saved reply then appeared and Working/Stop cleared
  without reselecting the conversation.
- Six screenshots captured different dot intensities. The only changing pixels
  inside the paused working row occupied the 17×17-pixel indicator region;
  response width, separators and corners stayed fixed.
- Four plugin-served PNGs downloaded on the phone with matching sizes and SHA-256.

The cover-state device check reached the system lock screen. The emulated state
override was reset; density/size settings were not changed. The user unlocked the
phone for the final saved-reply check. Cover layout remains verified by JVM
screenshots, not by this physical run. Fixture app/test packages, their temporary
USB forwarding and the local fixture process were removed afterward. Personal
Bellpull was only stopped; its installation, profiles and data were untouched.

Validation: **352 JVM checks passed, one opt-in live smoke skipped; two guarded
device tests passed; all 125 screenshot baselines verified; Android assemble/lint
and release signing verification passed**. Existing 72 plugin regressions and
portable checks from 0.3.5 still apply; plugin implementation did not change.
New parser tests use actual stock framing, including a pipe held open before
completion and replay through the durable-run client.

Device evidence: `build/device-review-036/{start,reasoning-first,reasoning-second,
tool-running,answer-streaming,completed-unfolded}.png`, paired accessibility XML,
`working-animation.json` and `acceptance.json`. Manual artifacts:
`build/releases/0.3.6/`. The actual provider and personal signed upgrade still
await the user's manual installation/report. No publication, push or merge.

## Conversation visibility, live thinking and working row — 2026-10-10

Bellpull **0.3.5 / code 8** and the consolidated **bellpull 0.2.1** plugin are
prepared on `repair/bellpull-plugin-migration` for the user's manual installation.
The personal server remains off limits. The user took the phone back during this
slice; **no ADB commands or device installs were performed for 0.3.5**.

The drawer now defaults to **All conversations**, omitting the native `source`
filter. A one-time encrypted preference migration retires the previous API-only
default for existing server profiles; new explicit filters persist per profile.
Native profile boundaries and nested-session visibility remain unchanged. Local
stock-adapter and client/store tests include API, CLI, TUI and Telegram sessions.
This reads Hermes' existing history; it does not import or merge provider history.

The stock durable-run route did not connect the agent's live reasoning callback.
Its `reasoning.available` event is a completed, 500-character preview. The plugin
now uses two small, feature-detected adapter-instance callback wrappers to forward
full reasoning through the native run stream as `reasoning.delta`. Older stock
factories use the native agent's existing callback attribute. Hermes still owns
auth, execution, event sequencing/replay, cancellation and persistence; no core
files or endpoints are replaced. Existing explicit callbacks remain intact,
duplicate completed previews are suppressed, and unload restores the factories
and fences late reasoning. Failed route registration restores them too.
`bellpull_reasoning_streaming` reports availability. Native interim/commentary
events render once as separate prose segments, respecting `already_streamed`.

The working status is a full-width row inside the response boundary, with the
same separators and inherited outer corners as thinking/tools/files. Its small
dot pulses without changing layout and stays still with reduced motion or system
animations disabled. Tests verify growing thinking in the already-open chat,
row geometry and changing indicator pixels. Static screenshot capture uses
reduced motion; the animated case has its own controlled-clock test.

Validation: **350 JVM checks passed, one opt-in live smoke skipped; 125 screenshot
baselines verified; Android assemble/lint passed; 72 plugin tests passed**.
Three portable tests also passed on each unmodified stock revision. Real stock HTTP
tests on unmodified v2026.9.7 and current non-Docker checkout
`d4d2ab7b5d104166e7e22a02cafd12f1fe456a31` receive more than 500 reasoning
characters while the run is still running, then the next reasoning step without
duplicates; request replay stays idempotent and core hashes stay unchanged.
Inference is replaced by a deterministic fixture, so actual provider streaming
and the signed upgrade still await the user's manual report.

Review images: `build/ui-review-035/streamingCover-diff.png`,
`streamingUnfolded-diff.png`, and `conversations-diff.png` (before/after/brightened
pixel difference). Manual artifacts live in `build/releases/`; signing uses the
existing release key and preview lineage. No publish, push or merge was performed.

## Stock-install file delivery and streaming repair — 2026-10-10

The user's personal Hermes runs on another box without Docker. **Do not access
that server.** The user will install this build/plugin manually and report back.
The locally observed Docker gateway is a playground, not the personal server.
All subsequent phone tests used `org.praxisog.hermes.fixture` with ADB loopback
to a temporary, stock local Hermes installation; inference was disabled.

Bellpull **0.3.4 / code 7** now resumes sequenced native run streams using
`Last-Event-ID`, suppresses duplicate frames, and rejects replay gaps. A restored
run can recover complete live replay. Successful terminal status waits for its
saved final assistant row instead of treating stale HTTP-200 history as synced.
Dropped legacy streams poll canonical history without resending, so the reply
appears without tapping the conversation again.

The consolidated **bellpull 0.2.0** plugin now provides persistent files and
lifecycle routes through `register_platform_handler` on stock Hermes. Exact
core hashes and host extensions no longer gate file sharing. Its native
`bellpull_share_files` tool prepares a session-private outbox and explicitly
publishes generated outputs. GPU telemetry stays in this same package; disable
the separate `bellpull_gpu` plugin when enabling it. Native run admission/auth
and model routing are unchanged. Input attachments/model overrides remain
separate optional legacy capabilities, with a visible explanation and draft
preservation on a stock server.

`tools/install-plugin.py` previews by default, accepts the actual data home,
backs up outside plugin discovery, and copies only plugin files. It does not edit
Hermes core or configuration, use Docker, or hardcode a server URL. The stock
install path is at the top of `plugins/bellpull/README.md`. `server-host/` is now
explicitly an optional legacy compatibility fixture.

Validation: **343 JVM checks passed, one opt-in live smoke skipped; 123 screenshot
baselines verified; Android assemble/lint passed; 65 plugin tests passed**.
Two portable tests additionally passed on both unmodified v2026.9.7 and a fresh
non-Docker current checkout (`d4d2ab7b5d104166e7e22a02cafd12f1fe456a31`, Python
3.14). Native discovery, four PNG outputs, authenticated HTTP 200 downloads,
rejection of wrong keys/unserved profiles, restart persistence, 11 MiB chunked
uploads, unsafe-output rejection and unchanged core hashes were checked. The
current checkout's tracked files remain clean; only the plugin was added.

The physical Fold fixture test passed: four 68-byte PNG downloads each matched
SHA-256 `431ced6916a2a21a156e38701afe55bbd7f88969fbbfc56d7fe099d47f265460`.
ADB extraction independently confirmed the bytes. A pending file/text draft was
preserved and nothing sent when chat attachments were unsupported. Manual ADB
taps verified the file list and PNG preview on the unfolded display. Cover/wide
unsupported-state screenshots were inspected. Espresso's Compose test rule is
incompatible with this Android 17 InputManager surface, so the device test uses
the AndroidX runner without Espresso; UI taps use ADB.

Before the user requested manual installation, the original preview-to-release
0.3.4 signing transition installed in place successfully and both encrypted
server profiles remained accessible. A private pre-upgrade backup is outside
Git. The final repaired APK is for **manual** installation; it has not been
installed into the personal app or deployed to the personal server. Actual
server acceptance, real provider streaming and natural physical cover use await
the user's report. No publish, push or merge was performed.

## Repair and plugin migration — 2026-10-10

Branch **`repair/bellpull-plugin-migration`**, based on heading-alignment checkpoint
`032486e` / upstream `e41f151`. The user authorized execution of
`Bellpull-Repair-and-Plugin-Migration-Changelist.txt`, approved CommonMark Java
0.30.1 plus tables, and selected **MIT** for Bellpull's own code. The original txt
is retained as the investigation/proposal snapshot; this entry records execution.
Phase 4 expansion and importing/merging provider history remain deferred.

Android **0.3.4 (code 7)** now has native Markdown answers (headings, emphasis,
lists, links, tables, code-block copying), title-only conversation navigation and
dense defaults, optional larger labeled navigation controls, consistent Info /
Session info wording, deliberate API/CLI/TUI/all-source selection, and complete
saved-transcript paging. Existing stored density preferences remain respected.
Global search says it searches loaded titles/previews; chat search and View prompts
receive the completely loaded saved transcript. Raw whole-message copy and native
selection remain available. Stream scroll/follow regressions pass.

Artifact UI adds quota/collection warnings, confirmed deletion, unavailable/deleted
history references and branch attachment aliases. Local draft files are protected
from housekeeping. Terminal/history-synced run records expire after 30 days;
ambiguous, outstanding and unsynced runs are retained.

`plugins/bellpull/` is one installable package for branch/edit, files, models,
notifications and optional telemetry. `server-host/` supplies the small proposed
local native contract against exactly pinned Hermes v2026.9.7 / internal 0.21.1.
It preserves the native run engine; no adapter method replacement or native static
flag mutation. Exact source checks disable unsupported combinations. Android merges
versioned plugin discovery with native structured run capabilities. Stable artifact
identity survives bearer rotation; explicit offline migration preserves legacy
IDs/content/metadata and old namespaces. New per-run output folders avoid old-file
starvation. Plugin unload/shutdown closes notification and paging resources.

Read-only production investigation found `/opt/data` backed by `~/hermes-data`,
with **47 API, 7 CLI, 1 TUI conversations and 545 messages** at inspection. The
former API-only source filter hid CLI/TUI chats. This is evidence about the inspected
default home, not proof of device acceptance or of every possible profile's history.
No live config/data/container changes or new inference were made for this repair.

Validation: **336 JVM tests passed, one opt-in live smoke skipped; 121 screenshot
baselines verified; debug APK and lint passed (zero errors, 39 warnings, four hints).
63 isolated plugin/native tests pass** using real aiohttp, SQLite, plugin discovery,
native adapter startup/shutdown, and fake inference, with no production mounts or
network access. They cover >50-chat/>500-message activity-stable paging, compression,
auth/profile isolation, exact branches and transaction rollback, migration/rotation,
quotas, interrupted uploads, repeated outputs, admission races and full slots,
restart recovery, opt-in/default/parallel/timeout approvals, and unload cleanup.
Rendered cover/unfolded, dark/light and 1.5x text layouts were inspected; the dense
conversation before/after diff is under ignored
`android/app/build/repair-review/conversation-diff.png`.

CI has Android test/screenshot/APK/lint and isolated plugin jobs with pinned actions,
read-only permissions, no signing secrets, inference or deployment. Equivalent job
commands passed locally; the GitHub-hosted workflow has not run because this branch
has not been pushed. Release instructions are in `docs/release.md`.

A dedicated RSA-4096 release key and the preview-to-release signing lineage were
prepared outside Git in `~/.config/bellpull/signing` (0700, private files 0600).
The original public preview certificate was checked against the downloaded 0.3.3
APK. The release APK verifies for the supported Android range; this does not prove
an actual on-device upgrade. No second-device/offline key backup has been made.
Private signing material must be backed up before publishing.

**Remaining acceptance work:**

- ADB currently reports **no attached device**. Connect and unlock the Fold to
  verify the in-place signing upgrade, existing profiles/drafts/settings, Markdown,
  source selection, compact/labeled controls and natural fold/unfold behavior.
- The thin plugin image is built locally as `bellpull:plugin-repair`; production
  still uses the legacy overlay image. Prepare a maintenance-window data backup,
  review migration dry-run, then obtain deployment approval before stopping writers,
  migrating artifacts or changing the live image/config. Perform harmless authorized
  production model/file/approval/notification parity checks after migration.
- Keep `server-patches/` and the old final image as rollback until parity is accepted.
  Neither remote branch publication nor an APK release has been performed.

Current authorities: `plugins/bellpull/README.md` for install/migrate/rollback,
`docs/hermes-api.md` for wire contracts, `docs/release.md` for signing/upgrades.
The older chronological entries below describe earlier releases and decisions;
this entry supersedes their default density, labeling and backend setup statements.

## Conversations heading matches chat titles — 2026-10-10

Branch `ui/align-conversations-heading` starts from tested checkpoint `e41f151`.
The drawer's Conversations heading now aligns with the chat title text inside
the cards, using the same `titleMedium` typography (15 sp, semibold). Standard
and immersive headers use the cards' 8 dp outer inset plus 14 dp inner padding;
the immersive header retains its camera avoidance and existing search position.
This reflects the user's refinement from aligning with the cards' outer edge.

Validation: 316 JVM tests passed, one opt-in live smoke skipped; all 113 screenshot
baselines verified; APK/lint passed with zero errors, 36 warnings and four hints.
All 29 updated snapshots differ only around the heading. Cover/dark and unfolded
before/after images were inspected. The updated debug APK was installed in place
on the naturally unfolded Pixel 10 Pro Fold: the heading and six visible chat
titles all start at x=61 px. Private captures/geometry remain in ignored
`android/app/build/device-tests/heading-align/`; fictional before/after comparisons
are in `android/app/build/heading-align/`. Physical folding was not exercised.
The app remains version 0.3.3 (code 6); this is a local branch checkpoint.

## Tool and artifact rows share the response outline — 2026-10-10

Android **0.3.3 (code 6)** refines the combined response bubble to match the user's
clarification. Terminal, search, file, thinking and artifact rows now span the full
response width. Internal rows are square with shared horizontal separators, no
nested outlines or side gutters. The parent response clips the first/last rows to
its outer corners, so a final artifact or tool forms the bottom of the bubble.
Response corners are 18 dp except the 4 dp lower-left corner; user bubbles use
18 dp all around. Prose retains its inset and tool status colors/details remain.
Standalone artifact lists keep their own row containers. No server changes.

Validation: **316 JVM tests passed, one opt-in live smoke skipped; 113 screenshot
baselines verified; APK/lint passed with zero errors, 36 advisory warnings and four
hints.** Existing response screenshots now assert exact row/bubble edge alignment;
two new cover/dark-wide screenshots exercise a middle artifact and a final tool.
Saved and streaming response diffs were inspected. On the naturally unfolded phone,
the update preserves Dense mode and the configured profile; full-width rows, tool
details and the final PDF artifact's preview were checked. No phone data was cleared,
fold state forced, new inference run, or desktop emulator started for this slice.
The user saw and approved the appearance while these checks were being completed.
Physical fold transitions remain untested; cover geometry has rendered coverage.

APK SHA-256: `11f79d6d1ab33888069c90837db0ff5bd5891740d444f2e657a9056f98e4fec1`.
Release: `android-v0.3.3-preview.1`, asset `Bellpull.apk`; branch
`phone/integrated-response-rows`, also published to `master`. README download/gallery
now reflect 0.3.3. Private phone captures stay under ignored
`android/app/build/device-tests/integrated-rows/`. This release is the current tested
checkpoint; 0.3.2 remains available. Stop for further instructions.

The user is considering trying a cheaper coding model for the next phase. Suggested
handoff: read this document, CLAUDE.md, docs/hermes-api.md and the current
Bellpull-Implementation-Prompt.txt; preserve existing work and branch from the latest
tested origin/master; start with Phase 4A (saved answers and lightweight projects),
briefly explain the approach, then implement/test with cover/unfolded checks and
small commits. Keep that future experiment off master and do not publish its APK
until the user reviews it. Phase 4 has not begun; the obsolete Tier 1/2/3 plan is
not the next-phase authority. Git checkpoints do not back up live server data/config.

## Dense chat, complete response bubbles and Back navigation — 2026-10-10

Android **0.3.2 (code 5)** completes the requested Phase 3 UI refinements:

- Each assistant response has one transparent outlined bubble containing its prose,
  thinking, terminal/search/file rows and generated artifacts. A new user prompt or
  system message separates responses; uploaded files retain their own placement.
  Streaming replies use the same presentation. Tool rows keep their details controls.
- Settings adds **Dense chat window**, off by default. It hides visible speaker names
  and permanent action bars while preserving accessible speaker descriptions. User
  messages stay filled; assistant bubbles stay unfilled. The preference persists.
- Long-press a user message for Copy, Select text, Follow up and Fork & edit. Assistant
  messages offer Copy, Select text, Read/Stop, Branch, Follow up and Share. Select text
  opens a read-only native selection dialog; copying a selection does not summon the
  keyboard or change the conversation. Streaming menus retain the text pressed.
- Copy/read/select/share use the complete response's prose without tool output or
  reasoning. Branch targets its final displayed canonical assistant message. Existing
  capability checks still gate branching/editing; Follow up creates an unsent quote.
- The chat overflow menu adds **View artifacts**, listing the current conversation's
  files with Download/View, including an explicit empty state. Viewing opens the
  existing preview dialog; it can still dock in the right drawer.
- Android Back returns from Settings to the last chat with its draft/scroll intact.
  Back closes an open conversations panel on cover and wide layouts. Once it is
  closed, Back can leave the app instead of reopening the panel in a loop.
- Search can reveal a specific segment inside a response taller than the viewport;
  tail following, read receipts and artifact chronology remain covered.

Validation: **314 JVM tests passed, one opt-in live smoke skipped; 111 screenshot
baselines verified; APK and lint passed with zero errors, 36 advisory warnings and
four hints.** Nine new fictional-data snapshots cover dense layouts, long-press menus,
selection, artifact browsing and mixed text/tool/file responses. Updated cover and
unfolded diffs were reviewed. The README gallery now uses current 0.3.2 baselines.

Two native Android acceptance tests passed at both 393×850 and 1280×800 on the
isolated emulator: real long presses, partial copying, read callbacks, unsent follow-up,
exact fork/edit targeting, tool details inside a combined bubble, whole-response
selection and both Back behaviors. The emulator is stopped. A cover-width Robolectric
QuoteDialog layout loop initially exhausted the desktop test worker's heap; that
particular JVM flow now uses the wide configuration, while native Android covers both
widths. The full suite passes at the original 512 MiB test heap; no memory override
or app heap setting was added.

Real-phone acceptance on the naturally unfolded Pixel 10 Pro Fold verified the
in-place upgrade, persisted Dense mode, grouped live-history text/tool/artifact rows,
user/bot action menus, selection dialog, View artifacts → PDF preview, Settings → chat
Back and conversations-panel Back. Dense is enabled on the user's phone; fresh installs
retain the standard layout. No credentials were removed, fold state forced, or new
inference requested. Physical cover/fold transitions remain untested; cover layout
and gestures are covered by rendered and native emulator tests.

Installed/released APK SHA-256:
`f97556892ac7571521acc801162d940adb548279323a6a6afea8675d13aac202`.
Release: `android-v0.3.2-preview.1`, asset `Bellpull.apk`; source branch
`phone/dense-chat-window`, also published to `master`. Phone captures stay in ignored
`android/app/build/device-tests/dense-chat/`. This tagged release is the rollback
checkpoint before the next phase. Future work should start on its own branch; Git
reverts tracked source, not server configuration, databases or device account data.
Phase 4 and further desktop packaging have not begun. Stop for further instruction.

## Inline artifacts and dialog previews — 2026-10-10

Android **0.3.1 (code 4)** refines Phase 3's saved-file presentation:

- Uploaded and generated files appear as compact outlined rows inside the scrolling
  transcript. Image files have a minimalist picture icon; other files use a folder.
  Names retain their extension with middle ellipsis. Each row has Download and View.
  Only unsent attachment drafts/progress stay above the composer.
- Explicit artifact message IDs determine placement when present. Existing server
  records without that link use creation timestamps; records without usable message
  times remain at the end. No endpoint or server change was required.
- View opens the same sized rounded dialog as tool details on both cover and wide
  layouts. Native PDF/image rendering supports scrolling, Fit, pinch/button zoom,
  Reset zoom, PDF page navigation, Open, Save and Share. Open in right drawer is an
  explicit action; the existing adaptive drawer and camera-aware header remain.
- Direct Download uses Android's create-document picker with the original name and
  MIME type, authenticated content retrieval and the existing integrity checks.
  Missing artifacts keep an inspectable error state and disable Download.
- Transcript navigation, tail following and read receipts account for inline files.
  Preview dialogs suspend conversation read marking while they cover the chat.

Validation: **294 JVM tests passed, one opt-in smoke skipped; 102 screenshot baselines
verified; APK and lint passed (zero errors, 36 advisory warnings, four hints).** New
checks cover chronology, stable file identity, inline scrolling, read receipts after
long artifact lists, action targeting, missing files, default dialog behavior, preview
scrolling/zoom controls and explicit docking on cover and unfolded layouts. Five new
fictional-data screenshots were inspected; existing baselines remain unchanged.

The dedicated emulator passed native MediaStore upload/retry, two-page PDF navigation,
PNG/generated previews, zoom/scroll/reset and dialog-to-drawer transitions. Manual
Download saved a 10,995-byte PDF through the Android picker with SHA-256 identical to
the server metadata. Unsupported TXT files retain Open/Save/Share. Real-phone testing
on the naturally unfolded Pixel 10 Pro Fold verified existing live PDF and PNG
artifacts, dialog defaults, zoom/reset and the optional drawer after an in-place APK
upgrade. No phone data was cleared, fold state forced, or new inference requested.
Physical cover transitions remain untested; cover layouts have screenshot coverage.

Installed/released APK SHA-256:
`b11a10d90f01a169e4d6fe0153eb985d55487c7e9eb98b545b6863e7a8b80b82`.
Release: `android-v0.3.1-preview.1`, asset `Bellpull.apk`. Phone captures and before/after
evidence stay in ignored `android/app/build/device-tests/inline-artifacts/`.
Phase 4 and desktop packaging have not begun.

## Phase 3 models, attachments, sharing and GPU plugin — 2026-10-10

Android version **0.3.0 (code 3)** implements current Bellpull Phase 3A–D. The user
authorized Phase 3, testing and applying the working build. This is separate from
the obsolete three-tier roadmap. Desktop emulator packaging is parked until the
Android phases are finished; the disposable emulator was used only for Android tests.

- Context has a searchable configured-model picker with actual supported thinking
  controls. Choices persist per conversation. Settings provides profile-local defaults
  for new chats; other chats and Hermes global/job defaults are unchanged. The current
  Fireworks DeepSeek V4.1 Flash route exposes low/high/max/Off. Off and Low both
  completed live requests. Native Hermes request-builder tests verify the exact fields.
- The accessible local `qwen38-flash-next` server accepts thinking On/Off using
  `chat_template_kwargs.enable_thinking`: the same bounded prompt used zero versus
  23 reasoning tokens. No graded local effort was verified. The toggle is available
  when that exact custom route is configured in Hermes; this patch does not create
  the route or change global inference routing.
- Native file/image selection and camera capture copy granted content streams into
  profile-private drafts. Uploads show progress/cancel and preserve drafts/files on
  failure. Stable IDs and exact model/attachment payloads survive lost acknowledgements.
  Source bytes remain intact, with 25 MiB per file/turn, eight files per turn and a
  512 MiB server scope quota. Original images go to a confirmed vision route; documents
  use existing bounded file extraction. No extra cloud vision provider is selected.
- Native PDF page/zoom and image pan/zoom previews preserve the chat and adapt between
  a right pane and cover overlay. The preview header uses the existing camera-cutout
  avoidance. Tools lists uploaded/generated files with sort/filter/source navigation.
  Unsupported formats can open/save/share. Share-in creates a chosen/new chat draft;
  share-out uses Android's chooser and selected-file read grants. No HTML bridge.
- `plugins/bellpull_gpu` is a native Hermes API plugin that runs local amd-smi or
  nvidia-smi on demand. No Moe SSH access is required. Cached, read-only telemetry
  identifies actual cards and sensors, fixed scales, unavailable/stale readings.
  The app polls on the foreground Tools page. This development host has no GPU CLI,
  so the live panel correctly reports unavailable; four-card behavior is synthetic-tested.
- On-phone acceptance caught a pre-existing recovery presentation issue: a completed
  run initiated outside the phone could duplicate its saved answer and add an empty
  prompt bubble. Observed-run reconciliation now matches canonical output without
  inventing a prompt. A regression test and a new live response on the phone passed.

Deployment: `server-patches/bellpull-phase3/activate.py` recognized installed hashes
and verified idle agents/background queues before copying/restarting. The local GPU
plugin was installed under `~/hermes-data/plugins/bellpull_gpu` and enabled through
Hermes after announcing the config change. A mode-0600 configuration backup is outside
the repository. Authenticated capabilities/catalog/artifacts/plugin respond normally;
unauthenticated requests return 401. Global provider/model defaults, keys and ports
were not changed. Recreating the container must use the derived image
`hermes-agent:bellpull-phase3-20261010`; copying live modules alone does not alter the
external deployment configuration. The repo includes the build chain, source hashes,
reviewable patch, isolated tests, activation/rollback helper and API contract.

Validation: **282 JVM tests passed, one opt-in smoke skipped; 97 screenshot baselines
verified; debug APK and lint passed with zero errors, 36 advisory warnings and four
hints.** Six artifact-store tests, seven native HTTP/transport/catalog tests, seven
GPU parser/cache/auth tests, eight Phase 2 tests and 16 branch/edit regressions passed
in isolated environments. No real data or upstream inference was used in those tests.

Dedicated Android runtime acceptance passed with real MediaStore image/PDF URIs:
share-in never submitted automatically, Activity recreation retained the draft,
forced first-upload 503 preserved text/IDs, retry completed once, and PDF page changes,
image/generated previews and reopening worked. Manual Android SAF and camera imports
passed. `ShareProbeActivity` in the separate test APK/UID received an actual PDF from
the Sharesheet, read matching bytes and verified read-only/non-persistable flags.
Credential-clearing tests ran only on the disposable emulator, never on the phone.

Live Hermes read a synthetic uploaded image/PDF and generated a new one-page PDF using
its existing coding tools, with no package installation. Authenticated downloads were
repeatable and byte-identical. The physical unfolded Pixel 10 Pro Fold was upgraded
in place; saved profile/theme/immersive preferences remained intact. It verified native
image/PDF previews beside chat, reopening, Tools sorting, unavailable GPU readings and
session-only model/reasoning changes. The user's phone restart fixed a pre-existing
Tailscale DNS failure; no Bellpull URL/key or TLS/DNS bypass was introduced. The final
external-run check showed one saved response and no blank prompt bubble.

Limits: real four-GPU telemetry, physical cover/fold transitions and deployment on the
external compute server remain untested. Cover/corner layouts have rendered coverage.
Generated files must actually be written into the supplied output directory; an
arbitrary path in prose is not a downloadable artifact. Collection follows normal
agent return, so interrupted work can leave unpublished output. Server artifacts have
no automatic deletion policy in this preview; removing an Android profile deletes local
copies only. PDF generation uses whatever coding libraries/converters the server has.

Installed/released APK SHA-256:
`566d32e61081531da4aca2d5fc72d4267cad8affc35e7ccab224bd9869b6763a`.
Release: `android-v0.3.0-preview.1`, asset `Bellpull.apk`. Local live/device evidence
stays in ignored `android/app/build/device-tests/phase3/`. No credentials, live chats,
phone captures, databases or emulator disks are release assets. Phase 4 has not begun.

## Public README gallery — 2026-10-09

The user made the repository public. The README now displays the app icon and five
current GUI screenshots: maroon unfolded/camera-cutout layout, dark three-pane layout,
disconnected welcome, compact message controls, and saved server profiles/themes.
`docs/images/README.txt` records their source assets. All images are unchanged copies
of the app icon or verified screenshot baselines with fictional data; no phone captures
or private account data were added. The Android APK download remains at the top.
The current release notes now reflect public availability. This is a documentation-only
update; the tested 0.2.0 APK is unchanged.

## Phase 2E server profiles and disconnected exploration — 2026-10-09

This completes the user's addition to the current Bellpull Phase 2, not the older
Tier 1/2/3 phone-bridge roadmap. The GitHub APK/quickstart shipped first as
`android-preview-20261009.1`; the current Android update is version 0.2.0 (code 2),
released as `android-v0.2.0-preview.1`. The README download and instructions now match
the new build. The repository was private at this release's initial publication;
the user subsequently made it public (see the gallery update above).
The existing Mint emulator release still bundles the earlier 0.1.0 APK.

- Fresh installs open Bellpull without a login gate. The welcome page offers a
  clearly labelled local sample conversation and Settings. The sample makes no
  inference/network requests; sending is disabled. Copy, device read-aloud,
  navigation and appearance can be explored without connecting an agent.
- Settings begins with Server profiles: named encrypted URL/key pairs, add/connect,
  use/reconnect, rename, sign out, remove one profile, and remove all credentials.
  Sign-out persists without forgetting saved profiles. Confirmed removal deletes
  local credentials and that profile's cached chats, drafts and recovery data;
  it neither deletes remote chats nor revokes a key on the server.
- The old encrypted connection migrates atomically into `legacy` / Existing server.
  That profile retains the original cache filename and all its saved local state.
  Other profile IDs get separate encrypted caches for sessions, drafts/revisions,
  receipts, capability documents, run journals, cursors and notification identities.
- Switching detaches the old store, cancels HTTP/SSE requests and pending probes,
  stops speech/listener and resets visible navigation. Closed caches reject late
  writes, including journal writes racing removal. Draft callbacks capture their
  own cache. Profile-tagged notification intents cannot open an identically named
  chat on another server. Notifications follow the selected profile.
- Remote work continues when switching or signing out. Returning to a saved profile
  resumes its normal durable-run reconciliation. Removing its local state deliberately
  forgets that recovery journal. Cached verified capabilities still permit offline
  opening. API keys never enter Compose saved-state bundles or connection toString.

Validation: `test verifyRoborazziDebug :app:assembleDebug :app:lintDebug` passes:
266 JVM tests passed, one opt-in live smoke skipped, 89 screenshot baselines verified,
zero lint errors, 32 advisory warnings and four hints. Five instrumentation tests
passed on the dedicated API 36 emulator: encrypted storage/isolation/migration,
closed-cache write protection, and existing window chrome checks. Credential-clearing
instrumentation ran only on that disposable emulator, never on the configured phone.

Two local synthetic HTTP/SSE servers with identical session IDs verified A -> B -> A
history/draft isolation, switching during B's admitted run, completed B recovery
without contaminating A, clearing only the submitted draft, sign-out surviving
force-stop/relaunch, and confirmed removal of all credentials surviving relaunch.
They use a public dummy fixture key, require no inference and modify no live server.

The physical Pixel 10 Pro Fold was updated in place without clearing application data.
Its existing connection migrated, its original chat/empty draft returned, sign-out
survived force-stop/relaunch, the local example opened, and Use profile reconnected
without re-entering the key. White/Maroon, notifications on, immersive on and Reduce
motion off were preserved. No display/density/fold overrides or new live inference.
Physical cover/fold transitions remain untested; cover layouts have rendered coverage.
No compute-server address was supplied, so only the existing real profile is configured.

Installed/released APK SHA-256:
`393025cd3455b47d67ce5fc6e6a4913f459a003c45d110244c97d86325f9cc73`.
Local evidence is under `android/app/build/device-tests/2026-10-09-profiles/`, excluded
from Git and release assets. The encrypted pre-upgrade preference backup remains
private in that ignored directory. No keys, chats or phone screenshots are published.

User direction: after testing, update GitHub and stop for further instruction.
Phase 3 remains pending. OpenClaw received only a brief documentation investigation;
there is no OpenClaw adapter in this build. Prior recovery/notification operational
limits below still apply, including force-stop suppressing delivery until reopening.

## Linux Mint emulator preview — 2026-10-09

User requested a desktop Android-emulator build after the phone was handed back,
and authorized pushing it to GitHub. Branch `desktop/mint-emulator-preview` builds
on the immersive UI commit. The APK is unchanged from the tested phone build below.

`desktop/mint-emulator` provides a Python standard-library launcher, per-user
installer, `.deb`/portable packaging and a local synthetic durable-run smoke server.
Android SDK/runtime files are downloaded separately from Google, with license prompts;
the release contains only the launcher, installer, icon, APK, metadata and README.
It reuses an existing SDK or verifies/downloads pinned command-line tools for setup.
No SDK, AVD disks, accounts, server keys or test data enter the packages.

Each data directory gets a distinct AVD name. All device commands explicitly target
an emulator, and installation/stop verify both its AVD name and emulator property.
The preview never targets the physical phone or kills the shared ADB server. Existing
AVD configuration and local app data survive updates. Startup is locked per profile.
Default VM: API 36 AOSP x86_64, 1280x800 at 160 dpi, 2 GB RAM, two cores and hardware
keyboard. Window resizing scales Android; this is not the proposed native desktop port.

Validation on Linux Mint 22.3, emulator 37.1.11 with working KVM and the existing SDK:
fresh dedicated AVD boot/install, connection, three-panel UI, fresh word-by-word SSE
reply, composer clearing, final reply visible, interrupted delivery recovery, repeat
launch, stop/restart and retained login. Eight launcher tests pass. Package file
allowlist, desktop-entry syntax, Python compilation and secret scanning are checked.
The initial mock server advertised durable admission without implementing it; the
desktop-only smoke fixture now supplies synthetic admission/status/events/history.
No production server changes or live inference were needed.

Restart testing exposed a transient occupied-port popup. Port probes now use
SO_REUSEADDR, --stop waits for ports to close, and a regression test covers recently
closed sockets. --no-dialog supports GUI automation without interrupting the user.
The phone remained hands off throughout desktop work.

Known limits: Android TTS needs its own engine/voice, notifications stay inside the
emulator, and simultaneous live subscribers still need backend fan-out. Plain HTTP
to Android's 10.0.2.2 alias is blocked by the existing app policy; use HTTPS/Tailscale
or an explicit emulator-only adb reverse with a loopback URL for local development.
Clean-machine SDK installation/license prompts are not end-to-end tested. Preview
uses the debug APK and has no automatic updater.

## Immersive chrome and sliding panels — 2026-10-09

Implemented and installed the resumed GUI experiment on branch `ui/immersive-chrome`.
The user has the phone back; subsequent desktop testing must target its own emulator,
never the physical phone unless the user requests phone work again.

- Settings has an opt-in Immersive mode switch, default off. Status/navigation bars
  hide with Android's transient edge-swipe behavior; disabling the switch restores them.
- Headers use Android-reported cutout rectangles in window coordinates. Whole controls
  move into safe intervals; titles ellipsize within one interval. A very wide notch
  falls back to a row below it. The remaining body starts below the camera area.
  IME, side/bottom safety and desktop caption-bar insets remain respected.
- Unfolded side panes and cover navigation slide in 180ms. Reduce motion and Android's
  disabled animator setting suppress panel motion. Existing nonimmersive spacing remains.
- Sliding a pane exposed a transcript-follow regression: earlier messages could rewrap
  without changing the final row's height. Following now observes width and scrollability
  as well, keeping the latest reply visible while respecting deliberate history browsing.

Validation: `test verifyRoborazziDebug :app:assembleDebug :app:lintDebug` passes, with
250 tests passed, one opt-in live smoke skipped, 83 screenshot baselines and zero lint
errors (28 existing warnings, 3 hints). New tests cover camera positions across pane
widths, centered/corner rendered layouts, toggling both panels, intermediate animation
frames, Reduce motion, and transcript following through repeated width changes.

On the actual native unfolded Pixel Fold, all five header buttons clear the reported
camera bounds `[1940,0][2076,160]`; the context close button occupies `[1791,22][1923,154]`.
Verified keyboard directly below the composer, both pane toggles, immersive on/off,
normal system-bar restoration and immersive persistence after Home/reopening. Original
chat, empty draft and White/Maroon theme are preserved. No fold/display/density overrides,
credential-clearing tests or new inference requests. Physical cover/fold-transition
behavior is not verified; centered-cover coverage is rendered tests only.

The installed APK matches SHA-256
`5b78fdc4c1a06f9602a50cbf686e73c562cfe9ed5172574d04880223800f57a0`.
Evidence is under `android/app/build/device-tests/2026-10-09-immersive/verification.json`
with screenshots beside it. Phone was handed back with immersive on, Reduce motion off,
the original chat open and both side panels visible.

Desktop discussion: Compose Desktop could reuse JVM networking/models and suitable UI
after separating Android storage/speech/notification services. Existing durable-run SSE
is a consuming queue, so simultaneous phone/desktop live subscriptions need a verified
fan-out/replay design before promising full multi-client streaming. User instead requested
an initial Mint Android-emulator preview, to be built and tested after phone handoff.

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
