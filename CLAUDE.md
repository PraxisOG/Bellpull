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
- **No approval prompt. Settled by Gate 0c, don't rebuild it.** The `api_server` platform is
  excluded from approval contexts in Hermes' own source, so this client will never receive a
  pending approval. Flagged commands are either auto-approved by smart approval or denied
  outright with `status: "blocked"`. Render `blocked` as its own honest state; never as a
  generic tool failure. Details and source citations in `docs/hermes-api.md` § Gate 0c.
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
  app/              # Connection screen + probe. Compose. Still HttpURLConnection-based.
  core-hermes/      # API client: auth, sessions, messages, SSE. Pure Kotlin JVM, no Android.
  core-model/       # Pure Kotlin data types + typed errors, shared by both.
mock-hermes/        # Replay server fed by recorded fixtures. Started by core-hermes' tests.
docs/
  hermes-api.md     # THE CONTRACT. Authoritative.
  references.md     # What to borrow from where
  HANDOFF.md        # Running status log
ROADMAP.md
```

`app/` does **not** use `core-hermes` yet — its connection screen predates the module and
still talks to `/v1/capabilities` directly. Migrating it is Slice 3's first job, not a
leftover to work around.

**There is no `infra/` directory and no Docker Compose in this repo.** An earlier version of
this file described one; it was never built. Hermes runs as a rootless Docker container
managed outside this repo, with config at `~/hermes-data/` on the host — see
`docs/hermes-api.md` § Setup on the Hermes host. The old "don't touch `infra/prod/`" rule
guarded nothing and is gone; the real rule is **don't edit `~/hermes-data/config.yaml`
without saying so first**, because that's the live instance.

**The default branch is `master`, not `main`.**

**Server-side work lives outside this repo.** Hermes plugins go in `~/hermes-data/plugins/`
on the host (`/opt/data/plugins/` in the container) — **not** `~/.hermes/plugins/`, which does
not exist here. Four `hermes-launcher-*` plugins already sit there from an earlier effort;
read them before writing a new `plugin.yaml`, and note they are root-owned while the gateway
runs as uid 1000. `ntfy` (`127.0.0.1:2586`) and `searxng` also run as host processes, not from
any Compose file in this repo.

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

## Working agreement

- **Plan before code.** Anything larger than a single file: produce a plan and wait.
- **One slice per branch**, small commits. Most of this is vibe-coded; cheap rollback is the
  safety net. Never bundle unrelated changes into one commit.
- **Every UI change ends with a screenshot test run.** Show the diff images.
- **Don't add a dependency without asking.** Especially not a Google/Play one. Approved so
  far, and the whole list: `kotlinx-serialization-json` and `okhttp` (core-hermes/core-model),
  Compose + `androidx.security:security-crypto` (app), Robolectric + Roborazzi (tests).
  OkHttp was chosen over `HttpURLConnection` for the SSE path specifically because a
  long-lived stream needs clean cancellation and a bounded connect timeout with no read
  timeout; `HttpURLConnection.disconnect()` mid-read is not well defined.
- **Don't touch `infra/prod/`.** Promotion is a manual env-file swap.

## Tone

Honest failure reporting over reassurance. If a slice is half-working, say which half. If
something in this file is wrong or has gone stale, say so rather than working around it.
