## Bellpull implementation — 2026-10-10

The selected scope is `Bellpull-Implementation-Prompt.txt` (v1.0). Its phases supersede
older tier deferrals below; existing behavior and installed identity remain protected.

Completed Phase 2 addition: named encrypted server profiles, profile-isolated local
state, Settings-based sign-in/switch/sign-out/removal, and useful disconnected
exploration. The GitHub APK quickstart shipped first; the 0.2.0 update now documents
Settings-based setup and no-login exploration. See Phase 2E in the implementation
prompt and the latest validation in `docs/HANDOFF.md`. The user subsequently
authorized Phase 3 and requested testing and installation of its working build.

- Phase 0: baseline build/test/screenshot verification passes (cached Gradle checks).
  Kotlin 2.3.21 / Compose BOM 2026.08.00 / AGP 9.4.0; SDK 29–37;
  app ID `org.praxisog.hermes`. Existing working tree preserved, no commit/reset.
- Phase 1 first release implemented and installed: Bellpull label; system/light/dark
  appearance, collapse-thinking and reduced-motion preferences; encrypted per-chat
  drafts/selection; **one horizontal row** for Chats/Projects/Tools/Settings/Today;
  expanded app/chat search from the drawer magnifier; quoted follow-up;
  exact-prefix fork-and-edit; thinking and grouped chronological tool rows.
  Projects/Today are explicit placeholders; Tools
  currently exposes working app/chat search and session context.
- Phase 1 validation: 194 tests passed, 1 live smoke skipped; 65 screenshot baselines
  verified; debug APK and lint pass (18 advisory warnings). Corrected the pre-existing
  Glance RestrictedApi errors with its public day/night colour factory, no suppression.
  Source snapshot backup: `/tmp/bellpull-snapshots-before-oct09/` (before this slice).
- Phase 2 implemented: durable request journal/admission/status recovery, canonical
  missed-reply catch-up, GrapheneOS-compatible ntfy notifications, and explicit per-run
  server approval controls. Server patch active and app installed. Final validation,
  device evidence and remaining operational limits are in `docs/HANDOFF.md`.
- Phase 3 implementation is in the 0.3.0 Android build: configured session models and
  reasoning, persistent file/image uploads, PDF/image previews, attachment browsing,
  Android sharing and local GPU telemetry plugin. See the handoff for final acceptance
  status and real hardware limitations. The user clarified no Moe/SSH access is needed:
  the plugin calls amd-smi/nvidia-smi on its own Hermes host.
- Phases 4–7 remain pending. Projects/Today placeholders do not imply completion.
- Desktop emulator packaging is parked until the Android phases are done. An isolated
  emulator may still be used for Android acceptance tests.

October 9 conversation list follow-up:
- Conversations is a plain heading, aligned left and vertically centered with the
  magnifier. The magnifier is the only drawer search entry point.
- Completed unread replies sort ahead of read chats, then by latest activity. The list
  scrolls to index zero on a completion/new unread promotion or a changed server head,
  including another completion in a chat already first. An ordinary metadata refresh
  preserves the user's position; the selected older chat cannot override a promotion.
- Unread dot moved to the bottom-right after the message/tool-count metadata, using the
  theme accent (dark in light appearance, contrasting in dark appearance), as requested.
- Local read receipts persist in the encrypted cache using canonical message IDs. A dot
  clears only when the saved reply's end is visible in the foreground transcript. Merely
  selecting a chat, browsing older content, or leaving its window in the background does
  not clear it. Late completion/read events cannot regress newer receipts.
- Current detection covers completed legacy streams observed by this running app, even
  after switching chats. Old imported chats are not fabricated as unread. Catch-up for
  work completed while the app process was dead/disconnected is now covered by Phase 2
  for tracked runs and previously synchronized conversations.

October 9 density, streaming and color refinement:
- Removed the Bellpull drawer wordmark/subtitle/logo and Activity block. Horizontal modes
  and the top-right chat controls remain; the cover conversation list retains its New chat.
- Tool/search rows and collapsed thinking headers are 32dp high (expand with font scale),
  with whole-row actions. Empty assistant envelopes/whitespace no longer add blank lines.
- Clear only the submitted draft revision at `run.started` acknowledgement. Show an
  accepted local user bubble until authoritative history arrives; retain newer drafts,
  rejected submissions and other chats. No endpoint/server patch changes required.
- Autoscroll follows prose, reasoning, tool layout growth and viewport changes while pinned.
  Reader upward scrolling or history navigation suspends it; reaching the bottom or
  Jump to latest resumes it.
- Settings now persist System/White/Black plus Neutral, Maroon (#59091E), Dark brown
  (#381B18), Original teal, Android wallpaper and a validated custom accent scheme.
  Surfaces/bubbles follow the accent; text contrast is checked; warning/refusal colors
  remain semantic. Android wallpaper uses native dynamic colors on API 31+, with an
  explained disabled option on older Android. No wallpaper permission or new dependency.
- Launcher uses the supplied maroon hand/tassel PNG unchanged. Adaptive XML fits it to
  the icon mask. Empty states/connection branding use the same art; Hermes response
  labels still identify the agent. Original dark brown artwork supplies the brown preset.
- Validation and final device evidence are recorded in docs/HANDOFF.md below the current
  refinement entry. Baselines before this refinement: /tmp/bellpull-density-snapshots-before/.

Ownership: Hermes owns canonical sessions/messages/runs/tools; Bellpull owns drafts,
UI preferences and read position. Future projects/artifacts need one explicit authority.
No LLM calls for navigation or state management. No silent model-route changes.

Verified gateway discovery: PowermacG3 Docker image `nousresearch/hermes-agent:v2026.9.7`
with selected-reply and before-user-prompt fork patches; no Git checkout in container. `/v1/capabilities`
reports durable idempotent run submission (24-hour retention), status, steering and
approval requests/responses. **Older approval deferrals below apply to the legacy
session stream, not `/v1/runs`.** Source confirms independent executor tasks;
SSE is an in-memory consuming queue, not replay. Restart marks unfinished durable runs
interrupted; recovery must use status plus saved history, never repeat tool execution.
`/v1/models` currently exposes only `hermes-agent`; provider options are not proof of
configured local routes. Memory-write/audio/realtime voice APIs report unavailable.
Browser artifact transport has a 10 MiB / 300-second scoped lifetime and is not a
persistent attachment store. Moe GPU/model/STT/TTS and coding runner contracts remain
unverified. The source exists for jobs/model locks, but those need scoped probes before UI.

Phase 1 evidence and remaining limits:
- ADB device `58311FDCG0011Y` physically unfolded, no device-state override.
  Reopened existing chats, verified A→B→A and Settings→Chats draft preservation,
  actual light appearance, horizontal mode row, keyboard spacing, selected quote
  into a focused editable draft without sending, and first-prompt fork/edit.
  A forced app stop/relaunch restored both the selected chat and encrypted draft.
  User then resumed typing; ADB automation stopped without altering their draft.
  Server/API comparison confirms source session/history unchanged; edit branch has
  zero messages until the user sends. No cloud inference invoked by these checks.
- Per-chat drafts/selection are stored under the existing encrypted cache boundary,
  removed on acceptance only if not edited since submission, and cleared on Disconnect.
  Activity state carries only navigation, not arbitrarily large draft text bundles.
- Follow-up uses an explicit selection dialog; native selection retains Android's
  standard actions. Adding Follow up to the native toolbar is a remaining polish gap.
- Legacy streaming provides sequence numbers but no tool-call IDs. New tool rows have
  stable run/sequence identities; duplicate sequences are ignored; same-name parallel
  completion remains unresolved until authoritative history (do not guess).
- No Activity section above conversations. Local completion receipts, unread-first sorting,
  bottom-right dots and top reveal are now implemented (see follow-up above). Phase 2 must
  extend these same UI semantics to durable run recovery and missed server work.
- Real folded/unfolded transition was not forced. Cover/unfolded layouts are verified
  in rendered tests; physical device tests in this slice used unfolded state only.
- Server extension lives at `~/hermes-agent/patches/bellpull-edit-fork/`: 16 isolated
  HTTP/DB tests pass; hash-guarded apply/rollback; runtime patch plus reusable image.
- No checkpoint commit: this branch contains substantial pre-existing uncommitted
  work intertwined with these changes. No reset, stash or unrelated staging occurred.

Next implementation slice: durable submission journal with stable request IDs;
separate admission/status/subscription, per-session run ownership, ambiguity recovery,
then completion/read catch-up, notifications and genuine approvals. Preserve the current legacy
path until durable routes are contract-tested. Moe hostname/SSH user remains unanswered;
current provider is Fireworks DeepSeek and must not be presented as local inference.

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

**Also absorbed:** stop via `POST /v1/runs/{id}/stop`. **Verified 2026-09-16 (Gate 0d)** —
it works, but it does not settle as `cancelled` the way this file and `docs/hermes-api.md`
both claimed. A stopped turn reports `completed: true, partial: false, interrupted: false`
and a run status of `completed`, so it is indistinguishable from a finished one. There is
also a registration race: stop called immediately after `run.started` returns 409
`run_not_active`.

Consequence for Slice 3's "a truncated turn must not look finished": that has to come from
client-side state — a stream the client saw drop, or a stop the client itself issued — not
from the terminal event's flags. The button still ships, because stopping genuinely works.

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

**Done 2026-09-17.** One tap, not two — the widget tap is the only one, because the app now
auto-connects when credentials are stored. That auto-connect was forced by this criterion:
widget → Check connection → New chat was three taps. **Not yet confirmed on the phone:** the
cold-start path is verified by instrumentation on an emulator, and the widget has never been
pinned to a real home screen.

### Slice 6 — App search bar (Day 9, 09/25)
Query launchable apps and launch them; same field submits a prompt to Hermes.

Use a `<queries>` element with an `<intent>` for `ACTION_MAIN` + `CATEGORY_LAUNCHER` —
that returns exactly the launchable apps and needs no permission. **Not**
`QUERY_ALL_PACKAGES`, which is broader than needed and returns system packages you'd then
filter back out.

No Hermes dependency, so pull this forward if the server ever blocks you.

*Accept:* typing finds and launches a sideloaded app; a non-matching query offers "ask
Hermes" and lands in a new session with the text pre-filled.

**Done 2026-09-17.** All criteria covered by behavioural tests. A test caught a real bug
first: `queryIntentActivities` was passed `MATCH_DEFAULT_ONLY`, which only matches filters
carrying `CATEGORY_DEFAULT` — launcher activities do not declare it, so the query returned
nothing and would have on the device too. **Not yet confirmed on the phone:** launching a
real sideloaded app.

### Slice 7 — Connectivity hardening (Day 10, 09/26)
Tailscale reachability, server-unreachable and auth-failed states distinguished, read-only
cache of the last-synced session list. Key rotation path for a lost phone.

*Accept:* airplane mode opens to cached sessions with an honest banner; server down names
which layer failed; no silent fallback anywhere.

**Done 2026-09-17.** All three met, with the cache encrypted because session titles and
previews are the opening lines of real conversations. Key rotation is the Disconnect action,
which forgets the key and the cache and says in words that rotating `API_SERVER_KEY` on the
host is what actually revokes a lost device. **Not yet confirmed on the phone:** real airplane
mode.

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

## Deferred deliberately, cheap when picked up

**Light/dark theme support — done 2026-10-03.** The app now follows the system setting
through `HermesTheme`, with fixed teal/neutral light and dark schemes. Amber marks flagged
commands and red marks refusals; wallpaper colours cannot change those meanings. The widget
palette matches, and the launcher has an adaptive Hermes monogram.

The GUI pass also adds session cards, clearer message hierarchy, session titles in the chat
header, a centred connection card, and empty-chat guidance. Nine additional screenshot
variants cover dark mode and new empty/error states. Chat creation errors now stay visible
on an empty session list and during search. Validation is local JVM rendering only; the
real-device checklist remains open.

**Conversation controls — done locally 2026-10-03.** Compact tool bars open full input/output
dialogs, retaining refusal and flagged status on the bar. New chat sits beside the overflow
menu; session info, prompt-history jumps, and local chat search work. Share remains a labeled
placeholder. Twelve additional screenshots cover the controls; the device checklist is still
open. See `docs/HANDOFF.md` and `beauty-shots/conversation-controls/index.html`.

## Parking lot — not before 09/28

Foreground-app awareness · screen-context search · Organic Maps plugin · voice wake ·
in-test-environment UI feedback tool · offline action queue · on-device llama.cpp ·
TUI-gateway slash commands · the card/sidecar design · curl-y recovery.

## Slip rule

If a slice runs over by more than half a day, cut scope inside that slice rather than
pushing the schedule. The deadline doesn't move.
