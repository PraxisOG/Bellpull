# CLAUDE.md — Hermes Android

Single-APK Android client for a self-hosted Hermes Agent instance. Kotlin + Compose.
Target device: Pixel 10 Pro Fold, GrapheneOS (Android 17), bootloader relocked, no Google
Play Services.

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
- **Borrow, don't build.** Check `docs/references.md` before writing a subsystem. Pull the
  specific piece, not the feature set.

## Scope gate

Only Tier 1 is in scope (see `ROADMAP.md`). Tier 2 (calendar/contacts/SMS content providers,
notification listener, accessibility service) and Tier 3 (on-device llama.cpp agent loop) are
**out of scope until after 2026-09-28**. If a task seems to need Tier 2/3 work, say so
instead of building it.

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

```
android/            # Gradle project, single APK
  app/              # UI, widget, search, navigation
  core-hermes/      # API client: auth, sessions, messages, SSE. No Android UI deps.
  core-model/       # Pure Kotlin data types shared by both
infra/              # Docker Compose: hermes, searxng, ntfy. dev/ and prod/ env files.
mock-hermes/        # Replay server fed by recorded fixtures. Used by all tests.
docs/
  hermes-api.md     # THE CONTRACT. Authoritative.
  references.md     # What to borrow from where
ROADMAP.md
```

## Commands

```bash
# Unit + contract tests (JVM, fast — run these constantly)
cd android && ./gradlew test

# Compose screenshot tests (JVM render, no emulator)
cd android && ./gradlew verifyRoborazziDebug
cd android && ./gradlew recordRoborazziDebug   # after an intentional UI change

# Instrumentation on the Gradle Managed Device (AOSP image, headless)
cd android && ./gradlew aospAtdApiNNDebugAndroidTest

# Mock Hermes (tests start it automatically; run manually to poke at it)
cd mock-hermes && ./run.sh

# Install to the real phone (wireless debugging — see platform constraints)
cd android && ./gradlew installDebug
```

## Working agreement

- **Plan before code.** Anything larger than a single file: produce a plan and wait.
- **One slice per branch**, small commits. Most of this is vibe-coded; cheap rollback is the
  safety net. Never bundle unrelated changes into one commit.
- **Every UI change ends with a screenshot test run.** Show the diff images.
- **Don't add a dependency without asking.** Especially not a Google/Play one.
- **Don't touch `infra/prod/`.** Promotion is a manual env-file swap.

## Tone

Honest failure reporting over reassurance. If a slice is half-working, say which half. If
something in this file is wrong or has gone stale, say so rather than working around it.
