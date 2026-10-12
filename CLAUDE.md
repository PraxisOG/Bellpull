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

**Current authority (2026-10-11, voice controls):** work on
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
  unused Google Material and WorkManager transitives excluded. This does not
  authorize Google LiteRT/Tensor libraries or additional speech runtimes.
  OkHttp was chosen over `HttpURLConnection` for the SSE path specifically because a
  long-lived stream needs clean cancellation and a bounded connect timeout with no read
  timeout; `HttpURLConnection.disconnect()` mid-read is not well defined.
- **Deploy only a reviewed result.** Prepare checks, backups and rollback first;
  live deployment and publication are separate from local implementation.

## Tone

Honest failure reporting over reassurance. If a slice is half-working, say which half. If
something in this file is wrong or has gone stale, say so rather than working around it.
