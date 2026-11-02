## Experimental Canvas and plugin reload release — 0.19.0 / build 48

Published as [android-v0.19.0-experimental.1](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.19.0-experimental.1),
paired with plugin **0.8.3-reload-preview**. The release combines the existing
Kanban controls with shared desktop/mobile Canvas, streamed drawing operations,
touch editing and Undo/Redo. It also restores Bellpull routes after unchanged
plugin rediscovery, preserves active runs and honors explicit plugin disable.
Replacing plugin code still requires a gateway restart.

Validation: 802 Android JVM checks (801 passed, one existing skip), 199 unchanged
screenshot comparisons, debug assembly and lint passed. Lint reports zero errors
and 91 warnings. The plugin suite passed 215 tests with one skip; six rediscovery
checks passed on each supported stock Hermes 0.21.1 and 0.21.5. The isolated
wireless Pixel reload test passed, covering retained board/run state and disable/
enable behavior. Signed assembly and signature checks passed; publication evidence
and checksums accompany the release assets. The signed build48 APK was installed
over build47 through wireless ADB; installed bytes match the release, and app UID,
data directory and original installation time are unchanged. Personal profiles
were not opened for acceptance.

Known experimental limit: the latest HTTPS/Tailscale Canvas device test reached
shared editing/reconnect but failed its subsequent main-conversation opening
assertion. That remains unresolved; this release does not claim complete Canvas
phone acceptance. Mobile selection-aware prompts and export controls are follow-up
work. Integration-status UI (#42) remains proposed work, not an included feature.

Install the signed APK over the current app to preserve data. Install the matching
plugin bundle and restart the gateway for Canvas and reload recovery. Moe remains
a manual user deployment. Assets include the APK, complete matching plugin,
source with pinned submodules, roadmap and SHA-256 checksums.
[Canvas scope and limits](SHARED-CANVAS.md) · [Reload behavior and evidence](PLUGIN-REDISCOVERY.md).

## Experimental Canvas source integration — 0.19.0 / build 48

Combines the published 0.18.2 Kanban release with shared conversation Canvas and
plugin **0.8.2-canvas-v1-preview**. Desktop and mobile use the same native tool,
authenticated snapshots and streamed committed changes, retaining existing chat
identity. Mobile adds touch editing, collapsed Drawing details, full-screen
controls, cached viewing and Undo/Redo. The source is merged into the current
experimental branch; no new APK/release assets or server deployment are included.

The combined debug app and instrumentation build pass, with 802 JVM tests
completing without failures (one existing skip), 199 unchanged screenshot baselines
and zero lint errors. Nine Canvas checks also pass on unmodified stock Hermes
v2026.9.24, including native tool-call identity and grouped Undo. Earlier isolated phone
checks passed. The latest HTTPS/Tailscale test reached shared editing/reconnect
but failed its subsequent main-conversation Canvas-opening assertion; that check
remains unresolved. Mobile selection-aware prompts and export controls are still
follow-up work. [Contract, validation and limitations](SHARED-CANVAS.md).

## Experimental native Kanban release — 0.18.2 / build 47

Publishes native board/list/detail views, task creation and field editing, comments,
non-executing moves, offline recovery and the shared Today preview. Also fixes
deleted tasks blocking unrelated edits, queue overflow during rapid actions,
late callbacks recreating submitted drafts, Unicode text-limit mismatches and
decoding native priorities outside the mobile editing range. Queue identities,
unsent text and original draft revisions remain durable. Plugin runtime code stays
at **0.8.1-integration-preview**; the test gateway needs no new deployment.

Six regression cases reproduced the failures before repair. The native Unicode/
priority contract is also covered on stock Hermes. Published as
[android-v0.18.2-experimental.1](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.18.2-experimental.1) with the signed APK, matching plugin,
complete source including pinned submodules, roadmap and checksums.
Moe remains a manual user deployment. Local validation artifacts are under
`/home/praxisog/Bellpull-builds/kanban-bugfix-development/`.
Validation: 796 JVM tests, 200 plugin tests, 199 screenshot baselines and all four
physical Kanban tests passed. JVM and plugin suites each retain one skip. Lint has
zero errors; debug and signed release assembly pass. The existing test gateway's
Canvas/Kanban capabilities and matching Kanban runtime were verified read-only.
Signed build47 is installed in place with matching release bytes and preserved
signing identity, app UID, data directories and original installation time.
[Implementation and validation](PHASE-18-KANBAN.md#validation).

## Local Kanban creation/editing build — 0.18.1 / build 46

Adds creation in triage and edits to title, description, assignee profile and
priority. Encrypted forms retain their original revision through refresh/restart;
queued creations recover the native task ID from atomic receipts. Active work
cannot be edited, and stale UI actions cannot discard unknown delivery.
Requires standard plugin **0.8.1-integration-preview** for the new controls.

The local test Hermes was updated while preserving its separately developed Canvas
preview, configuration and 68 sessions. Its composite plugin reports
`0.6.1-canvas-v1-preview+kanban.0.8.1`. Moe remains a manual user deployment.
Signed build46 is installed in place on the Pixel 10 Pro Fold with matching APK
bytes, signing identity, app UID and data directories. Validation: 789 JVM tests,
199 screenshot baselines and all three physical Kanban tests passed; lint has zero
errors. The plugin suite passed 199 tests with one skip; JVM checks also have one
opt-in skip. The test gateway passed create/edit/conflict/receipt checks without execution.
Local artifacts are under `/home/praxisog/Bellpull-builds/kanban-editing-development/`.
This increment has not been published.
[Implementation, validation and remaining scope](PHASE-18-KANBAN.md).

## Initial Kanban development build — 0.18.0 / build 45

Adds native task board/list/detail views, comments, non-executing moves to triage or
blocked, encrypted offline queues with receipt recovery, and a Today task preview.
Requires matching plugin **0.8.0-integration-preview** on a compatible Hermes host.
[Implementation, validation and remaining scope](PHASE-18-KANBAN.md).
This local build was installed in place before the creation/editing increment.

## Settings controls — app 0.15.1 / build 44

Reply notifications can now be enabled on stock Hermes without a push relay,
using the existing foreground polling service. The whole setting row is tappable,
and permission denial has a visible explanation and Android settings shortcut.
Choose default assistant opens Android's assistant settings instead of submitting
a non-requestable role request. Plugin 0.7.0 is unchanged.

[Repair details and validation](RELEASE-0.15.1-EXPERIMENTAL.md).

## Phone assistant and context — app 0.15.0 / build 43, plugin 0.7.0

Android assistant entry, bounded direct phone commands, foreground speech and
new-chat draft handoff are available in Settings → Phone assistant. The offline
notice now dismisses after five seconds, with Android accessibility timeout support.

Separate opt-ins sync recent notifications and selected upcoming phone-calendar
events to a profile-scoped plugin cache for new API chats; offline chats have their
own local controls. Incoming sync creates no chats/runs. Cron conversations appear
in a separate Notifications view. Existing sharing does not silently opt into the
broader snapshot. Phone calendars work independently of Nextcloud availability.

758 Android checks and 187 plugin checks pass, each with one existing skip;
screenshot verification, lint and signed release assembly pass. Context checks
pass on stock Hermes 0.21.5 and 0.21.1. Physical synthetic fixtures cover assistant
state and phone sources → plugin → native chat prompt → revocation at both widths.
Build43 is installed in place; installed APK bytes match the signed release and
UID/data directory are preserved. Default assistant selection, real phone-action
handoffs and long background/Doze behavior still need manual acceptance.

[Download experimental app and plugin](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.15.0-experimental.1) ·
[Behavior, protocol, limits and evidence](PHASE-15-ASSISTANT.md).
No live Hermes plugin deployment was performed by this publication task.

## Phone loudspeaker — 0.14.10 / build 42

Telecom voice mode requests the loudspeaker when the phone would otherwise use
its earpiece and no car/headset/streaming endpoint is available. It rechecks on
resume and endpoint changes, and leaves external audio routes to Telecom.
Routing uses [Core-Telecom's endpoint API](https://developer.android.com/develop/connectivity/telecom/voip-app/telecom),
without competing AudioManager routing commands. Plugin 0.6.2 is unchanged.

Validation: 50 focused voice JVM tests and lint passed. The isolated Pixel test
verified actual speaker selection at start, after ten seconds screen-off,
after hold/resume and across three restarts; ending voice restored normal audio.
The signed build42 update is installed in place, with matching APK hash and the
same personal app UID/data directories. Real car acoustics remain for manual testing.
See [device result](evidence/voice-speaker-20261023/device-test.txt) and
[validation record](evidence/voice-speaker-20261023/validation.json).

Manual acceptance checklist:

- With Bluetooth/headsets disconnected, start voice and confirm the phone's
  loudspeaker plays replies at a comfortable call-volume setting.
- Pause/resume, lock the screen, and reopen the app; speech remains audible.
- Stop voice from the app and notification; neither closes the app, and the
  call notification disappears. Start again and confirm loudspeaker output.
- Connect the car/headset before starting voice; confirm it receives speech.
  Test connecting/disconnecting it during a session and verify the resulting route.
- While parked, test navigation announcements and an incoming call; confirm
  voice pauses/resumes appropriately without overlapping or repeating speech.
- Interrupt a spoken response; old queued sentences must stop rather than
  play over the next turn. Check that the loudspeaker does not transcribe itself.
- Switch Wi-Fi/cellular, then briefly disconnect/reconnect Tailscale; check
  recovery and that responses do not appear twice. Verify both server profiles.
- Run a 15-minute voice session; note heat, battery use, missed speech and gaps.

## Moe thinking controls — plugin 0.6.2, app 0.14.9 unchanged

The named `moe/moe-local` route now advertises the verified Qwen controls and
keeps that identity when Hermes resolves its transport to `custom`. Minimal,
High and Max are rejected before saving. Old invalid choices have a clear reset
instruction; Default repairs the existing conversation without changing its model.
See [release notes and installation](RELEASE-PLUGIN-0.6.2-EXPERIMENTAL.md).

## Startup replay repair — 0.14.9 / build 41

Restoring a long live reply could retain a native regex matcher for every word,
freeze the main thread and exhaust memory. A bounded token-prefix scan removes
that allocation pattern while preserving reconnect deduplication and raw streams.
Plugin 0.6.1 is unchanged. See [release notes and device checks](RELEASE-0.14.9-EXPERIMENTAL.md).

## Thinking level menu — 0.14.8 / build 40

The conversation’s top-right **⋮ → Thinking level** opens a submenu containing
only the selected model’s supported controls, with its current choice marked.
Selecting one closes the menu and saves it for that conversation. Model/provider
selection stays intact; rejected saves retain the previous choice and report an
inline error. Plugin 0.6.1 is unchanged. See
[release notes and UI checks](RELEASE-0.14.8-EXPERIMENTAL.md).

## vLLM reasoning controls — 0.14.7 / build 39

App **0.14.7-experimental**, plugin **0.6.1-integration-preview**. Model controls
now work on the tested stock Hermes 0.21.5 without a core patch. Qwen3.8 Flash Next
supports Off/On/Low/Medium/XHigh per conversation. The app sends native request
options and the plugin translates them into vLLM's reasoning/template fields.
Actual Moe inference, saved choices and idempotent retries passed. The signed
app is installed in place, and PowerMac runs the matching plugin with Moe as an
additional selectable route. Its default model is preserved.
See [release notes](RELEASE-0.14.7-EXPERIMENTAL.md) and
[validation, setup and limits](evidence/vllm-reasoning-20261022/README.md).

## Kokoro sentence streaming — 0.14.6 / build 38

Kokoro prepares one sentence ahead while speech plays. Voice mode and Read aloud
share bounded phrase splitting and immediate Stop. See
[release notes](RELEASE-0.14.6-EXPERIMENTAL.md) and
[physical-phone measurements](evidence/kokoro-sentences-20261022/README.md).

## Voice and media repairs — 0.14.5 / build 37

Kokoro small CPU TTS, voice-exit repair, OpenCode activity in the conversation
menu, adjustable image previews and reconnect response deduplication. See
[release notes](RELEASE-0.14.5-EXPERIMENTAL.md).

## Telecom call mode and streaming speech — 0.14.4 / build 36

App **0.14.4-experimental**, plugin **0.6.0-integration-preview**.

Call mode now registers a persistent Android Telecom call, with platform audio
routing and hold/resume/end/mute integration. Server recognition streams PCM while
recording and displays partials; only finalized text reaches the existing
voice-turn logic. Cancellation, bounded queues and explicit Retry preserve the
recording without silently replaying an accepted stream to the server.

PowerMac runs the authenticated CPU helper with **FP32 Moonshine medium**, selected
after CPU measurements. The original FP32 export supports true incremental
decoding; it was about 2.1× faster than native INT8 on four public clips. This
helper is STT-only. Existing TTS settings remain user-owned.

Validation: 714 JVM checks (one existing skip), 187 unchanged screenshot baselines,
lint, signed ARM64/x86_64 release assembly, 168 plugin checks (one existing skip),
15 modern stock-host checks, six helper checks and two physical-phone tests.
The phone tests exercise system Telecom with screen off/hold/resume/hang-up and
public audio through Android → plugin → CPU. Actual car acoustics, incoming SIM
calls, navigation interruptions, extended heat and cellular reconnection remain
open. See [measurements, installation and limits](evidence/car-streaming-20261020/README.md).

## Open-issue repairs — 0.14.3 / build 35

App **0.14.3-experimental**, plugin **0.5.2-integration-preview**.

- #20: publish durable history and retire temporary response/prompt rows together;
  subsequent status updates cannot restore an already-saved response. Matching
  tolerates transport whitespace while excluding pre-existing repeated prompts.
- #21/#22: thinking disclosures respond inside tall bubbles; a short body tap
  collapses expanded thinking. Native selection/copy and ordinary message actions
  retain distinct touch handling, including during recomposition.
- #23: automatic Gemma warm-up waits for 10 seconds of continuous disconnection;
  the connection is rechecked before native loading, and reconnecting does not cancel a load already in flight. TTS/STT load attempts have
  a monotonic 10-second cooldown, cached engines remain reusable, and explicit
  Resume/Retry/Settings actions can retry immediately.
- #14: the plugin atomically repairs an untitled/automatically derived voice title
  without overwriting a manual, legacy, or LLM-generated title. Pending repairs
  survive the appearance of a server-generated wrapper title. The new behavior
  requires the advertised `bellpull_voice_titles` capability; older plugins retain
  the previous title path.

Validation: 705 JVM tests (one existing live-server skip), 187 unchanged screenshot
baselines, lint and ARM64/x86_64 release assembly. Plugin: 165 checks (one existing
skip), plus seven checks against modern stock Hermes. Two physical Pixel fixture
checks cover tall response taps/native selection/copy/message actions and real
Pin/Unpin touches plus Activity recreation. The pin control passed; no new pin
implementation or diagnosis of the earlier personal-server failure is claimed.

PowerMac's local Hermes was upgraded to plugin 0.5.2 after verifying no active work.
Live authenticated discovery/title repair/manual-rename protection passed, rejected
unauthenticated repair, and the disposable test chat was deleted. The phone's saved
**PowermacG3 Test** profile is selected. Moe was not changed.

Car routing, acoustic cutoff, sustained TTS quality/heat, and the separate server
STT quantization/streaming requests remain open. See
[issue evidence](evidence/issues-20261019/validation.json).

## Local prompt settings — 0.14.2 / build 34

Local fallback settings now expose the editable system prompt, encrypted storage,
Save/default controls, and clearly labeled 2K/4K/8K context choices. An explicit
button asks the selected Hermes server for a short personal profile in a separate
chat; the user reviews and copies the reply into local settings. Existing drafts
remain intact, and the request is submitted once after successful history loading.
Oversized local prompts preserve both the message draft and saved conversation.
Plugin 0.5.1 is unchanged. See [implementation and acceptance](PHASE-14-OFFLINE.md).

## Integrated local fallback — 0.14.1 / build 33

Local chats now share the normal conversation list and shell. Automatic foreground
loading, red/yellow readiness, offline + and online long-press +, configurable
2K/4K/8K contexts, resumable chunk compaction and automatic local-history compaction.
Approved resync remains idempotent and imported text is searchable in Hermes;
the main conversation list shows one entry per synced chat. Source and local
originals remain saved. Plugin 0.5.1 keeps the existing 0.5 handoff contract.
This is a locally installed experiment, not a published GitHub release.
See [implementation and acceptance](PHASE-14-OFFLINE.md).

## Local Phase 14 experiment — 0.14.0 / build 32

Persistent encrypted local forks, Gemma 4 E2B CPU with a thinking toggle,
source-only semantic/keyword search and read-only retrieval tools, cached Hermes
memory, optional offline preparation, and reviewed idempotent handoff into a new
server conversation. See [implementation and acceptance](PHASE-14-OFFLINE.md).
Memory and handoff require Bellpull plugin 0.5.0-integration-preview; the local
test Hermes is updated. This build is a locally installed experiment, not a
published GitHub release.

## Local Phase 13 experiment — 0.13.0 / build 31

Adds an explicit local text inference lab using the supplied Imagination GPU
experiment. See [implementation and measurements](PHASE-13-LOCAL-LLM.md).
Plugin runtime remains 0.4.3; no server update is needed for the lab.

## Published experimental build — 0.12.0 / build 30

`feature/phase12-notification-context-0120` adds [Phase 12 phone notification
context](PHASE-12-NOTIFICATION-CONTEXT.md) on top of the car experimental build and
[Today dashboard](PHASE-11-TODAY.md). Capture and sharing start off. Android access,
encrypted local history, app exclusions, retention, search, updates/removals and
per-server opt-in sharing are implemented. Relevant excerpts use existing chat/run
APIs; no server migration or new endpoint is required.

Plugin code remains 0.4.3-integration-preview. Published as
[`android-v0.12.0-experimental.1`](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.12.0-experimental.1)
from `4741b4b`, with the installed build30 APK, matching plugin, recursive source,
roadmap and checksums. Older downloads below are historical.

## Historical development release — 0.8.4 / build 25

The canonical source for both Android and the plugin is `master`. The matching
pair is **Android 0.8.4-integration-preview/build25** and **plugin
0.4.2-integration-preview**, published under
`android-v0.8.4-integration-preview.1`. Historical releases are retained for
reference and rollback; they are not separate active development lines.

The release contains the signed `Bellpull.apk`, complete
`bellpull-plugin-0.4.2-integration-preview.tar.gz`, recursive
`Bellpull-source-0.8.4.tar.gz`, phase roadmap and `SHA256SUMS`. The source archive
includes pinned native submodules and `SOURCE-REVISIONS.json`. It excludes model
weights, credentials, signing material, build caches and untracked personal files.
Use the [build guide](BUILDING.md) or the [plugin installer](../plugins/bellpull/README.md).

This consolidates the audit repairs: calendar revocation/retained receipts,
transcription Retry, Stop feedback, streaming follow, message statistics and media
routing for standalone Read aloud. A screenshot fixture now publishes project
state on the main dispatcher used by the app; its existing baselines are unchanged.
No new speech engine or silent fallback is introduced. Pocket/Loona was explicitly
enabled on the owner's phone in the preceding setup; an app update preserves it.

Validation: 598 Android tests passed with one live-server skip, 172 screenshot
baselines, debug/release builds and lint. Plugin runtime is unchanged from the
validated 0.4.2 repair: 148 passes/one skip on the full legacy-image suite and
66 passes/one skip on the stock-host subset. Publication also verifies the signed
APK/16-KiB alignment, archive contents and an extracted plugin installation.

See the [October 15 reliability audit](EXPERIMENTAL-ISSUE-REPAIRS.md#october-15-re-audit)
for ten closed issues and six remaining open issues. Physical car/headset routes,
sustained speech heat, pin touch behavior, title recovery and provider/cloud
acceptance remain explicit limitations. This remains a development preview.

## Historical experimental release — 0.8.3 / build 22

The user authorized publishing Android 0.8.3-integration-preview/build22 with
plugin 0.4.1-integration-preview. ADB confirms the installed APK is exactly the
signed release artifact; no reinstall was needed. See the
[0.8.3 release record](RELEASE-0.8.3-EXPERIMENTAL.md) for checksums, installation,
known unresolved audit findings and CI results. Source is on
`repair/experimental-open-issues`; tag `android-v0.8.3-integration-preview.1`.
Older version and deployment statements below are historical records.

## Local combined integration preview — 0.8.1 / build 20

`integration/phase6-8-9` combines the independently tested OpenCode and planning
branches. App `0.8.1-integration-preview`, plugin `0.4.0-integration-preview`.
Signed local artifacts are in `build/releases/0.8.1-integration-preview/` with
`SHA256SUMS`. The user authorized publishing the matching
[experimental prerelease](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.8.1-integration-preview.3),
including the phase roadmap. This is not a stable-release declaration.
The APK uses the existing signer/rotation lineage; signature, version metadata
and 16-KiB ZIP alignment were checked. SHA-256:
`84069d410f42cefea93dea8d53f03475e89aed335f767fd747c1e81bab86d92b`.
Local checks and remaining CI/provider limits are in
[the combined handoff](INTEGRATION-6-8-9.md). ADB was returned October 13 and the
combined fixture passed 11 final device-test executions, including both widths and
the real calendar permission flow. The initial navigation-harness timing failure
was fixed; production APK bytes are unchanged. Fixture cleanup is complete.
The user then requested the latest personal app. Signed build20 was installed
over corrected 0.5.5 with the same certificate, UID10260 and CE/DE data-directory
inodes 117766/116002. The previous installed APK was copied to `personal-upgrade/`
first; installation and package verification logs are beside it. The app was left
stopped for the user to open. No personal plugin deployment, personal-server
access occurred during installation. Subsequent GitHub publication is separately
authorized; preview app code remains on `integration/phase6-8-9` and the default
branch receives only the front-page/roadmap documentation update.

## Corrected 0.5.5 installation — 2026-10-12

The first 0.5.5 APK from `c5dceec` crashes on launch: the shipped Qwen checkpoint
ID `qwen-0.6b` was rejected by the new folder validator. **Do not use that artifact**
(SHA-256 `42c5d4ff4c943bdef7f36bcd064491b374602c9d5fa77143d54e4762a7c847cb`).
Fix `c913243` preserves the ID/downloads and permits the safe decimal dot. Eight
focused JVM tests and one isolated real-phone fixture startup/preflight/Activity
check passed. The corrected signed 0.5.5/code 17 APK was installed in place:
SHA-256 `1e7121c0a32658ded36fcb02122b7b581f8c5dd0a8993646b0f25f28f20415d5`.
Signer, UID 10260 and both data-directory inodes were preserved. Personal app was
left stopped; fixture packages removed. No personal server was accessed.
This startup check does not verify Pocket synthesis, audio routes or provider use.
Later local baseline/API-level guard corrections are in `feature/local-voice-models`;
Phase 6 and Phase 8/9 previews inherit them and remain independent branches.
No preview installation, push, merge or release publication is implied.

# Building and upgrading Bellpull

The current `feature/voice-turn-taking` branch builds **0.5.3, version code 15**.
Tap the microphone to start/end conversation mode; hold and release to dictate into
the message draft. The active button pulses, and a brief chime signals microphone
readiness. Settings → Voice → Using voice documents the controls; Debugging voice
interface restores the previous panel. See [voice behavior and limits](PHASE-5-VOICE.md).
No plugin update, new dependency or provider thinking-control change is required.
The current experimental repair permits complete long narration; Brief voice responses
is an optional style preference that honors explicit requests for detail. Live activity
announcements use actual events, and recognized non-echo speech requests the normal
chat Stop before one replacement admission. Completed runs still syncing history are
allowed to reconcile without a redundant Stop. Failures/30-second timeout keep new
words for review instead of waiting indefinitely or resubmitting the interrupted run.

0.5.3 validation passed **501 local tests**, one opt-in live-server skip, **158
screenshot checks**, debug/release builds, lint and five isolated unfolded-phone
checks. See [the current verification record](PHASE-5-VOICE.md#verification-of-053-voice-controls).
The signed APK passed signature and 16 KiB ZIP-alignment verification. SHA-256:
`e42c207a9bd8535a6ca13b35bf6b0266355d1f4f6e8bf5450f1087b2afd16d82`.
It was installed over 0.5.2 with the same signing lineage, UID and data-directory
inodes; personal data was not cleared and the personal app stayed stopped. No
personal Hermes/provider call was made. Model selection and downloads were not
changed. Test packages, our two reverse ports and local fixture servers were removed.
Signed delivery and prior rollback APKs remain under ignored `build/releases/0.5.3/`
and `build/releases/0.5.2/`; logs are under `build/speech-lab/voice-controls-*` and
`build/speech-lab/personal-upgrade/0.5.3/`. Do not uninstall or clear app data to
roll back; Android's version-code downgrade restrictions still apply. No public
release was published. Real acoustic/provider acceptance remains open.

The previous local-speech branch built **0.5.2, version code 14**, from the Phase 5
foundation. See [local speech status and setup](LOCAL-SPEECH-LAB.md) and
[Phase 5 acceptance](PHASE-5-VOICE.md). No plugin update is required for voice;
download Moonshine for local CPU recognition, or explicitly select your own private
transcription service. Answers use the phone's installed offline voice. Phone
recognition, native switching/local review and thermal comparisons passed in the
isolated fixture app; [results](../benchmarks/speech/pixel-10-pro-fold-2026-10-11/README.md)
include limitations. 0.5.2 disables Start voice until the selected local model is
installed and model operations have finished. It checks files again before permissions,
service startup and recognizer creation; an unloaded installed model can still start.
Missing/incomplete/deleted-file regression tests and all three disabled voice modes
are covered. Private-server mode remains independent of local downloads.
Local validation passed 481 tests with one opt-in live-server skip, all 156 screenshot
baselines, debug/release builds and lint. Signed APK SHA-256:
`a681d69a0d326bd914d365907fea3ec0021d0f399acfeafc1cffb748b2eb2fd6`.
Local signed APK/rollback copies are under ignored `build/releases/0.5.2/`,
`build/releases/0.5.1/` and
`build/speech-lab/personal-upgrade/`. No release was published; do not test against
the personal Hermes server.

Two isolated phone regressions passed: real taps cannot start any of the three voice
modes without a model, and About reports the installed version. The signed 0.5.2
update was installed over 0.5.1 with the same signing certificate, UID and data
directory inodes. The personal app was left stopped and fixture apps removed.
No personal Hermes/provider call was made. Local logs for this patch are under
ignored `build/speech-lab/voice-start-guard-*.txt`; the 0.5.1 benchmark data above
remains the measured recognition/thermal evidence.

Isolated voice checks use `tools/voice-device-fixture.py` on loopback 18647 and
`mock-hermes/server.py` on loopback 18646 with `MOCK_HERMES_KEY=voice-device-hermes-key`.
These are fake inference/transcription, never deployment services. Build the fixture
APK/test APK with `-PbellpullDeviceFixture=true`, install only those for testing,
forward both ports with ADB, and explicitly select `VoiceDeviceTest` and
`ProjectsUiDeviceTest`. The latter checks drawer filters, About and keyboard Save. The current unfolded
run passed; the cover attempt timed out behind keyguard. Both screenshot widths
passed. Never run the full device suite on the personal app.

The public download remains 0.3.3 preview. The Phase 4 branch builds **0.4.0,
version code 11**, paired with **Bellpull plugin 0.3.0**. It starts at the pushed
0.3.7 repair commit `743dbe4`. Phase 4 remains a development branch, not a public release.
Do not reuse a version code after release.

The user returned ADB access and authorized installation, testing, fixes and a
branch push. The signed 0.4.0/code 11 update was installed over 0.3.7 with the
existing lineage and the same Android UID, without clearing data or uninstalling
the personal app. That app was kept stopped; personal-profile decoding and the
actual server/provider remain user acceptance. The personal Hermes server remains
off limits. See [Phase 4](PHASE-4-PROJECTS.md) for verification and its acceptance
checklist. Older delivery evidence below describes the completed 0.3.7 work.

For the next isolated device acceptance, start a fresh disposable stock fixture:

```bash
python tools/stock-device-fixture.py --hermes-source /PATH/TO/STOCK/HERMES --projects
./android/gradlew -p android -PbellpullDeviceFixture=true :app:assembleDebug :app:assembleDebugAndroidTest
```

For isolated testing, install only the separate fixture application/test APKs,
forward port 18646, and select `org.praxisog.hermes.ProjectsDeviceTest` explicitly.
The test is guarded against the personal app package. It checks encrypted cache
restoration, drafts, separate profile cache, project creation/moves/pin/new chat,
native branching and real compaction. No provider call is needed. The authenticated
`/fixture/projects/...` controls exist only in the disposable helper, never in
the shipped plugin. Restart the fixture before repeating the test. Select
`ProjectsUiDeviceTest` separately for real keyboard/Save touches and About
version/status checks at each Fold display state. It uses public Android APIs;
the current Espresso InputManager hook fails on Android 17 before executing a
Compose device test. JVM Compose screenshot/interaction tests remain in use.

0.4.0 device acceptance passed eight test executions (six distinct checks):
files and project persistence, two UI checks at each width, conversation sources,
and the 0.3.7 initial-stream/compaction/restart regression. Temporary display
overrides, port forwarding, fixture apps and local servers were removed afterward.

Build personal-app updates using `python3 tools/release.py build`, preserving
the same signing lineage. Compilation/signature verification alone is not device acceptance.

The completed 0.3.7 manual test delivery paired that APK with **bellpull plugin 0.2.2**.
Use the stock installation instructions in `plugins/bellpull/README.md`; Docker
and `server-host/` are not required for persistent generated-file sharing. The
user manages the personal server and has explicitly prohibited agent access.
Phone checks use `-PbellpullDeviceFixture=true`, which produces the separate
`org.praxisog.hermes.fixture` package and cannot load personal app profiles.
The preview-to-release signing transition was verified on the Fold. Use the same
signing lineage for the 0.3.7 update. The user authorized ADB installation and
isolated fixture acceptance of 0.3.7; the personal Hermes server remains off limits.
See [the audit repair report](AUDIT-0.3.6-REPAIR.md) for the final evidence.

0.3.6 fixture acceptance passed on the unfolded Fold with stock non-Docker Hermes:
all conversation sources, growing thinking, interim prose/tools, saved completion
and byte-identical PNG downloads. The cover-state attempt reached the lock screen;
its override was reset. Both widths pass JVM screenshot checks. See `HANDOFF.md`
for the exact evidence and remaining actual-provider/upgrade acceptance.

To reproduce the isolated streaming fixture, run the helper with a disposable
stock source checkout and its Python environment:

```bash
python tools/stock-device-fixture.py --hermes-source /PATH/TO/STOCK/HERMES --streaming
./android/gradlew -p android -PbellpullDeviceFixture=true :app:assembleDebug :app:assembleDebugAndroidTest
```

Install only the fixture APK/test APK, forward USB port 18646, and select
`PortableFilesDeviceTest` and `ConversationSourcesDeviceTest` explicitly. Do not
run the whole instrumentation suite on a personal phone. The source test leaves
the fixture profile configured for the visible chat check; authenticated POSTs
to `/fixture/advance` release each reasoning/tool/reply step. These loopback test
controls never ship in the plugin. Remove both fixture packages and forwarding
afterward; leave personal Bellpull stopped throughout.

For the 0.3.7 audit regression, start a fresh fixture with `--audit-repair` instead
of `--streaming`, then select `PortableFilesDeviceTest`,
`ConversationSourcesDeviceTest`, and `AuditRepairDeviceTest` explicitly. The last
test drives its own authenticated fixture controls: first SSE request fails,
thinking/answer reconnect, the prompt is compacted, completion precedes the final
saved reply, and encrypted tracking is restored before reconciliation. It asserts
one inference and no stale pending prompt. Restart the disposable fixture before
rerunning it; the fixture intentionally admits only one inference per process.

The 0.3.7 personal-app install is a signed `adb install -r`, preserving the installed
app rather than clearing data. Do not launch the personal profile for these tests:
the personal Hermes server remains off limits. Actual saved-profile/draft access
must be checked by the user. See the audit report for device and signing evidence.

## Checks before a release

```bash
./android/gradlew -p android test verifyRoborazziDebug assembleDebug lintDebug
docker build -f server-host/Dockerfile -t bellpull:checks .
docker run --rm --network none -e HERMES_HOME=/tmp/bellpull-tests \
  -e BELLPULL_NTFY_URL= --entrypoint python bellpull:checks \
  -m unittest discover -s /opt/hermes/plugins/bellpull/tests -v
```

CI runs these checks without signing secrets, production mounts or inference.
Inspect intentional screenshot differences before updating baselines. Check cover,
unfolded, dark/light and large-text layouts. Test the signed APK on the Fold;
rendered screenshots cannot establish actual upgrade, fold or network behavior.

## Stable signing

The published preview certificate SHA-256 is
`b8ff7d02769b42f0a41ab922334875bfc2766086c78b2caa581ba5f5b6da53cf`.
Do not uninstall the app to get around a certificate mismatch: that loses private
connections, drafts, read state and recovery records.

```bash
python3 tools/release.py init
python3 tools/release.py build
```

`init` is idempotent. It verifies the original preview certificate, snapshots its
key, creates a dedicated RSA-4096 release key, and constructs an Android signing
lineage. Secrets live in `~/.config/bellpull/signing/` (directory 0700, private
files 0600), outside Git. The password is randomly generated and is not printed.
Keep this whole directory, including the lineage and preview key, in a secure
offline backup before publishing. No backup to a second device has been made by
this repository's build scripts. Losing the release key prevents future updates.

The script zipaligns and signs `android/app/build/outputs/apk/release/Bellpull.apk`,
then verifies the signature and writes `SHA256SUMS`. Rotation targets API 28+, so
the lineage applies to all supported devices (Bellpull's minimum is API 29).
The old key also supplies compatibility signatures. See the official
[apksigner rotation contract](https://developer.android.com/tools/apksigner).
Future release builds must use this script and the same signing directory;
Gradle's optional `keystore.properties` signing alone does not attach the lineage.

Before publishing, install over the existing preview with `adb -s SERIAL install -r
Bellpull.apk`. Verify the same profile, drafts, encrypted settings and chat cache
remain accessible; restart the app and check run recovery. A debug APK cannot be
assumed to update a release-signed installation. Use the stable signing path for
subsequent upgrades. Keep a private data backup before a signing transition.

## Publication and rollback

After device acceptance and maintainer approval, tag the reviewed commit and
upload the signed APK with its SHA-256 and release notes. Never publish the
keystore, password, server configuration, bearer key, or private screenshots.
Publishing is a separate action; neither CI nor the release script performs it.

Keep the preceding APK and server image. Android normally rejects lower version
codes; prefer a fix with a higher code signed through the same lineage. Server
rollback is described in [the plugin migration guide](../plugins/bellpull/README.md).

## 0.5.4 local voice-output snapshot — not installed

Signed APK `build/releases/0.5.4/Bellpull.apk`, SHA-256
`8341e1830a4bc8225e9c7acbbeaed5af3adb93cbc9fa86555e722197ca4a5e22`.
Signer SHA-256 `c1211fd4dbb2b4c0710fe35858bbc4d19eb2d2d27abb18eb5ad51e8192db55bf`;
signature and 16-KiB zip alignment verified. Local test/build evidence is in
[VOICE-OUTPUT.md](VOICE-OUTPUT.md). The phone disconnected before installation,
so personal app remains 0.5.3/code 15. Fixture package/test APK and Qwen weights
remain to be cleaned up when ADB returns; never remove the personal app.
No push/release publication is authorized until user acceptance/green light.

## 0.5.5 local candidate — installed 2026-10-12

Version 0.5.5/code 17 adds Pocket CPU synthesis/model lifecycle and audio-route/
focus recovery. Debug and signed release builds compile ARM64 and x86_64. On the
user's October 12 request, Phase 5 commit `c5dceec` was signed and installed over
0.5.3 using the existing lineage. Signer, UID 10260 and both data-directory inodes
are preserved. Personal app left stopped; no server access. Fixture packages and
their test weights removed. No fresh functional tests, screenshots or benchmarks
were run; Pocket/recovery acceptance remains pending. Nothing pushed or published.

Signed APK `build/releases/0.5.5/Bellpull.apk`; SHA-256
`42c5d4ff4c943bdef7f36bcd064491b374602c9d5fa77143d54e4762a7c847cb`.
Signature and 16-KiB ZIP alignment verified; install/package evidence is under
`build/releases/0.5.5/personal-upgrade/`. The Phase 6 prototype is excluded.
See [VOICE-OUTPUT.md](VOICE-OUTPUT.md) for pending acceptance.
