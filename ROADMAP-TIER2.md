# Tier 2 Roadmap — the phone-agency layer

*Written 2026-09-16, after Tier 1 went code-complete. No hard deadline; Blue Origin starts
09/28 and this becomes evening and weekend work at that point. Everything before then is
slack that Tier 1 didn't use.*

> **Four claims in this file were checked against the live host on 2026-09-17 and three of
> them needed correcting — plugin path, ntfy reachability, and prior art that already exists.
> See *Verified against the host* at the end before starting T2-0 or T2-1.*

**What Tier 2 is:** Hermes stops being something you chat with on the phone and starts being
something that can read the phone. Contacts, SMS, notifications, and eventually calendar.

**What changes structurally:** Tier 1 was one-directional — the phone called the server and
the server answered. Tier 2 inverts it. Hermes now needs an answer *from* the phone, which
means the phone has to be reachable when it's in your pocket with the screen off. That
inversion is the whole risk in this tier. Everything else is content providers, which are
well-documented and boring.

---

## Carry-over from Tier 1 — close these first

Tier 1 is code-complete, not demo-complete. Don't start Tier 2 on top of unverified Tier 1.

- **On-glass checklist in `HANDOFF.md`.** Only the session list has been driven on the
  device. Stop-mid-stream is the flagged one — the server reports a stopped turn as
  `interrupted: false`, so the client is the sole source of truth for that state and nothing
  on the JVM can corroborate it. Drive it stopping right after the first delta *and* while a
  tool is running; those are different paths.
- **Generate the signing keystore.** A signing config that's never been exercised is its own
  untested path, and doing it on the 27th is how you find that out.
- **R8 stays off until after the on-glass pass**, with the reason already written down. A
  keep-rule mistake presents as a widget that silently never appears.
- **`approvals.unattended_mode` is still unset** (so deny). One line in
  `~/hermes-data/config.yaml`. Recommendation stands: leave it at deny and let the client
  render refusals honestly.

---

## Already decided — do not relitigate

These came out of scoping and are settled. Claude Code should treat them as constraints.

- **Order: contacts → SMS → notification listener.** Calendar is last and blocked.
- **Contacts are read-only in the first pass.** No create, no edit. Exposed to Hermes as a
  name-based lookup returning phone numbers and emails. Test fixture set is 5–6 fake
  contacts including at least one duplicate name and one contact with multiple numbers.
- **SMS reading is automatic; sending is manually gated.** Hermes can draft a message. It
  sends only when you tap a send button inline in the chat UI. There is no auto-send path
  and none gets added later without an explicit decision.
- **Notification capture is generic** — source app, title, body. No per-app parsing. Deeper
  extraction gets added case by case only when a specific need appears.
- **Accessibility service is a fallback**, not a slice. It gets scheduled only when something
  concrete needs arbitrary on-screen content and nothing cheaper reaches it.
- **Calendar ground truth is Nextcloud on curl-y**, synced to Hermes over CalDAV and down to
  the phone. Blocked until curl-y is back.
- **The phone is the sole origin point for any write or outbound action** touching personal
  data or communications. The server reads and reasons; it never mutates personal data and
  never contacts anyone unilaterally. This is the architecture boundary from the Tier 3
  discussion and it constrains Tier 2's transport — see below.
- **Server side is Hermes plugins**, not a bespoke backend.

---

## Gate T2-0 — prove the phone is reachable

Same shape as Gate 0 last time: the thing that can invalidate the whole tier goes first, and
nothing else gets built until it has an answer.

### The question

Can Hermes get an answer out of the phone when the phone is in your pocket, screen off, app
not open, several hours after you last touched it?

### Why it's genuinely hard

- A `dataSync` foreground service has a **hard 6-hour cap** on Android 15+. The system stops
  it regardless of what it's doing.
- Apps targeting API 31+ **cannot start a foreground service from the background**, so it
  can't quietly restart itself after that cap.
- The usual escape hatch — a high-priority FCM message granting a background FGS start — is
  **unavailable**. No Play Services, by design.
- `specialUse` may have different timeout behavior and is the plausible correct type for a
  personal device bridge, but **verify it rather than assuming**. This is exactly the class
  of thing the Android 17 local-network finding turned out to be.

GrapheneOS helps rather than hurts here: no OEM background killer, and you can exempt the app
from battery optimization yourself.

### The transport decision this gate makes

Two shapes, and your own architecture boundary already points at one of them:

**A. Hermes calls into the phone.** Phone runs an HTTP service on its tailnet address.
Simple to reason about, matches WakeHermesClaw's Mobile Bridge, and runs straight into every
restriction above.

**B. The phone calls out, woken by ntfy.** Hermes publishes a wake event to the ntfy topic
already in your Compose stack. The phone wakes, calls Hermes, fetches pending requests,
executes locally, posts results back. The phone never accepts an inbound connection.

**B is the recommendation.** It's the same principle you already settled on for writes — the
phone initiates — applied to the transport instead of the data. It also degrades honestly:
if the phone doesn't answer, Hermes gets a timeout and says so, rather than a half-open
connection that looks alive.

The cost is latency on cold requests. That's fine; the common case is you're in the app
already with a live connection.

### The test

One Hermes plugin, one tool: `phone_ping`. Echo a string. Then call it in four states:

1. App in the foreground
2. App backgrounded five minutes
3. Screen off one hour
4. Screen off overnight — **this is the real test**

*Accept:* a written answer for each of the four states, and confirmation that an unreachable
phone produces a scoped error rather than a hang. Record it in `docs/phone-bridge.md`. If
state 4 fails, that's a finding, not a failure — it means Tier 2 tools are
"works-when-recently-used" and the tool descriptions should say so to the model.

---

## Slices

### T2-1 — Bridge + plugin skeleton
The transport chosen in T2-0, plus the Hermes-side plugin scaffold: a directory in
`~/.hermes/plugins/` with a `plugin.yaml` and Python, which Hermes discovers at startup and
exposes alongside built-in tools. These are **Tools, not Skills** — they need end-to-end
integration with auth, which is the documented dividing line.

Build in the security model now, not later:

- **A separate token from the Tier 1 API key.** Different direction of trust, different
  credential, independently revocable.
- **A capability allowlist on the phone.** The bridge answers `contacts.lookup` because it's
  on the list, not because the server asked nicely.
- **An on-device audit log** of every request the server made, visible in the app.
- **A kill switch** — one toggle that stops answering, without uninstalling anything.

Write the plugin to be installable by someone else from the start: config through env vars,
no hardcoded tailnet addresses. Your stated reason for building server-side work as plugins
was that other people could run it on their own Hermes, and the plugin catalog is SHA-pinned
with admission CI if you ever want to submit it.

*Accept:* `phone_ping` round-trips through the real plugin with the real token; a request for
a capability not on the allowlist is refused and logged.

### T2-2 — Contacts
`READ_CONTACTS`, name-based lookup, returns numbers and emails. Read-only.

The fixture set is the interesting part: a duplicate name and a multi-number contact are
there to force the question of what the tool returns when the answer is ambiguous. It should
return both and let the model ask, not silently pick the first row.

*Accept:* lookup against the fixture set returns both duplicates distinguishably; a
multi-number contact returns all numbers with their labels; a miss returns an explicit empty
result, not an error.

### T2-3 — SMS read
`READ_SMS`. Thread list, messages in a thread. Read path only.

*Accept:* threads list with correct participants and recency; a thread's messages come back
in order with directions correct.

### T2-4 — SMS send, gated
Hermes drafts; the app renders the draft with recipient and body; you tap send. The gate is
a UI affordance, and it is the feature — the send path should be structurally incapable of
firing without a tap, not merely defaulted off.

*Accept:* there is no code path from a tool call to a sent message. Prove it by trying.

### T2-5 — Notification listener
`BIND_NOTIFICATION_LISTENER_SERVICE`, enabled by the user in system settings. Generic capture
only: source app, title, body.

Worth knowing: this service is bound by the system, so it survives without a foreground
service of its own — it's the one Tier 2 component that doesn't fight the background
restrictions. Handle rebind after reboot.

*Accept:* notifications from three different apps captured with correct attribution;
survives a reboot without re-enabling by hand.

### T2-6 — Calendar (blocked)
Nextcloud on curl-y → CalDAV → Hermes → phone. Not startable until curl-y is back, and it's
the only slice with a hardware dependency. Don't let it hold up the rest.

---

## Realistic sequencing

T2-0 through T2-3 is a plausible target before 09/28 given how Tier 1 went — that's the
transport proven, the plugin pattern established, and two content providers working. T2-4 and
T2-5 land after the job starts. T2-6 whenever curl-y comes back.

Don't compress T2-0 to get further down the list. It's the only part of this tier that can
turn out to be impossible.

## Honest grade

**Content providers: A−.** Well-documented APIs, clear scope, your fixtures already designed.
The contacts and SMS slices are the kind of work Tier 1's pace actually predicts.

**Transport: C+, and it's the whole tier.** Not because it's hard to write, but because the
platform is actively hostile to what it needs, the usual workaround depends on the Google
stack you deliberately removed, and you can't find out whether it holds without leaving a
phone alone overnight. Budget real calendar time for T2-0, not real coding time — most of it
is waiting.

## Blast radius, stated plainly

Tier 1 put a key to your homelab on your phone. Tier 2 puts a key to your messages and
contacts on your homelab. That's a new direction of trust and it deserves the separate token,
the allowlist, and the audit log rather than a second use of the credential you already have.

---

## Verified against the host — 2026-09-17

Checked while preparing the handoff. The plan above is unchanged in substance; these are
factual corrections and one addition, in the same spirit as the Android 17 local-network
finding this file already cites.

### 1. The plugin directory is not `~/.hermes/plugins/`

`~/.hermes` **does not exist on this host.** Hermes runs as a rootless Docker container with
`HERMES_HOME=/opt/data`, bind-mounted from `~/hermes-data`. So T2-1's plugin scaffold goes in:

| | |
|---|---|
| Host path | `~/hermes-data/plugins/<name>/` |
| In-container path | `/opt/data/plugins/<name>/` |

This is the same mistake `docs/hermes-api.md` carried until Gate 0c corrected it — the
upstream docs describe a non-container install. See that file's *Setup on the Hermes host*.

**Friction worth knowing before you start:** everything already in `plugins/` is **root-owned**
while the gateway runs as uid 1000. Creating a new plugin directory there will need `sudo` or
an ownership fix, and this host was deliberately set up without sudo for Tailscale. Resolve
that before writing code, not after.

### 2. Correction: the apparent prior art is empty

`~/hermes-data/plugins/` contains four root-owned directories from 2026-09-11:

```
hermes-launcher-briefs      hermes-launcher-cards
hermes-launcher-retrieval   hermes-launcher-tasks
```

**Rechecked 2026-09-16: all four directories are empty.** They are not plugin examples.
The running Hermes source has bundled plugins; `plugins/google_meet/` shows the manifest,
`register(ctx)`, and tool registration mechanics for this version. See
`docs/phone-bridge.md` for the gate discovery log.

### 3. ntfy needed a tailnet route — resolved for the gate

Good news first: **ntfy is live** on `127.0.0.1:2586` and answers `/v1/health` with 200. So
option B's wake transport exists.

But it is **loopback-only**. `tailscale serve status` shows exactly one route:

```
https://powermacg3.tail0b3e3a.ts.net (tailnet only)
|-- / proxy http://127.0.0.1:8642
```

That was Hermes, on `/`. Gate T2-0 added a separate tailnet-only Tailscale Serve HTTPS route
on port 8443 to `http://127.0.0.1:2586` on 2026-09-16. The Fold loaded ntfy's health
endpoint and received a live SSE message over this route. See `docs/phone-bridge.md`.

`/` was already taken, so the gate considered two routes:

- **`tailscale serve --set-path /ntfy`** keeps one hostname, but ntfy subscriptions are
  long-lived SSE/WebSocket connections and a path prefix is the kind of thing that works for
  REST and breaks for streaming. Verify a *subscription*, not just a GET.
- **`tailscale serve --https=8443`** gives ntfy its own port and avoids the path question
  entirely. Less tidy, fewer unknowns.

The second route was chosen and the live subscription worked while the browser was
foregrounded. Background and overnight reachability remain Gate T2-0 measurements.

### 4. Confirmed as stated

- The `dataSync` 6-hour cap, the API 31+ background-FGS-start restriction, and the absence of
  FCM are all real and are the actual shape of this problem. No correction needed.
- ntfy and searxng both run as **host processes**, not from a Compose file in this repo. There
  is no `infra/` directory here and never was — see `CLAUDE.md`'s repo layout, which was
  corrected on 09-16 for the same reason.
