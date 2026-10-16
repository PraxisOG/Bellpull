# References — what to borrow from where

Immersive experiment (2026-10-09): Android's
[system-bar controller](https://developer.android.com/develop/ui/views/layout/immersive),
[reported display cutouts](https://developer.android.com/develop/ui/views/layout/display-cutout)
and [Compose visibility transitions](https://developer.android.com/develop/ui/compose/animation/composables-modifiers)
are used without new dependencies. The earlier system-bar discussion below is historical;
the opt-in experiment is now implemented and tested as recorded in `HANDOFF.md`.

Companion to the **"Borrow, don't build"** non-negotiable in `CLAUDE.md`. Check this file
before writing a subsystem. Pull the specific piece, not the feature set.

This file is the canonical borrow list. `ROADMAP.md` § Revised borrow list is the same
content in schedule context; if they drift, this file wins for *what* to borrow and the
roadmap wins for *when*.

## Borrow now (Tier 1)

| Source | Take exactly this | Leave behind |
|---|---|---|
| **Clawket** | **QR pairing**, to get the 64-char bearer key onto the phone without typing it. This is the only Tier 1 borrow. | The relay workers, the Expo app, the bridge CLI. Its relay exists to get remote access *without* Tailscale, which we already have — and its bridge reaches Hermes through the same API server we call directly. |

Relevant because the key is long and typo-prone: the dev key is 64 characters, and a
mistyped key surfaces as a generic auth failure.

## Read first for Tier 2 — it is already on this disk

| Source | Why it matters |
|---|---|
| **Hermes' bundled `plugins/google_meet/`** | A working example in the running Hermes source of `plugin.yaml`, `register(ctx)`, and `ctx.register_tool(...)`. The four host `hermes-launcher-*` directories formerly listed here were checked on 2026-09-16 and are empty. Borrow the registration mechanics, not the Meet feature. |

## Read before designing Tier 2 — do not implement yet

| Source | Why it matters |
|---|---|
| **WakeHermesClaw, "Mobile Bridge"** | An opt-in HTTP service *inside* an Android app that lets Hermes call into the phone — bearer token in EncryptedSharedPreferences, per-capability allowlist, binds to `127.0.0.1` by default, explicit approval for medium/high-risk capabilities. Borrow the **security model** — separate token, allowlist, audit log — which is exactly what `ROADMAP-TIER2.md` § T2-1 requires. Be aware its **transport is the inbound shape (option A)**, which that roadmap rejects in favour of the phone calling out via ntfy. Take the model, not the direction. |

## Tier 3

| Source | Why it matters |
|---|---|
| **Mobilerun** | On-device agent loop. Unchanged from the original assessment. Out of scope until after 2026-09-28. |

## Deliberately not borrowed

- **Hermes' own web dashboard** (`hermes dashboard`, port 9119 on this host). Overlapping
  `/api/sessions/*` surface, but browser-shaped auth: session cookies, a 15-minute access
  token with no refresh token, single-use WebSocket tickets, a DNS-rebinding Host guard,
  localhost-only CORS. A native client against it is a re-login treadmill. See
  `docs/hermes-api.md` § Decision.
- **The TUI gateway.** Richest surface, but JSON-RPC over stdio/WebSocket built for the Ink
  TUI, and it sends server→client requests a host must answer or the agent hangs. Tier 2+.
- **Legacy session-stream approvals.** Gate 0c still applies there. Bellpull Phase 2
  must use the verified `/v1/runs` request/response path for real approvals; see
  `docs/hermes-api.md` § Bellpull discovery (2026-10-09).
- **Anything Google.** No Firebase, no Play Services, no Play-dependent library. Push is
  ntfy. The device has zero Google surface.

## Bellpull appearance (2026-10-09)

Native wallpaper palette integration follows [Android's Material 3 dynamic color guidance](https://developer.android.com/develop/ui/compose/designsystems/material3#dynamic_color):
`dynamicLightColorScheme` / `dynamicDarkColorScheme` on API 31+, an ordinary preset
fallback below that. Uses the existing AndroidX Material dependency; no wallpaper
image access, Play services or new library. Supplied Bellpull PNGs provide the maroon
and brown seed colors; custom schemes use checked text contrast.

## Bellpull Phase 2 and compact layout (2026-10-09)

- Reuse the existing authenticated gateway, encrypted SessionCache, run status/history,
  and loopback ntfy deployment; no new app dependency or Google push surface.
- [ntfy subscription API](https://docs.ntfy.sh/subscribe/api/): cached JSON stream is a
  wake hint; canonical authenticated Hermes state and saved notification IDs deduplicate.
- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types):
  user-enabled `specialUse` listener with visible connection notification and Turn off.
- [Android notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission):
  request on explicit notification enablement; preserve denied/off state.
- Compose foundation already provides `BasicText` autosizing and `BasicTextField` with
  selection/IME support. Reused for single-line metadata and the compact growing composer.
- System bars were discussed only, per user instruction. Options are matching edge-to-edge
  backgrounds or optional immersive hiding; do not change them without a later request.
  [Android edge-to-edge](https://developer.android.com/develop/ui/compose/system/setup-e2e),
  [immersive mode](https://developer.android.com/develop/ui/views/layout/immersive).

## Phase 5 voice (2026-10-11)

Reuse the existing application runtime/durable journal and Android's installed
TextToSpeech voice, AudioRecord communication capture, available AEC/noise
suppression, audio focus, communication routing and MediaSession. See the primary
Android and transcription protocol links in [PHASE-5-VOICE.md](PHASE-5-VOICE.md).
No new dependency, cloud recognizer, model installation, wake word or Auto surface.
