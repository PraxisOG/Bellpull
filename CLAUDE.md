# CLAUDE.md — Hermes Android

Single-APK Android client for a self-hosted Hermes Agent instance. Kotlin + Compose.
Target device: Pixel 10 Pro Fold, GrapheneOS (Android 17), bootloader relocked, no Google
Play Services.

> **Naming — read this before searching anything "Hermes."** NousResearch ships two
> different things under that name. **Hermes Agent**
> (`github.com/NousResearch/hermes-agent`, docs at `hermes-agent.nousresearch.com/docs`) is
> the self-hosted agent *software* this client is built against — API server, web dashboard,
> gateway, sessions, tools. **Hermes** (Hermes 2 / 3 / 4) is a separate, unrelated-in-use
> NousResearch *LLM family* — fine-tuned model weights served by an inference provider. This
> homelab's Hermes Agent instance routes inference to whatever model its config points at
> (seen so far: an Anthropic model that 404'd, then a DeepSeek model on Fireworks — not
> Hermes-the-model). A Hermes-the-model card, benchmark, or release note is never a source
> for this app's API contract. That contract is `docs/hermes-api.md`, full stop.

## Non-negotiables

**Current task authority (2026-10-23, voice speaker):** make Telecom voice mode
use the phone loudspeaker instead of its earpiece, preserving car/headset routes.
Branch `fix/voice-speaker-01410`, app 0.14.10/build42; plugin stays 0.6.2.
Test the real audio route in the isolated phone fixture, install the signed app
in place with personal settings/data intact, and provide a manual test checklist.
The user subsequently requested GitHub publication of this tested increment.

**Current task authority (2026-10-22, issue #27):** fix the named Moe route's
unsupported thinking controls first. Branch `fix/moe-reasoning-062`, plugin
0.6.2; Android stays 0.14.9/build41. Verify catalog, persistence, native provider
normalization, actual wire controls and recovery from an old invalid choice.
Continue authorized isolated Hermes/backend testing and experimental publication.
Preserve personal data and server defaults; do not restart/reconfigure vLLM.
The user will install the fixed plugin on Moe and explicitly requested GitHub
publication. No personal-server deployment is part of this increment.

**Current task authority (2026-10-22, crash repair):** investigate and fix the
reported app crashes. Phone exit history shows 0.14.8 ANRs and a low-memory kill;
both ANR stacks point to replay deduplication's retained regex matchers. Branch
`fix/live-history-memory-0149`, Android 0.14.9/build41; plugin stays 0.6.1.
Replace the allocation-heavy prefix scan, verify long replay on the real Android
runtime in the isolated fixture package, then install in place and publish the
experimental repair under existing authorization. Preserve all personal data.

**Current task authority (2026-10-22, thinking menu):** add a direct Thinking
level submenu to the conversation's top-right overflow. Choosing an option
closes both menus and saves that conversation's reasoning choice. Branch
`feature/thinking-menu-0148`, Android 0.14.8/build40; plugin stays 0.6.1.
Continue existing signed in-place phone install and experimental publication
authorization. Preserve personal app data, server profile and model choices.

**Current task authority (2026-10-22, vLLM reasoning):** investigate Moe's running
Qwen backend and wire Bellpull's reasoning choice through the plugin and Hermes.
The user explicitly authorized SSH/API investigation and test inference on Moe.
Branch `feature/vllm-reasoning-061`, Android 0.14.7/build39, plugin 0.6.1.
Continue existing signed in-place phone installation, local PowerMac plugin
deployment and experimental publication authorization. Preserve personal app data
and the PowerMac server profile. A selectable Moe provider may be added to local
Hermes without changing its default model. Do not restart/reconfigure Moe's
inference service. See `docs/evidence/vllm-reasoning-20261022/README.md`.

**Current task authority (2026-10-22, sentence streaming):** make Kokoro generate
sentences to mimic streaming. Branch `fix/kokoro-sentence-streaming-0146`, Android
0.14.6/build38; plugin stays 0.6.0. Share sentence boundaries between live voice
and Read aloud, prepare one sentence ahead, preserve bounded queues and immediate
Stop. Continue existing isolated phone testing, signed in-place installation and
experimental publication authorization. Keep personal settings/models/data.

**Current task authority (2026-10-22):** fix voice-mode exit, move OpenCode activity
into the conversation menu, add adjustable image thumbnails, repair reconnect
response duplication, and implement Kokoro as the small on-device TTS model.
This increment uses `fix/voice-media-reconnect-0145`, Android 0.14.5/build37.
Plugin remains 0.6.0. Phone fixture tests, in-place signed installation and
experimental publication continue existing authorization. Preserve personal app
settings/data, Moonshine input and the PowerMac connection; leave Moe untouched.
Kokoro uses pinned Apache-2.0 Misaki dictionaries/neural G2P and the existing ORT
CPU runtime. No eSpeak/Piper or all-model Sherpa AAR is introduced.
See `docs/evidence/voice-media-20261022/README.md` for acceptance and limits.

**Current task authority (2026-10-20):** the user requested stronger persistent car
audio, potentially Android Telecom, CPU STT measurements and streaming audio to
the server. They then selected FP32 because it was faster. This increment uses
`feature/car-telecom-streaming-speech`, Android 0.14.4/build36, plugin 0.6.0.
AndroidX Core Telecom 1.0.0 implements the requested platform integration.
The local PowerMac Hermes and CPU helper are authorized test/deployment targets;
Moe is unchanged. Phone installation/testing and experimental publication continue
the existing authorization. Keep personal app data/signing, use disposable fixture
packages for instrumentation, and never commit private credentials or weights.
See `docs/evidence/car-streaming-20261020/README.md` for actual acceptance and limits.

- **No launcher shell.** This is a normal app. No home-screen-replacement code.
- **No silent model substitution.** If the primary inference path is unavailable, fail with
  a scoped, visible error. Never quietly downgrade to a local or smaller model.
- **No Firebase, no Play Services, no Google libraries.** Push is ntfy. The device has zero
  Google surface.
- **Never invent a Hermes endpoint, field, or auth scheme.** `docs/hermes-api.md` is the
  contract. If it doesn't cover something, stop and ask. A guessed endpoint that compiles is
  worse than a blocker.
- **The API bearer key is root-equivalent on the Hermes host** — the API server exposes the
  full toolset including terminal commands. It lives in EncryptedSharedPreferences. Never in
  a build file, never in a committed env file, never in a log line, never in a test fixture.
- **Hermes is the source of truth.** The phone overwrites local state from Hermes on sync.
  No bidirectional merge. (Offline queue is Tier 3 — not now.)
- **Importance is a typed data field**, never styling embedded in a payload. The client
  decides how to render it and enforces its own budget. Agent output cannot override this.
- **Only render genuine server approval requests.** Gate 0c still applies to legacy
  session streams and default unattended API requests. Bellpull Phase 2 explicitly opts
  durable runs into the verified `run_interactive_approvals` contract described in
  `docs/hermes-api.md`. Allow once/Deny bind to the current exact request ID; no
  fabricated prompts, blanket approval, or global policy changes.
- **Borrow, don't build.** Check `docs/references.md` before writing a subsystem. Pull the
  specific piece, not the feature set.

### Tier 2 non-negotiables

Settled in `ROADMAP-TIER2.md`. They exist because Tier 1 put a key to the homelab on the
phone, and Tier 2 puts a key to messages and contacts on the homelab — a new direction of
trust, not a second use of the old one.

- **The phone is the sole origin point for any write or outbound action** touching personal
  data or communications. The server reads and reasons; it never mutates personal data and
  never contacts anyone unilaterally.
- **A separate token from the Tier 1 API key**, independently revocable. Never reuse the
  `API_SERVER_KEY` for the phone-bridge direction.
- **A capability allowlist on the phone.** The bridge answers a request because it is on the
  list, not because the server asked.
- **An on-device audit log** of every request the server made, visible in the app, plus a
  **kill switch** that stops answering without uninstalling anything.
- **SMS sending is structurally incapable of firing without a tap** — not defaulted off, not
  gated by a flag. There is no auto-send path and none gets added without an explicit
  decision.
- **Contacts are read-only** in the first pass, and ambiguous lookups return every match for
  the model to disambiguate rather than silently picking the first row.

## Scope gate

**Latest authority (2026-10-17, Phase 13):** the user requested publishing the
latest experimental build first, then implementing a working Phase 13 prototype,
using `/home/praxisog/Imagination-gpu-experiment` as a reference. 0.12.0/build30
is published as `android-v0.12.0-experimental.1` from `4741b4b`. Work now lives on
`feature/phase13-local-llm-0130`, building 0.13.0/build31. The supplied pinned
llama.cpp/Vulkan/KleidiAI implementation is in scope for this request. Keep explicit
model/backend selection and correctness gates. Existing phone testing/install
authorization persists; preserve signing and personal data. The local Docker
Hermes is authorized for tests, with no personal remote server/config deployment.
See `docs/PHASE-13-LOCAL-LLM.md`. No default-branch merge is part of this task.

**Current authority (2026-10-15, consolidation):** the user asked to make the
latest version the default and keep GitHub clean. Consolidate app/plugin development
onto `master`, publish one matching current development download, update entry-point
documentation and remove remote branches only after their tips are reachable from
`master`. Preserve released tags/assets and record the old branch tips. Earlier
no-default-merge instructions below are historical and superseded for this work.
The current packaged pair is app 0.8.4/build25 and plugin 0.4.2. Future development
starts from `master`; completed feature branches are temporary. Personal-server
access remains out of scope. Phone installation authorization persists; preserve
signing, app data and the owner's selected Pocket/Loona voice.


**Latest authority (2026-10-14, experimental release):** the user requested verifying
the installed app is 0.8.3 and publishing app 0.8.3 plus plugin 0.4.1 as experimental
on GitHub. ADB confirms build22; its APK matches the previously signed artifact.
Publish `android-v0.8.3-integration-preview.1` with that APK, the full plugin,
complete source, roadmap, checksums and known audit findings. Update release-facing
documentation/download links. Preserve the phone's current state and personal
server boundary. No experimental runtime merge into the default branch is requested.
See `docs/RELEASE-0.8.3-EXPERIMENTAL.md` for release evidence.

**Latest authority (2026-10-14, project audit):** the user requested a full project
audit and uploading the audit to GitHub. Audit revision `4fc426f` and publish the
report plus synthetic reproductions on `repair/experimental-open-issues`, alongside
the already pushed app/plugin source. See `docs/AUDIT-2026-10-14.md`. This audit
does not implement its findings, deploy the personal server, merge the default
branch, publish a release or change the installed phone app.

**Latest authority (2026-10-14, GitHub push):** the user requested pushing the
completed work to GitHub. Push `repair/experimental-open-issues`, including all
issue repairs and the compact voice prompt, to `PraxisOG/Bellpull`. This supersedes
earlier no-push instructions for this branch. No default-branch merge, release
publication or personal-server access is requested.

**Latest authority (2026-10-14, compact voice prompt):** the user requested hiding
the Bellpull voice preset behind a tappable disclosure while retaining a visible
voice-enabled indicator. Existing ADB/install authorization carries forward.
Build22/0.8.3-integration-preview is installed over build21 with unchanged signer,
UID 10260 and CE/DE identities 117766/116002; personal app left stopped. Saved and
pending user messages start collapsed, with original submitted/stored/action
content preserved. No personal-server access, plugin change, push or publication.
See `docs/VOICE-PROMPT-DISCLOSURE.md` for checks and artifact evidence.

**Latest authority (2026-10-13, all open experimental issues and phone install):**
the user requested fixing all open GitHub issues, confirmed ADB availability,
and requested installation when done. Work is on `repair/experimental-open-issues`
from integration `4fa43d1`. Android 0.8.2/build21 and plugin 0.4.1 implement the
repairs; see `docs/EXPERIMENTAL-ISSUE-REPAIRS.md` for evidence/remaining car checks.
Signed build21 is installed over build20, with the same certificate, UID10260 and
CE/DE inodes117766/116002. Prior APK backed up; personal app left stopped, no
personal-server access/deployment. Source remains local; default branch unchanged.


**Latest authority (2026-10-13, development deployment and GitHub):** the user
requested updating the local development server, pushing the latest development
release for their agent, including the phase roadmap, and improving the GitHub
front page. Back up/migrate the local Docker playground to one Bellpull plugin;
use a supported stock Hermes image, preserve data/configuration and verify idle
before restart. Push the integration branch and publish a clearly labeled
prerelease with matching APK/plugin/instructions. A documentation-only front-page
update is authorized; do not merge preview app code into master. No personal
server access or personal plugin deployment. Earlier no-push/publication rules
below are superseded only for this requested development release.
The first prerelease is published and verified; the default page is updated with
only documentation/gallery/license changes. Dev gateway/dashboard migration to
stock Hermes0.21.5/plugin0.4.0 passed with backups, unchanged provider/model settings,
58 sessions/562 messages retained and three byte-identical downloads. No personal
server access or inference. The user additionally requested all build source on
GitHub: a fresh recursive release clone built successfully, and the source follow-ups add a complete
source archive/build guide plus verified test/CI portability fixes. Production APK
and runtime behavior are unchanged; no further phone install is needed.

**Latest authority (2026-10-13, personal app update):** after combined fixture
acceptance, the user explicitly requested the latest app on their phone to try.
Install the signed 0.8.1-integration-preview/build20 APK in place, preserving the
existing signer, UID and app data. Keep a local copy of the installed 0.5.5 APK.
Do not launch the personal app/contact its server; leave opening it to the user.
No personal plugin deployment, push or publication is authorized.
Completed: signed build20 installed with matching certificate, UID10260 and
CE/DE inodes 117766/116002 preserved. Previous installed APK backed up; the app was
left stopped for the user to open. The personal app is now 0.8.1-integration-preview.

**Latest authority (2026-10-13, combined ADB acceptance):** after the requested
integration merge and ADB question, the user replied “send it.” Resume the combined
fixture phone checks on `integration/phase6-8-9`. Use disposable loopback Hermes and
the separate fixture package, preserve personal app/accounts/data, and keep the
personal Hermes server prohibition. No push, default-branch merge or publication.
The earlier wait-for-ADB instruction below is now satisfied.
Acceptance completed: 11 final device-test executions passed across both widths
and the actual permission flow. The initial diff-close navigation failure was in
the test harness; its wait is fixed. No product/APK change. Fixture packages,
reverse ports and temporary size override are removed; original density preserved.
At fixture cleanup the personal app was stopped at 0.5.5/code17/UID10260; the
subsequently authorized update is recorded above. See INTEGRATION-6-8-9.md.


**Latest authority (2026-10-12, integration):** the user explicitly requested merging
Phase 6 and Phases 8/9, then asking for ADB and testing when available. Work on
`integration/phase6-8-9`, preserving both original feature branches. Combine app and
plugin features, run local validation, prepare the signed preview, then ask for ADB.
Do not use ADB until it is returned for this combined acceptance. No personal-server
access, push, default-branch merge or publication. Earlier no-merge/no-ADB statements
below are historical and superseded only within this requested integration scope.


**Latest authority (2026-10-12, ADB returned):** the user requested isolated phone
testing of Phase 6 first, then Phases 8/9 after clean Phase 6 acceptance. Fixture
installs/tests and necessary fixes are authorized. Keep personal app data and
server untouched; no push, merge or publication. Phase 6 passed both widths;
see docs/PHASE-6-OPENCODE.md. Earlier no-ADB instructions below are historical.


**Latest authority (2026-10-12):** after fixing the 0.5.5 launch crash, continue
both independent branches from corrected 0.5.5: Phase 6 OpenCode activity here,
and combined Phase 8/9 reminders/routines/visual phone calendar at
`/home/praxisog/Bellpull-phase8-9`. Use local tests and disposable hosts. The user
is away with the phone; no ADB, personal-server access, push, merge or publication.
The hotfix was installed and fixture-tested before departure; see HANDOFF.


**Current authority (2026-10-12, Phase 6 implementation):** the user approved
building out the proposed Hermes/OpenCode activity view while away, with phone
acceptance on return. Work on `feature/phase6-opencode-activity`: portable plugin
observer/journal, authenticated discovery and read-only activity/workspace APIs,
Android timeline/recovery and read-only diffs. Define Bellpull-owned contracts in
`docs/hermes-api.md`; never guess upstream contracts. Validate using disposable
local fixtures/hosts and local Android tests, no personal server or phone access.
No new runner, arbitrary terminal, blanket permission, push, merge or publication.
The user reports backend caching is fixed: Phase 7 is now Hermes prompt
optimization/latency verification, with no Quick Chat app feature planned.

**October 12 installation update:** the user requested installation of 0.5.5.
Signed Phase 5 commit `c5dceec` was installed in place (code 17), preserving signer,
UID 10260 and both data-directory inodes. Personal app left stopped; no server
access. Fixture app/test package and weights removed. Release build, signature,
16-KiB ZIP alignment and installed package checks passed; no new functional tests.
Pocket/recovery remain awaiting acceptance. Phase 6 prototype excluded. No push,
merge or release publication. See `docs/HANDOFF.md` for evidence.

**Latest authority (2026-10-12, ADB returned):** test Phase 6 first, then Phases 8/9
when its isolated acceptance passes. Both now pass fixture phone checks at unfolded
and simulated narrow widths. Use only the separate test app and disposable local
hosts; personal app data/server remain untouched. No push, merge or publication.
See docs/PHASE-8-9-REMINDERS-CALENDAR.md for actual evidence and remaining limits.
Earlier no-ADB instructions below are historical.


**Latest authority (2026-10-12):** build two independent branches from corrected
0.5.5 while the user is away with their phone. This worktree is
`feature/phase8-9-reminders-calendar`: persistent reminders, native Hermes routines,
visual day/week calendar blocks and opt-in Android CalendarContract management.
The independent Phase 6 branch remains at `/home/praxisog/Hermes-App-Project`.
Use local tests/disposable hosts. No personal-server access, ADB, push, merge or
publication. No new dependencies. Define Bellpull-owned contracts in hermes-api.md.
No automatic phone bridge/calendar writes: proposals require an explicit phone
Apply action in this prototype. Alerts/offline delivery are Phase 10. Phase 7 now
means Hermes prompt optimization/latency verification, not a new Quick Chat app.
The 0.5.5 launch hotfix was installed and fixture-tested before departure.


**Current authority (2026-10-11, local voice output and Phase 6 prelim):** finish
Phase 5 implementation on `feature/local-voice-models`, **without further tests or
phone access**, then create a separate branch for Phase 6 preliminary OpenCode
monitoring through Hermes. Phase 5 is committed at `c5dceec`; current Phase 6
branch is `feature/phase6-opencode-activity`. Its scope/evidence is
`docs/PHASE-6-OPENCODE.md`: source audit and unconnected typed CLI timeline only,
not a live integration. No new Hermes API, plugin deployment or OpenCode run.
No personal server access. No push until the user's
green light; no merge or release publication. The user approved qwentts.cpp/GGML
and Sherpa-ONNX 1.13.8 with ONNX Runtime for local output. No Google accelerator
libraries are approved. Keep the previous voice-turn-taking behavior and provider
thinking controls unchanged. Voice input and Voice output are separate categories.
Android speech remains the default; never silently substitute engines.

The tested 0.5.4/code 16 Qwen/Android snapshot passed 519 local tests (one live
smoke skip), 161 screenshot baselines, builds/lint and two isolated native phone
checks. Qwen CPU is measured at roughly 7–8 processing seconds per audio second.
**The phone disconnected before personal installation: the personal app remains
0.5.3/code 15**, stopped. The fixture app/test package and Qwen test weights remain
pending cleanup when ADB returns. See `docs/VOICE-OUTPUT.md` for exact evidence.
The subsequent 0.5.5/code 17 implementation compiles ARM64/x86_64 but has not
run tests/screenshots/device checks, at the user's request. It adds Pocket-only
Sherpa/ORT, model switching and explicit audio-route/focus recovery. Do not reuse
0.5.4 test results as evidence for 0.5.5.

**Previous authority (2026-10-11, voice controls):** work on
`feature/voice-turn-taking` from 0.5.2 (`3c8acf7`). Default microphone tap starts/ends
conversation mode with a clear pulsing indicator; holding dictates into the composer
for review. Keep the previous panel behind Settings → Voice → Debugging voice
interface; add Using voice and a brief readiness chime. Voice prompts ask for one
paragraph/180 words, minimal reasoning and specific tool/search purposes. The user
explicitly said **do not change thinking controls**, only ask for less thinking.
Announce genuine live activity, never raw reasoning/tool arguments; observed status
does not establish playback before tool execution. Confirm non-echo speech before
using the existing chat Stop behavior; save one replacement turn until Stop settles,
with visible recovery on failure/timeout. Keep selected ASR model/downloads unchanged.
No new dependencies or plugin changes. Prior install/test/push authority persists;
test only the fixture sandbox, preserve personal signing/data and do not access the
personal Hermes server. No merge or release publication.

0.5.3/code 15 passed 501 local tests (one opt-in live-server skip), 158 screenshot
baselines, build/lint and five isolated phone checks. These cover tap/hold/release/
cancel through active recomposition, missing-model protection, About, native
chime/TTS/microphone cleanup and background-service controls. The real store/run
journal regression verifies one Stop and one replacement admission. Signed install
preserved the personal UID/data directories; the personal app was left stopped.
Model selection/downloads were not changed. Listening/echo, personal-provider
compliance and per-tool playback ordering remain user acceptance; see
`docs/PHASE-5-VOICE.md` and `docs/release.md` for evidence and limits.

**Previous authority (2026-10-11, local speech experiments):** work on
`feature/local-speech-lab`; see `docs/LOCAL-SPEECH-LAB.md`. The addendum prioritizes
Moonshine Tiny, Small and Medium Streaming, Settings organization, downloads/switching
and direct-input CPU benchmarks before additional models or acceleration ports.
Display is a separate category; experiments belong under Voice → Experimental.
The user approved Moonshine CPU SDK 0.1.5 and resumed local integration after the
SDK-only pause. **The user has now returned ADB for isolated tests and benchmarks.**
Make local recognition and optional private-server recognition explicit choices;
local mode must not ask for a URL or service key or silently use a remote service.
Use the separate fixture app and preserve
personal signing/data when phone testing resumes; do not access the personal Hermes
server. A separate pending request covers
an optional exception for standalone Google LiteRT/Tensor libraries. Do not treat
Parakeet TDT's Pixel TPU route as proof of Realtime EOU compatibility. Report actual
backend, source-checked inputs, timing annotation limits and measured/untested/blocked
results separately. No merge or release publication.

The current 0.5.2/code 14 patch disables Start voice for missing/incomplete local
models and during model operations. Actual file availability is rechecked before
permissions, service start and recognizer construction. Regression validation passed
481 local tests (one live-server skip), 156 screenshots, build/lint and two isolated
phone checks (all three blocked voice modes and About). The signed update preserved
personal UID/data and was left stopped; fixture apps were removed. No personal server
access or plugin update. Signing/rollback details are in `docs/release.md`.

The previous CPU delivery was verified and installed as 0.5.1/code 13. See the measured
report under `benchmarks/speech/pixel-10-pro-fold-2026-10-11/`: 206 model benchmark
rows, all three native suites passed, and eight distinct device checks passed
after fixing the UI harness's stale accessibility cache/scroll handling. Local
validation: 477 passed, one live-server skip, 156 screenshot baselines, build/lint.
Personal signing/UID/data directories were preserved; personal app left stopped,
fixture apps/weights removed and original display settings restored. Human acoustic,
car/headset, screen-lock and personal-server acceptance remain open. Additional
models/accelerators remain earmarked, not implemented.

Read `BELLPULL-FILE-SHARE-MANUAL.md` for the sharing workflow. The user requested
recommendations before starting file-sharing changes; discussion alone does not
authorize that separate plugin update.

**Previous authority (2026-10-11, Phase 5):** the user authorized conversational
voice implementation and the compact drawer/filter changes, with ADB testing while
they are unavailable. Use `feature/phase5-voice` and `docs/PHASE-5-VOICE.md`.
Do not access the personal Hermes server or deploy a speech provider. Native
installed offline TTS and an explicitly configured private transcription service
require no added dependency. Keep Phase 5's unverified acoustic/car/screen-lock
acceptance and per-tool announcement ordering limits explicit. Preserve existing
signing/app data and use only the separate fixture app for server/device tests.
Earlier install/test/push authorization persists; no merge or release publication.

**Previous authority (2026-10-11, Phase 4):** the 0.3.7 audit repair is complete. The user
authorized Phase 4 on a new branch from 0.3.7, narrowed to conversation projects.
`docs/PHASE-4-PROJECTS.md` supersedes the old broad Phase 4. Work through local
implementation and validation. The user subsequently returned ADB access and
authorized installation, device testing, necessary tweaks and pushing this branch.
Use the isolated fixture app for server/UI tests and the existing signing lineage
for the personal app update. The personal Hermes server remains off limits.
No merge or release publication is requested.
The user also requested Settings → About with the actual installed app version
and each saved server's plugin version/compatibility status. These are included
in Phase 4; latest-release polling is not implied.

**Previous authority (2026-10-10):** the user authorized execution of
`Bellpull-Repair-and-Plugin-Migration-Changelist.txt` on
`repair/bellpull-plugin-migration`. Finish that repair list before expanding Phase 4.
`Bellpull-Implementation-Prompt.txt` remains the background Phases 0–7 plan.
Significant offline work, saved-answer/project expansion, and importing/merging
provider history into Hermes are deferred. The old Tier roadmaps below are historical.

**Tier 1 is code-complete** (see `ROADMAP.md`). **Tier 2 is planned and startable** — see
`ROADMAP-TIER2.md`, which has its own gate, slices, and a set of decisions marked *do not
relitigate*. Tier 3 (on-device llama.cpp agent loop) remains out of scope.

Two rules about the order of work:

- **Close the Tier 1 carry-over first.** Tier 1 is code-complete, not demo-complete. The
  on-glass checklist in `docs/HANDOFF.md` and the carry-over list at the top of
  `ROADMAP-TIER2.md` come before new Tier 2 code. Don't build a tier on top of an unverified
  one.
- **Gate T2-0 goes before every other Tier 2 slice.** It is the only part of that tier that
  can turn out to be impossible, and compressing it to reach the content providers sooner is
  the specific mistake the roadmap warns about.

## Platform constraints specific to this build

These are environment facts, not preferences. Violating them produces silent failures.

- **`ACCESS_LOCAL_NETWORK` (Android 17 / API 37) — decided, not needed.** Gate 0 tested the
  app on the real Fold against `https://powermacg3.tail0b3e3a.ts.net` (Tailscale Serve,
  tailnet-only, proxying to the host's loopback Hermes on `127.0.0.1:8642`) with no
  `ACCESS_LOCAL_NETWORK` permission declared. The phone authenticated and reported session
  streaming available. All traffic to Hermes goes over Tailscale (100.64.0.0/10, through the
  phone's Tailscale VPN interface) — CGNAT space, not a private LAN range — so Android's
  local-network enforcement doesn't apply. Do not add this permission. If plain-LAN access is
  ever needed later, this decision must be revisited.
- **targetSdk 37 removes the large-screen orientation opt-out.** Adaptive layout on a
  foldable is mandatory. Every screen has folded and unfolded screenshot configs and both
  stay green.
- **App enumeration uses `<queries>` with an `ACTION_MAIN` + `CATEGORY_LAUNCHER` intent.**
  Never `QUERY_ALL_PACKAGES`.
- **GrapheneOS blocks USB data while the screen is locked** (Exploit protection → USB-C
  port → "Charging-only when locked"). Use wireless debugging for install/test loops.
- **Foldable:** check every screen at cover and unfolded widths.

## Repo layout

What exists today (as of 2026-09-16):

```
android/            # Gradle project, single APK
  app/              # Compose UI, HermesStore, offline cache, widget, search.
  core-hermes/      # API client: auth, sessions, messages, SSE. Pure Kotlin JVM, no Android.
  core-model/       # Pure Kotlin data types + typed errors, shared by both.
mock-hermes/        # Replay server fed by recorded fixtures. Started by core-hermes' tests.
docs/
  hermes-api.md     # THE CONTRACT. Authoritative.
  references.md     # What to borrow from where
  HANDOFF.md        # Running status log
ROADMAP.md
```

`app/` uses `core-hermes` through `HermesStore`. The connection screen still has its older
direct `/v1/capabilities` probe; do not mistake that one path for the chat architecture.

**There is no `infra/` directory and no Docker Compose in this repo.** An earlier version of
this file described one; it was never built. Hermes runs as a rootless Docker container
managed outside this repo, with config at `~/hermes-data/` on the host — see
`docs/hermes-api.md` § Setup on the Hermes host. The old "don't touch `infra/prod/`" rule
guarded nothing and is gone; the real rule is **don't edit `~/hermes-data/config.yaml`
without saying so first**, because that's the live instance.

**The default branch is `master`, not `main`.**

**The live server is managed outside this repo.** The user's personal Hermes is
a stock non-Docker install on another box. **Do not access that server**: the user
will install the APK and plugin manually and report results. ADB checks use the
separate `org.praxisog.hermes.fixture` application with loopback fixtures; leave
the personal app stopped during testing. The earlier local Docker gateway is a
playground and must not be mistaken for the personal server.

The current migration target is one plugin in `plugins/bellpull/`. Portable file
sharing and GPU telemetry require no core patch. `server-host/` is retained as an
optional legacy compatibility fixture for run input/model/approval behavior.
Read `plugins/bellpull/README.md` for installation, migration and
rollback. `server-patches/` is the preserved legacy chain, still the production
rollback until parity/device acceptance. Hermes plugins go in `~/hermes-data/plugins/`
on the host (`/opt/data/plugins/` in the container) — **not** `~/.hermes/plugins/`, which does
not exist here. Four root-owned `hermes-launcher-*` directories sit there, but they were
verified empty on 2026-09-16; use Hermes' bundled plugins as format examples. The gateway
runs as uid 1000. `ntfy` (`127.0.0.1:2586`) and `searxng` are externally managed
services; Docker containers for them were observed on 2026-10-10. There is no
Compose definition for them in this repo.

## Commands

```bash
# Unit + contract tests (JVM, fast — run these constantly). core-hermes' contract tests
# start mock-hermes themselves; nothing manual is needed.
cd android && ./gradlew test

# Live smoke test against a real Hermes. Opt-in: skipped unless both are set, so the normal
# suite stays hermetic. Costs one real inference call.
cd android && HERMES_SMOKE_URL=http://127.0.0.1:8642 \
  HERMES_SMOKE_KEY_FILE=~/.config/hermes-android/dev-api-key \
  ./gradlew :core-hermes:test --tests '*HermesLiveSmokeTest*'

# Compose screenshot tests (JVM render, no emulator)
cd android && ./gradlew verifyRoborazziDebug
cd android && ./gradlew recordRoborazziDebug   # after an intentional UI change

# Instrumentation on the Gradle Managed Device (AOSP ATD image, headless, API 34)
cd android && ./gradlew aospAtdApi34DebugAndroidTest   # 0 tests today — no androidTest source set yet

# Mock Hermes (fixture replay server)
cd mock-hermes && ./run.sh   # http://127.0.0.1:8643, override with MOCK_HERMES_PORT
# It now requires a bearer key on EVERY route -- a bare curl gets 401. Set MOCK_HERMES_KEY
# and send it. MOCK_HERMES_SSE_DELAY makes the stream incremental; the X-Mock-Fail header
# injects auth / rate-limit / stream-abort / slow-headers per request (mock-only, not a
# Hermes feature). See mock-hermes/server.py's docstring.

# Install to the real phone (wireless debugging — see platform constraints)
cd android && ./gradlew installDebug
```

After upgrading to the dedicated release certificate, use `tools/release.py` and
`docs/release.md` for installs. An ordinary debug APK cannot be assumed to update
that installation; preserve the signing lineage and app data.

## Working agreement

- **Plan significant work.** The current repair changelist is already authorized
  for execution; do not ask again before its routine implementation/tests.
- **One slice per branch**, small commits. Most of this is vibe-coded; cheap rollback is the
  safety net. Never bundle unrelated changes into one commit.
- **Every UI change ends with a screenshot test run.** Show the diff images.
- **Don't add a dependency without asking.** Especially not a Google/Play one. Approved so
  far, and the whole list: `kotlinx-serialization-json` and `okhttp` (core-hermes/core-model),
  Compose + `androidx.security:security-crypto` (app), Robolectric + Roborazzi (tests),
  and user-approved CommonMark Java 0.30.1 with its table extension (2026-10-10).
  Moonshine Voice Android CPU SDK 0.1.5 is also user-approved (2026-10-11), with
  unused Google Material and WorkManager transitives excluded. Qwen's pinned CPU runtime/GGML and Sherpa-ONNX 1.13.8 with bundled ONNX
  Runtime (including its existing JSON dependency) are also user-approved for
  voice output. This does not authorize Google LiteRT/Tensor libraries.
  OkHttp was chosen over `HttpURLConnection` for the SSE path specifically because a
  long-lived stream needs clean cancellation and a bounded connect timeout with no read
  timeout; `HttpURLConnection.disconnect()` mid-read is not well defined.
- **Deploy only a reviewed result.** Prepare checks, backups and rollback first;
  live deployment and publication are separate from local implementation.

## Tone

Honest failure reporting over reassurance. If a slice is half-working, say which half. If
something in this file is wrong or has gone stale, say so rather than working around it.
