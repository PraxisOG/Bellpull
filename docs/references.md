# References — what to borrow from where

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
- **Any approval-prompt implementation.** Settled by Gate 0c: unreachable on this transport.
  See `docs/hermes-api.md` § Gate 0c.
- **Anything Google.** No Firebase, no Play Services, no Play-dependent library. Push is
  ntfy. The device has zero Google surface.
