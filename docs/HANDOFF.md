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
| Tests | **76, 0 failures, 1 skipped** (the skip is the opt-in live smoke test) |
| Screenshots | **25**, all verified, at 393dp / 840dp / 1280dp |
| Modules | `app` (Compose UI), `core-hermes` (API client), `core-model` (types + errors) |
| Deps added, all approved | okhttp 5.5.0, kotlinx-serialization-json 1.11.0, kotlinx-coroutines-android 1.11.0 |

Built: connection screen → session list → session detail with streaming, tool-result
rendering, stop, and a read-only context panel. Adaptive across cover/unfolded/wide.

## Commands

```bash
cd android && ./gradlew test                    # unit + contract; starts mock-hermes itself
cd android && ./gradlew verifyRoborazziDebug    # screenshots
cd android && ./gradlew recordRoborazziDebug    # after an intentional UI change
cd android && ./gradlew installDebug            # needs wireless debugging paired

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

**THE BIG ONE: the app has never run on the phone since Slice 1.** `assembleDebug` passes,
but `adb devices` is empty — the wireless pairing is gone. Every UI claim rests on Roborazzi
renders, not glass, and OkHttp / serialization / coroutines have never executed on the real
Fold. **Re-pair wireless debugging and run it before building anything else.**

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

1. **Run on the Fold.** Highest priority. Needs wireless debugging re-paired.
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

## Next

Run it on the Fold. Then Slice 5 (widget) or Slice 6 (app search — no Hermes dependency, so
it's the safe one to pull forward). Day 7 is free from the Slice 4 cut. Freeze is the morning
of 2026-09-28.
