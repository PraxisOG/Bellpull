# Tier 1 Roadmap — target 2026-09-28

*Revised 2026-09-16 after researching the Hermes API surface and the Android 17 platform
changes. Supersedes the first draft.*

11 working days (Thu 09/17 → Sun 09/27). Build freezes the morning of 09/28.

**Done for Tier 1:** a signed APK on the Fold that (1) has a widget opening a new Hermes
chat, (2) has a search bar that finds installed apps and accepts a Hermes prompt, (3) lists
sessions on the Hermes server, opens one, and streams a conversation with visible
context/memory.

---

## What the research changed

**The server side is solved and smaller than planned.** Hermes' API server exposes a full
REST sessions API — list, create, read, history, fork, and an SSE streaming turn — behind a
single static bearer key on one port. No custom plugin, no dashboard, no TUI gateway
WebSocket, no Clawket relay. See `docs/hermes-api.md`. Gate 0 shrinks from a day of
discovery to a 30-minute verification.

**Three risks moved in the other direction**, all new:

1. **`ACCESS_LOCAL_NETWORK`.** Android 17 (API 37, shipped June 2026) makes local network
   protections mandatory for apps targeting 37+. Enforcement sits deep in the networking
   stack and applies to OkHttp. Without the permission, LAN traffic **fails silently** — no
   crash, just nothing. The Fold is on Android 17.
2. **Approvals stall the chat.** A gated tool call waits on a human decision. A client that
   can't answer it hangs with no error. This is Tier 1, not Tier 2.
3. **The bearer key is root-equivalent** on the Hermes host — full toolset including
   terminal. The phone carries shell access to the homelab.

---

## Gate 0 — Day 1 (09/17)

**0a. Verify the contract.** `curl /v1/capabilities` against the dev container and confirm
the `session_*` flags and endpoints match `docs/hermes-api.md`. Where they disagree, the
live response wins — edit the file. Then record real responses from `/api/sessions`,
`/api/sessions/{id}/messages`, and one `/chat/stream` turn into `mock-hermes/fixtures/`.
Those fixtures are the test substrate for the next ten days.

**0b. Prove the network path on the real phone.** Empty Compose app → `installDebug` →
launches on the relocked GrapheneOS Fold → makes one authenticated GET to the dev
container. The permission question is decided by what this returns:

- Over **Tailscale** (100.64/10 through a tun interface) local-network enforcement may not
  apply at all. If it works without the permission, skip it entirely.
- Over **plain LAN** (192.168.x.x) it will fail silently without
  `ACCESS_LOCAL_NETWORK` declared *and* granted at runtime (it's in the NEARBY_DEVICES
  group, so an existing grant in that group may already cover it).
- Fallback if this gets messy: target SDK 36 for now and defer. Decide on Day 1, not Day 9.

**0c. Fix the dev loop before it costs you a day.** GrapheneOS defaults
*Settings → Security → Exploit protection → USB-C port* to **"Charging-only when locked"** —
new USB connections are blocked while the phone is locked and USB data dies when the current
connection ends. Every `installDebug` fails the moment the screen locks. Use **wireless
debugging** (pair over Wi-Fi) so the phone can sit locked, or change the setting knowingly.

**curl-y stays off the critical path.** Not required for Tier 1. Promote the prod env onto
the desktop container for the demo; diagnose the motherboard after 09/28.

---

## Slices

One branch, roughly one Claude Code session each.

### Slice 1 — Harness (Day 2, 09/18)
Repo skeleton, `CLAUDE.md`, Gradle Managed Device on a plain AOSP image, **Roborazzi**
screenshot tests with folded + unfolded configs, `mock-hermes` replaying the Gate 0 fixtures.

Roborazzi over Paparazzi or Compose Preview Screenshot Testing: it generates screenshot
tests directly from `@Preview` annotations (`generateComposePreviewRobolectricTests`), and
it's the only one of the three that can capture *after an interaction* — which the streaming
chat view needs. Google's preview-based tool wants previews in a separate source set.

At targetSdk 37 the large-screen orientation opt-out is gone, so adaptive layout on a
foldable is mandatory, not polish. Both width configs stay green from day one.

*Accept:* `test` and `verifyRoborazziDebug` green on an empty app; mock-hermes serves a
recorded session list.

### Slice 2 — `core-hermes` client (Day 3, 09/19)
Nine endpoints, one bearer header, one base URL. Pure Kotlin, no Android UI dependency.
Key stored in EncryptedSharedPreferences, never in a build file.

Two details that are cheap now and expensive later: send `Idempotency-Key` on every turn
(server-side dedupe that survives gateway restarts — the flaky-network resend problem,
already solved), and filter the session list with `source` + `include_children=false` or it
fills with cron, tool, and subagent noise.

*Accept:* contract tests green against mock-hermes; one live smoke test streams a real
reply; every error path returns a typed scoped error; 429 backs off rather than spinning.

> One day, not two. The surface collapsed.

**Done 2026-09-16**, ahead of schedule. All four criteria met: 13 contract tests against
mock-hermes plus 13 core-model unit tests; the opt-in live smoke test created a session,
streamed nine events and a real reply and round-tripped history; every failure returns a
`HermesError` case, including the two that needed finding — an unknown path returns plain
text rather than the JSON error envelope, and a stream dropped mid-flight is
`StreamTruncated` with its event count rather than a transport error; 429 honours the
server's `Retry-After` and gives up after three attempts.

One deviation from the plan above, deliberately: **no `Idempotency-Key` is sent.**
`docs/hermes-api.md` records that dedupe for `chat/stream` is unverified, so sending the
header would imply a retry safety nobody has established. It belongs with `/v1/runs`, which
Tier 1 does not use.

### Slice 3 — Chat client UI (Days 4–6, 09/20–22)
The long pole. Session list → session detail → streaming view → context/memory panel.
Render `assistant.delta`, `tool.started`, `tool.completed`, and the terminal event's real
`completed` / `partial` / `interrupted` flags — a truncated turn must not look finished.

*Accept:* all screens pass screenshot tests at both widths; streaming renders incrementally;
a dropped connection mid-stream shows a scoped error and leaves the session readable.

> If behind at end of Day 5, cut the context/memory panel to a read-only dump. Least
> load-bearing third of this slice.

### Slice 4 — ~~Approvals~~ **CUT** (frees Day 7, 09/23)

**Cut 2026-09-16 by Gate 0c, and written down here as the roadmap asked.** Not cut because
approvals are switched off — cut because they are *structurally unreachable on this
transport*. Hermes excludes the `api_server` platform from being an approval context in its
own source (`tools/approval_context.py:172`), precisely so an unattended client can't hang
waiting for a human who will never answer. A session created via `/api/sessions` never emits
a pending approval, so there is nothing for the phone to render or POST. Full evidence,
including two live probe turns and the source citations, is in `docs/hermes-api.md` § Gate 0c.

The feared failure mode — *"a chat that hangs forever with no error the first time the agent
tries to touch a shell"* — cannot happen on this surface. Gate 0c ran a real `rm -rf` through
the API and it completed in 8.8 s.

**What survives from this slice, absorbed into Slice 3** (both small):

1. Render `status: "blocked"` as its own honest state. Flagged commands fall to
   `approvals.unattended_mode`, default `deny`, so a command the user genuinely wanted can
   be refused with no prompt. "Hermes refused this command" — never a generic tool error.
2. Render the `approval` audit note when a tool result carries one, so an auto-approved
   destructive command is visible rather than silent.

**Also absorbed:** stop via `POST /v1/runs/{id}/stop`, which was tacked onto this slice.
Still wanted, still unverified against the live server.

> **Operator decision, not app work.** `approvals.unattended_mode` is unset in
> `~/hermes-data/config.yaml`, so it runs on the default `deny`. Recommendation: leave it.
> Setting it to `approve` would let the phone run anything smart approval flags, unattended.
> One line in the live config either way.

**Day 7 is now buffer.** Per the slip rule the deadline doesn't move; spend it on the two
things Gate 0c did *not* de-risk — the mock-hermes gap (Slice 2's accept criteria aren't
testable against the current replay server) and real-device soak.

### Slice 5 — Widget + new-chat entry (Day 8, 09/24)
Glance widget → intent → `POST /api/sessions` → straight into the chat view.

*Accept:* widget opens a fresh session in under two taps, cold start.

### Slice 6 — App search bar (Day 9, 09/25)
Query launchable apps and launch them; same field submits a prompt to Hermes.

Use a `<queries>` element with an `<intent>` for `ACTION_MAIN` + `CATEGORY_LAUNCHER` —
that returns exactly the launchable apps and needs no permission. **Not**
`QUERY_ALL_PACKAGES`, which is broader than needed and returns system packages you'd then
filter back out.

No Hermes dependency, so pull this forward if the server ever blocks you.

*Accept:* typing finds and launches a sideloaded app; a non-matching query offers "ask
Hermes" and lands in a new session with the text pre-filled.

### Slice 7 — Connectivity hardening (Day 10, 09/26)
Tailscale reachability, server-unreachable and auth-failed states distinguished, read-only
cache of the last-synced session list. Key rotation path for a lost phone.

*Accept:* airplane mode opens to cached sessions with an honest banner; server down names
which layer failed; no silent fallback anywhere.

### Day 11 (09/27) — Soak, buffer, demo build
Real-device use, battery, cold start, fold transitions. Signed release APK. Whatever isn't
done moves to the Tier 2 backlog. Freeze.

---

## Revised borrow list

- **Clawket** — narrower than planned. Its relay/bridge exists mostly to get remote access
  *without* Tailscale, which you don't need. Its bridge reaches Hermes through the same API
  server we're using directly. Borrow one thing: **QR pairing** to get the bearer key onto
  the phone without typing 40 random characters. Skip the relay workers, the Expo app, the
  bridge CLI.
- **WakeHermesClaw's "Mobile Bridge"** — *promote this for Tier 2.* It's an opt-in HTTP
  service inside an Android app that lets Hermes call *into* the phone: bearer token in
  EncryptedSharedPreferences, per-capability allowlist, binds to `127.0.0.1` by default,
  explicit approval for medium/high-risk capabilities. That is the Hermes-to-phone direction
  Tier 2 needs, already designed. Read it before designing the content-provider layer.
- **Mobilerun** — unchanged, Tier 3.

## Parking lot — not before 09/28

Foreground-app awareness · screen-context search · Organic Maps plugin · voice wake ·
in-test-environment UI feedback tool · offline action queue · on-device llama.cpp ·
TUI-gateway slash commands · the card/sidecar design · curl-y recovery.

## Slip rule

If a slice runs over by more than half a day, cut scope inside that slice rather than
pushing the schedule. The deadline doesn't move.
