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
  (not Funnel — not exposed to the public internet). `tailscaled` runs under the enabled
  user systemd unit `~/.config/systemd/user/tailscaled-hermes.service`
  (`WantedBy=default.target`, `Restart=on-failure`) — it restarts automatically on login/
  reboot on this host, this isn't a one-off manual process. `Linger=no` for this user, so it
  won't come up before a graphical/SSH login on a fresh boot with nobody logged in.
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

- The effective dev key is in the local private file `~/.config/hermes-android/dev-api-key` (mode 600). The user enters it on the phone; never paste it into chat or logs.
- **Slice 1 is merged.** `slice1-minimal-app` fast-forwarded into `master` at `c7a183f`.
  The default branch is `master`, not `main`.
- **Gate 0c is done — approvals are settled and Slice 4 is cut.** The `api_server` platform
  is excluded from approval contexts in Hermes' own source, so this client can never receive
  a pending approval; flagged commands are auto-approved by smart approval or denied with
  `status: "blocked"`. Two live probe turns on session `api_1789554464_8413b4c7` (left on the
  server as evidence; safe to delete). Evidence and source citations in `docs/hermes-api.md`
  § Gate 0c; the schedule consequence is in `ROADMAP.md` § Slice 4. Day 7 is now buffer.
- **One operator decision is open:** `approvals.unattended_mode` is unset in
  `~/hermes-data/config.yaml` and therefore `deny`. Recommendation is to leave it. Changing
  it is a one-line edit to the live config, not app work.
- **Docs reconciled with disk.** `CLAUDE.md`'s repo layout described `core-hermes/`,
  `core-model/` and an `infra/` Compose tree that were never built; it now separates what
  exists from what's planned. `docs/references.md` was cited by a non-negotiable but missing
  — now written. `docs/hermes-api.md` said config lived in `~/.hermes/.env`; the real
  deployment is a rootless Docker container with config at `~/hermes-data/`.
- **`mock-hermes` is hardened.** Bearer auth on every route, `POST /api/sessions`, chunked
  event-by-event SSE with `MOCK_HERMES_SSE_DELAY`, and per-request fault injection via the
  mock-only `X-Mock-Fail` header (auth / rate-limit / stream-abort / slow-headers). Every
  error body it returns was captured from the live API; the 429 alone is source-derived and
  labelled `observed: false` in `fixtures/error-responses.json`.
- **Slice 2 is done, on branch `slice2-core-hermes`.** `core-model` (typed data + a sealed
  `HermesError`) and `core-hermes` (OkHttp client, six endpoints, SSE) are committed.
  **31 tests green** — 5 app, 13 core-model, 13 core-hermes — screenshots unchanged, and the
  opt-in live smoke test really did create a session, stream 9 events and a reply, and
  round-trip history against the running instance.
- Dependencies added with approval: `okhttp:5.5.0` and `kotlinx-serialization-json:1.11.0`.
  The full approved list is now recorded in `CLAUDE.md`.
- Note for whoever picks this up: **`app/` still does not use `core-hermes`.** Its connection
  screen predates the module and talks to `/v1/capabilities` with `HttpURLConnection`
  directly. Migrating it is Slice 3's first job.
- The live smoke test runs over `http://127.0.0.1:8642`, not the tailnet name: MagicDNS does
  not resolve from this host, because `tailscaled` runs with `--tun=userspace-networking` and
  no host DNS integration. The phone resolves it fine — that was Gate 0b. So the tailnet path
  is proved from the Fold, and the client is proved against live Hermes, but not both at once
  from here.
- Next: Slice 3 (chat client UI) per `ROADMAP.md`, starting by moving `app/` onto
  `core-hermes`.
