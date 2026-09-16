# Hermes Android handoff — 2026-09-16

- Repo instructions: read `CLAUDE.md`, `ROADMAP.md`, and `docs/hermes-api.md`. Only Tier 1 is in scope. Do not print or commit the Hermes API key.
- Gate 0 API verification is done. Sanitized live fixtures are in `mock-hermes/fixtures/` on commit `dd1ff33`; Hermes state was recovered and the dev server works.
- Minimal Kotlin/Compose app is committed on `slice1-minimal-app` as `c1a0153`. It stores the key in EncryptedSharedPreferences and checks `GET /v1/capabilities`. Folded and unfolded Roborazzi screenshots pass; `:app:assembleDebug` passes.
- The app is installed on the Pixel 10 Pro Fold (Android 17/API 37). Wireless ADB is paired.
- **Gate 0b is done.** Tailscale is installed on the host as a local user install (no sudo,
  no system package) at `~/.local/opt/tailscale_1.102.4_amd64`, `tailscaled` runs with
  `--tun=userspace-networking` and an isolated statedir at `~/.config/tailscale-hermes`. Host
  is on the tailnet as `powermacg3` (`100.75.193.5`); the Fold is joined as
  `pixel-10-pro-fold` (`100.97.154.105`). `tailscale serve` proxies
  `https://powermacg3.tail0b3e3a.ts.net` → `http://127.0.0.1:8642`, confirmed **tailnet-only**
  (not Funnel — not exposed to the public internet).
- The ADB reverse tunnel (`adb reverse tcp:8642 tcp:8642`) is gone — `adb reverse --list` is
  empty. The app was re-tested against the real tailnet URL
  (`https://powermacg3.tail0b3e3a.ts.net`), not the loopback tunnel, and reported
  **“Authenticated. Session streaming is available.”** with no `ACCESS_LOCAL_NETWORK`
  permission declared. That decision is now recorded in `CLAUDE.md`: the permission is not
  needed, since Hermes traffic only ever goes over Tailscale (100.64.0.0/10, CGNAT space, not
  a private LAN range Android's local-network enforcement covers).
- **Slice 1's two remaining accept criteria are closed.** `mock-hermes/run.sh` runs a stdlib
  Python `http.server` replay of the three fixtures (`GET /api/sessions`,
  `GET /api/sessions/{id}/messages`, `POST /api/sessions/{id}/chat/stream`) on
  `127.0.0.1:8643` — manually verified with curl against all three routes plus 404s on
  unknown ids/paths. It is not yet wired into the Gradle test lifecycle; that happens when
  `core-hermes` contract tests exist in Slice 2. `app/build.gradle.kts` now declares a Gradle
  Managed Device (`aospAtdApi34`, AOSP ATD image, API 34) — `./gradlew :app:tasks` confirms
  `aospAtdApi34DebugAndroidTest` is registered, but it runs 0 tests today since there's no
  `androidTest` source set yet. Actually running that task would download an SDK system
  image; that hasn't been done in this session.
- Next: Slice 2 (`core-hermes` client) per `ROADMAP.md`.
- The effective dev key is in the local private file `~/.config/hermes-android/dev-api-key` (mode 600). The user enters it on the phone; never paste it into chat or logs.
