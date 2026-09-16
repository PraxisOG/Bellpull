# Handoff — Hermes Android

Rewritten 2026-09-17. Entry point for whoever picks this up next, human or agent.

## Read in this order

1. `CLAUDE.md` — non-negotiables, platform constraints, working agreement. Overrides habit.
2. `docs/hermes-api.md` — **the API contract.** Authoritative. Never invent an endpoint,
   field or auth scheme; if it isn't in there, stop and ask.
3. `ROADMAP.md` — Tier 1: slices, accept criteria, the slip rule, what was cut and why.
4. `ROADMAP-TIER2.md` — Tier 2: its gate, its slices, and decisions marked *do not
   relitigate*. Read its *Verified against the host* section before writing any plugin code.
5. `docs/references.md` — what to borrow before building a subsystem.

## State in one paragraph

**Tier 1 is code-complete and partly confirmed on the device. Gate T2-0 is in progress on
`tier2-gate-phone-ping`; see `docs/phone-bridge.md`.** Tier 1 built six
slices (1–3, 5–7) and cut Slice 4 with evidence. All three things Tier 1 called "done" exist:
the widget, the app-search bar, and sessions/streaming with visible context.

| | |
|---|---|
| Tests | **124 JVM**, 0 failures, 1 skipped (the opt-in live smoke test) |
| Screenshots | **34**, all verified, none blank, at 393dp / 840dp / 1280dp |
| Instrumentation | **8**, on a Pixel 10 Pro Fold managed device (AOSP ATD, API 34) |
| Modules | `app` (Compose UI), `core-hermes` (API client), `core-model` (types + errors) |
| Deps, all approved | okhttp 5.5.0, kotlinx-serialization-json 1.11.0, coroutines 1.11.0, glance-appwidget 1.2.0 |

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

Everything below is Roborazzi renders plus emulator instrumentation only:

- The **unfolded three-pane layout** and the ☰ / ⓘ pane toggles. Worth prioritising: those
  screenshots were blank until 09-17 and the layout has never been seen by a human.
- **App search launching a real app**, and a non-matching query pre-filling a new session.
- **Airplane-mode** cached list with its banner.
- **Disconnect** clearing credentials, and re-entering a key afterwards.
- **The widget pinned to a real home screen.** Registration is verified against a real
  `AppWidgetManager` on the emulator, but a real launcher is a different host.

## Tier 1 carry-over — do this before Tier 2

Also listed at the top of `ROADMAP-TIER2.md`.

1. **Work the list above.** Stop-mid-stream deserves two passes: stopping right after the
   first delta, and stopping while a tool is running. Different code paths.
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
2. **Gate T2-0** — prove the phone is reachable when it has been asleep for hours. It is the
   only part of Tier 2 that can turn out to be impossible, and compressing it to reach the
   content providers sooner is the specific mistake `ROADMAP-TIER2.md` warns about. It has a
   prerequisite that roadmap did not list: ntfy needed a tailnet route. Port 8443 now
   provides one, and a foreground SSE subscription worked on the Fold. See
   `docs/phone-bridge.md`; background and overnight results are still pending.
3. Then T2-1 (bridge + plugin skeleton). The four
   `~/hermes-data/plugins/hermes-launcher-*` directories are empty, despite the earlier
   handoff claim; see `docs/phone-bridge.md`. Use bundled Hermes plugins as examples.
