# Handoff — Hermes Android

Written 2026-09-16. Entry point for whoever picks this up next.

## Read in this order

1. `CLAUDE.md` — non-negotiables, platform constraints, working agreement. Overrides habit.
2. `docs/hermes-api.md` — **the API contract.** Authoritative. Never invent an endpoint,
   field or auth scheme; if it isn't in there, stop and ask.
3. `ROADMAP.md` — tiers, slices, accept criteria, the slip rule.
4. `docs/references.md` — what to borrow before building a subsystem.

Only **Tier 1** is in scope. Tier 2/3 are out until after 2026-09-28.

## Where things stand

`master` is current — Slices 1, 2 and 3 are merged; nothing is parked on a branch.

| | |
|---|---|
| Tests | **111 JVM, 0 failures, 1 skipped** (the skip is the opt-in live smoke test) + **5 instrumentation** |
| Screenshots | **33**, all verified, at 393dp / 840dp / 1280dp |
| Modules | `app` (Compose UI), `core-hermes` (API client), `core-model` (types + errors) |
| Deps added, all approved | okhttp 5.5.0, kotlinx-serialization-json 1.11.0, kotlinx-coroutines-android 1.11.0 |

Built: connection screen → session list → session detail with streaming, tool-result
rendering, stop, and a read-only context panel. Adaptive across cover/unfolded/wide, with
both side panes collapsible. Plus an app-search bar and a Glance widget.

**All three Tier 1 deliverables now exist in code** — widget, app search, and
sessions/streaming with visible context. What is missing is confirmation on the phone, not
features. See the next section.

## Commands

```bash
cd android && ./gradlew test                    # unit + contract; starts mock-hermes itself
cd android && ./gradlew verifyRoborazziDebug    # screenshots
cd android && ./gradlew recordRoborazziDebug    # after an intentional UI change
cd android && ./gradlew installDebug            # needs the phone on the same LAN; see adb note
cd android && ./gradlew aospAtdApi34DebugAndroidTest   # instrumentation, headless emulator

# Live smoke test against a real Hermes. Opt-in; costs one inference call.
cd android && HERMES_SMOKE_URL=http://127.0.0.1:8642 \
  HERMES_SMOKE_KEY_FILE=~/.config/hermes-android/dev-api-key \
  ./gradlew :core-hermes:test --tests '*HermesLiveSmokeTest*'
```

## Verified vs not — read this before trusting anything

**Verified live against Hermes 0.21.1:** `/v1/capabilities`, `GET`/`POST /api/sessions`,
`GET /api/sessions/{id}/messages`, `POST /api/sessions/{id}/chat/stream`,
`GET /v1/runs/{id}`, `POST /v1/runs/{id}/stop`. Error shapes for 401, 404 (both kinds), and
both 400s. The client itself streamed a real reply and round-tripped history.

**Not verified — do not assume:** `PATCH`/`DELETE /api/sessions/{id}`, `/fork`, the
non-streaming `/chat`, `/v1/runs/{id}/events`, and the 429 body (source-derived only, marked
`observed: false` in `mock-hermes/fixtures/error-responses.json`).

**Verified on the real phone 2026-09-16 (Pixel 10 Pro Fold, Android 17/API 37, GrapheneOS).**
Installed over wireless debugging and launched with no crash; process stable. Confirmed on
glass, cover width: the saved URL round-tripped out of EncryptedSharedPreferences, "Check
connection" authenticated over `https://powermacg3.tail0b3e3a.ts.net`, and the app navigated
to a session list rendering **real Hermes sessions** — correct relative times, pluralisation
and previews. So OkHttp 5.5.0, kotlinx-serialization and coroutines all work on the device,
over Tailscale, with no `ACCESS_LOCAL_NETWORK`.

Reconnecting later: `adb mdns services` lists the Fold as `_adb-tls-connect._tcp`; the host
is already paired, so `adb connect <ip>:<port>` is enough. `adb` is not on `PATH` — it lives
at `~/Android/Sdk/platform-tools/adb`. Note `adb exec-out screencap -p` prefixes a
multiple-display warning to stdout on this device and corrupts the PNG; use
`adb shell screencap -p /sdcard/x.png` then `adb pull`.

**Still not exercised on glass** (the list screen is the only one confirmed on device):
session detail, streaming, tool-result rendering, stop, the context panel, the collapsible
panes and the three-pane unfolded layout, app search launching a real app, and the widget —
which has never been pinned to a real home screen. Everything else is Roborazzi renders plus
emulator instrumentation.

**Behaviour change worth knowing:** the app now **auto-connects on launch** when credentials
are stored, which the widget's "under two taps" criterion required. It makes one
`/v1/capabilities` call per start.

**Fixed since:** the redundant platform action bar (no theme was declared, so the app
inherited the default). `Theme.Hermes` now keeps DayNight and switches the bar off
explicitly, because `android:Theme.DeviceDefault.DayNight.NoActionBar` does not exist.
`MainActivityChromeTest` guards it.

## What the four gates settled

- **Gate 0a/0b** — API contract verified; `ACCESS_LOCAL_NETWORK` **not needed** (all traffic
  is Tailscale CGNAT, not a private LAN range). Do not add that permission.
- **Gate 0c** — **approvals cannot reach this client.** Hermes excludes the `api_server`
  platform from approval contexts in its own source, so a flagged command is either
  auto-approved by smart approval (leaving an `approval` audit note) or refused with
  `status: "blocked"`. **Slice 4 is cut.** Do not build an approval prompt.
- **Gate 0d** — **stop works but reports a stopped turn as `completed`/`interrupted: false`.**
  Stop and truncation are therefore tracked client-side, never from server flags. Stop before
  the first delta returns 409 `run_not_active` (a registration race, not a failure).

Full evidence and source citations: `docs/hermes-api.md` §§ Gate 0c, Gate 0d.

## Open items

1. **Finish exercising it on the Fold.** The list screen is confirmed on glass; session
   detail, a real streaming turn, stop, and the unfolded three-pane layout are not. Needs the
   phone on the same LAN — see the adb note below.
2. **One accept criterion is implemented but unproven:** history refetch after a *truncated*
   stream. See the long comment in `HermesStoreTest` — it's Compose snapshot visibility in
   plain JUnit, not a store bug. Wants a Compose-aware rule or an instrumentation test.
3. **Operator decision, still open:** `approvals.unattended_mode` is unset in
   `~/hermes-data/config.yaml`, so it defaults to `deny`. Recommendation was to leave it.
   Changing it is one line in the live config, not app work.
4. **Context panel shows session metadata only.** Hermes' long-term memory contents are not
   shown because no verified endpoint reads them back. Don't invent one.
5. **No `androidTest` source set.** `aospAtdApi34DebugAndroidTest` is registered but runs 0
   tests, and the SDK image has never been downloaded.

## Done 2026-09-17

- **New-chat entry point.** `createSession` existed in the client but nothing in the UI
  called it, so the app could only open sessions created elsewhere, and a fresh Hermes was a
  dead end. There is now a "New chat" button on the list; a failed create shows a banner and
  leaves the list usable.
- **Three panes at the Fold's unfolded width.** The threshold was 1000dp, so 840dp got two
  panes and a toggle. Now 820dp with the list at 240dp and context at 200dp, leaving the
  transcript ~400dp. Narrowing exposed three wrapping bugs (model name broken mid-word,
  "3 tool calls" split mid-label, an ellipsised cost figure) — all fixed.
- **`androidTest` stands up and runs.** Source set created, deps added, managed device
  switched from Pixel 6 to **Pixel 10 Pro Fold** geometry with `testedAbi = "x86_64"` pinned
  (AGP warns the default flips to arm64-v8a in AGP 10.0, and this ATD image cannot do NDK
  translation, so the flip would break the task). `aospAtdApi34DebugAndroidTest` went from
  0 tests to **2 passing**. The AOSP ATD API 34 image is downloaded; a run takes ~20s warm,
  ~1m30s cold.
- **The action-bar guard was verified by making it fail.** Removed the theme, confirmed both
  tests fail, restored it, confirmed both pass. Worth doing: a guard that has never failed
  may be asserting nothing. That check also caught that my second test was a copy of the
  first; it now asserts the Activity reaches RESUMED, which nothing on the JVM side does.

**81 JVM tests, 28 screenshots, 2 instrumentation tests.**

## Landmines

- **The bearer key is root-equivalent on the Hermes host** — shell as the container's
  `hermes` user, and full read/write over `~/hermes-data` including `.env` (so the API key
  and provider keys). Never log it, never commit it, never put it in a fixture. The dev key
  lives at `~/.config/hermes-android/dev-api-key` (mode 600).
- **Hermes runs in a rootless Docker container**, not from `~/.hermes`. `HERMES_HOME=/opt/data`
  is bind-mounted from `~/hermes-data`; the code at `/opt/hermes` exists only inside the
  container (reach it via `/proc/<gateway-pid>/root`). `docker` CLI is not available to this
  user. **Don't edit `~/hermes-data/config.yaml` without saying so** — it's the live instance.
- **An unknown path returns plain text `404: Not Found`, not the JSON error envelope.** A
  client assuming JSON on every non-2xx will throw a parse error instead of reporting the 404.
- **`tool.completed` carries no tool output.** Results exist only in message history, which
  is why the store refetches on every stream termination.
- **Three different response envelopes** — `POST /api/sessions` wraps in `session`, not `data`.
- **Redirects are refused, not followed** (`core-hermes`), because a same-host redirect would
  carry the bearer key onward. Don't "fix" that by re-enabling them.
- **GrapheneOS blocks USB data while locked.** Use wireless debugging.
- **Default branch is `master`, not `main`.**
- **adb cannot reach the phone over Tailscale from this host.** `tailscaled` runs with
  `--tun=userspace-networking` (the no-sudo install), so there is **no TUN interface and no
  host route into `100.64.0.0/10`** — host-originated connections to tailnet IPs fail with
  `Network is unreachable`. The working direction is phone → host, because `tailscale serve`
  terminates inside tailscaled and needs no TUN. That asymmetry is also why the live smoke
  test uses `127.0.0.1:8642` rather than the tailnet hostname. **`installDebug` and `logcat`
  therefore need the phone on the same LAN.** Workarounds if that ever matters: a
  `socat` + `tailscale nc` bridge through the userspace stack, or giving tailscaled a real
  TUN, which needs root and would undo the deliberate no-sudo setup.

## Next

Run it on the Fold. Then Slice 5 (widget) or Slice 6 (app search — no Hermes dependency, so
it's the safe one to pull forward). Day 7 is free from the Slice 4 cut. Freeze is the morning
of 2026-09-28.
