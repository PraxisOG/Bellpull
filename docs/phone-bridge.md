# Phone bridge — Gate T2-0

Started 2026-09-16. This is the evidence log for the Tier 2 reachability gate in
`ROADMAP-TIER2.md`. A network probe is not a `phone_ping` result: the gate remains open until
the app and Hermes plugin round-trip in all four device states.

## Pause checkpoint — 2026-09-16

> **Superseded 2026-09-16 23:34 PDT by the overnight run below.** The route is back up and
> the service is running. The resume/stop commands in this section are still the correct ones;
> the state it describes is no longer current.

Work is paused at the user's request on branch `tier2-gate-phone-ping`. The debug APK
containing the ping service is
installed on the Fold, and its service is **stopped**. Tailscale Serve port 8443 is **off**;
the existing Hermes route on `/` remains. The live Hermes config and plugin directory were
not changed. No one-hour or overnight screen-off run has started.

Verification before pausing: `./gradlew test`, `:app:assembleDebug`, and
`:app:assembleRelease` passed; the release merged manifest contains no ping service.

> **Corrected 2026-09-18.** The original claim here also said the release manifest contains no
> foreground-service permission. That is wrong. It contains no
> `FOREGROUND_SERVICE_SPECIAL_USE` — the one the gate harness declares, and the one that
> matters — but the **base `android.permission.FOREGROUND_SERVICE` is present**, together with
> `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` and `ACCESS_NETWORK_STATE`. All four are injected by
> `androidx.work` (`work-runtime-ktx` 2.7.1), pulled in transitively by `glance-appwidget`.
> The app's own manifest declares only `INTERNET`. Re-verified against
> `app/build/intermediates/merged_manifests/release/`: `PingBridgeService` 0 occurrences,
> `FOREGROUND_SERVICE_SPECIAL_USE` 0 occurrences, no `exported="true"` service. The plugin handler registered against a test context and
completed a real ntfy round trip, but it has not been loaded by the running Hermes gateway.

To resume, first arrange a period when the Fold can remain powered, on Tailscale, and
screen-off for the one-hour and overnight measurements. Re-enable only the temporary route:

```bash
/home/praxisog/.local/opt/tailscale_1.102.4_amd64/tailscale \
  --socket=/home/praxisog/.config/tailscale-hermes/tailscaled.sock \
  serve --bg --https=8443 --yes http://127.0.0.1:2586
```

Then start the debug service using the command under *Gate harness* below. Run
`phone_ping` with the staged handler, or install and enable the plugin after resolving the
root-owned plugin directory and gateway configuration. Record actual elapsed time and device
state for each result. To run the staged handler directly from the repo root:

```bash
PHONE_PING_NTFY_URL=http://127.0.0.1:2586 \
PHONE_PING_REQUEST_TOPIC=hermes-tier2-gate-requests-20260916 \
PHONE_PING_REPLY_TOPIC=hermes-tier2-gate-replies-20260916 \
python3 -c 'import importlib.util; s=importlib.util.spec_from_file_location("gate", "phone-ping-plugin/__init__.py"); m=importlib.util.module_from_spec(s); s.loader.exec_module(m); print(m.phone_ping({"echo":"resume-check"}))'
```

When pausing again, stop the service and turn off port 8443:

```bash
/home/praxisog/Android/Sdk/platform-tools/adb shell am stopservice -n org.praxisog.hermes/.PingBridgeService
/home/praxisog/.local/opt/tailscale_1.102.4_amd64/tailscale \
  --socket=/home/praxisog/.config/tailscale-hermes/tailscaled.sock serve --https=8443 off
```

Confirm `tailscale serve status` shows only Hermes.
Do not proceed to contacts or SMS until this gate is documented.

## Preflight on the real Fold

Device: Pixel 10 Pro Fold, GrapheneOS / Android 17, connected by wireless adb. Hermes
0.1.0 is installed. The Fold was open and awake for these checks.

| Check | Result |
|---|---|
| ntfy on host | `http://127.0.0.1:2586/v1/health` returned `{"healthy":true}`. |
| Tailnet route | Temporarily added Tailscale Serve HTTPS port 8443 to proxy `http://127.0.0.1:2586`. Existing Hermes route on `/` remained. The gate route was disabled after the five-minute test pending screen-off timing. |
| Phone to ntfy | The Fold's browser loaded `https://powermacg3.tail0b3e3a.ts.net:8443/v1/health` and showed `{"healthy":true}`. |
| Streaming subscription | The Fold's browser opened `/hermes-tier2-gate-probe-20260916/sse`; a message published on the host appeared in the live SSE response. This proves the route carries a stream while the browser is foregrounded. |

The port 8443 Serve route was **tailnet only**, not a public Funnel route. While active it
exposed ntfy without topic authentication to other tailnet members, so it was turned off
after the five-minute test. The probe topics carried no personal data. A real bridge must
use its own separately revocable credential and enforce a phone-side capability allowlist
before it handles personal data.

## Gate measurements still required

| State | `phone_ping` round trip | Notes |
|---|---|---|
| App foreground | Pass, 2026-09-16 13:54 PDT | Debug app service answered `gate-foreground-1` through ntfy. The staged plugin handler received the same echo. Hermes has not loaded the plugin yet. |
| App backgrounded five minutes | Pass, 2026-09-16 13:59 PDT | Launcher was the resumed activity for five minutes; foreground service stayed registered and answered `gate-background-five-minutes`. |
| Screen off one hour | **Pass**, 2026-09-17 00:39 PDT, 0.17 s | In deep Doze (`deep=IDLE`) and **not** battery-exempt. |
| Screen off overnight | **Pass**, 2026-09-17 07:24 PDT, 0.26 s | 11/11 pings answered sub-second across 7h45m unplugged. See results below. |
| Phone unreachable | **Pass through real Hermes**, 2026-09-18 01:47 PDT | The agent called the plugin tool in an `api_server` session; it returned `phone_unreachable` after 25 s and the assistant reported it verbatim. No hang. |

## Gate harness

The first implementation is intentionally limited to ping. `android/app/src/debug/` contains
a `specialUse` foreground service; the release APK contains none of it. It accepts only
`phone_ping` with an ID and up to 200 characters of echo text and publishes `phone_pong` to a
configured topic. The server handler and Hermes manifest are in `phone-ping-plugin/`.
Both ntfy URLs and topic names are supplied at run time, so no tailnet address is compiled
into the APK or plugin.

The phone service was started while Hermes was visible with:

```bash
adb shell am start-foreground-service -n org.praxisog.hermes/.PingBridgeService \
  --es server https://powermacg3.tail0b3e3a.ts.net:8443 \
  --es request_topic hermes-tier2-gate-requests-20260916 \
  --es reply_topic hermes-tier2-gate-replies-20260916
```

To stop the service, use `adb shell am stopservice -n
org.praxisog.hermes/.PingBridgeService`. The service persists its three non-secret settings
for a process restart and returns `START_STICKY`. The app service was stopped after the
five-minute check pending screen-off timing. Restart it and the port 8443 Serve route for
the one-hour and overnight checks.

The gate topics have no authentication and must carry no personal data. They are suitable
only for this disposable reachability experiment. T2-1 must replace them with a separately
revocable credential, phone-side allowlist, on-device audit log, and kill switch before any
contact, SMS, or notification operation exists.

## Host discovery correction

`~/hermes-data/plugins/hermes-launcher-{briefs,cards,retrieval,tasks}` exist as root-owned
**empty directories**. Earlier handoff and reference text called them working prior art;
those files have been corrected. There are no `plugin.yaml` or Python files in the host
directories. Hermes' bundled `google_meet` plugin
is an available example of `register(ctx)`, `ctx.register_tool(...)`, and a manifest. The
user plugin directory is root-owned and `sudo -n` requires a password, so deploying a plugin
there needs an ownership or installation step.

The running Hermes source also shows plugins are opt-in through `plugins.enabled`. Installing
a plugin alone will not activate it. The live `~/hermes-data/config.yaml` has not been edited.

## Overnight run — started 2026-09-16 23:34 PDT

The decisive measurement. Harness, logs and results live outside the repo at
`~/hermes-gate-t2-0-night/` (`overnight.py`, `run.log`, `results.jsonl`), running detached
under `setsid` so it does not depend on an open session.

### Conditions

The phone is **unplugged, on battery**, at 90% and screen-locked for the night. This
deliberately contradicts the pause checkpoint's instruction to keep the Fold "powered":
**Doze only engages on battery**, so a plugged-in phone would never enter the state this gate
exists to measure. Powered would have quietly invalidated the result.

Verified before starting: ntfy healthy; Tailscale Serve `:8443` re-enabled; the phone's
Tailscale interface up (`tun1`, `100.97.154.105`); `wifi_sleep_policy=2` (Wi-Fi stays on with
the screen off); `PingBridgeService` running as a `specialUse` foreground service. An awake
baseline `phone_ping` through the real plugin handler round-tripped in **0.33 s**, and a
separate wiring check confirmed the detached listener observes pongs end to end.

Night topics are fresh — `hermes-tier2-gate-{requests,replies}-20260916-night` — so no cached
message from the afternoon's run can be mistaken for an overnight reply.

### Two deliberate choices in the measurement

**1. True latency is recorded separately from the 25 s product timeout.** `phone_ping` returns
`phone_unreachable` after 25 s, which is the right product behaviour but the wrong
instrument: "answered at T+8min" and "never answered" are different findings, and the roadmap
already says a state-4 failure is a finding rather than a failure. The harness therefore holds
a listener open on the reply topic all night and timestamps every pong, whether or not it beat
the 25 s window. Each request id embeds its send epoch, so a late reply is still attributable.

Because the service reconnects with `since=<lastMessageId>` and ntfy caches messages, pings
published while the phone is unreachable are replayed on reconnect. The reply timestamps
therefore give a precise timeline of *when the phone regained network*, even if every ping
misses its product window. A killed-and-restarted process falls back to `since=10m` and would
lose older requests; the reply timeline still shows the restart.

**2. The night is split by Doze exemption.** The app was **not** on the battery-optimisation
whitelist — the honest out-of-the-box state — so the run starts there and covers the 1-hour
measurement unexempted. At 01:44 the harness applies the exemption via
`dumpsys deviceidle whitelist +org.praxisog.hermes` and the rest of the night runs exempted.
One night yields both configurations instead of spending it on one. The toggle was tested and
reverted before the run, so the flip is known to work; if adb has died by then the failure is
logged and the night continues unexempted, which is a stricter result, not a lost one.

Schedule (T0 = 23:34): T+20m, T+1h, T+2h, **flip**, T+3h, T+4h, T+5h, T+5h30, T+6h, T+6h30,
T+7h, T+7h45, then a 10-minute grace sweep. The cluster from T+5h to T+6h30 brackets the
**6-hour foreground-service cap** — `dataSync` has a hard one and `ROADMAP-TIER2.md` flags
`specialUse` timeout behaviour as assumed-not-verified. Service liveness (`isForeground=true`),
battery, and both Doze states are polled at every ping, best-effort over adb.

### Still open after this run

A pass here is reachability through the *staged* handler. Hermes has still never loaded the
plugin — the root-owned `~/hermes-data/plugins/` and `plugins.enabled` blockers are untouched,
and the live `config.yaml` has not been edited.

### Collection and teardown are automatic

Four detached processes, all under `~/hermes-gate-t2-0-night/`:

| Process | Role |
|---|---|
| `overnight.py` | Publishes the scheduled pings; holds the reply listener open; `results.jsonl`. |
| `collect.py` | One persistent `adb logcat` stream → `doze.log`. Reconnects if wireless adb drops. |
| `finish.py` | Waits for the run to end, writes `REPORT.md`, then tears down in a `finally`. |
| `failsafe.sh` | Independent backstop at ~08:15: tears down if `DONE` is absent. |

**Doze is observed by one long-lived logcat stream, not by repeated `dumpsys` polling.**
Repeatedly invoking adb shell commands risks perturbing the state being measured; a single
idle stream is the standard observation method. It is also self-validating — `REPORT.md`
prints an explicit caveat if no Doze transition was captured, because that means either the
device never idled or the observation itself was the confound. Either way the result is not
quietly reported as a pass.

Teardown runs without anyone present: `:8443` off, service stopped, Doze exemption removed so
the phone is returned to its prior state, collector killed, and `tailscale serve status`
appended to `run.log` as proof. It sits in a `finally` block, so a crash in report generation
still closes the route — the route exposes ntfy to tailnet members without topic
authentication while it is up, which is not a thing to leave to a morning checklist. The
report generator was exercised against partial data before the run to confirm it cannot fail
closed and leave no output.

## Overnight result — 2026-09-17

**Gate T2-0 passes. All four states answer.** 11 of 11 scheduled pings answered inside the
25 s product timeout; none late, none missed. Full table in `~/hermes-gate-t2-0-night/REPORT.md`.

| | |
|---|---|
| Window | 2026-09-16 23:34 → 2026-09-17 07:24 PDT, 7h45m |
| Power | Unplugged throughout; battery 90% → 72% at the last ping (~2.3%/h) |
| Latency | 0.17–1.0 s, median ~0.26 s. Slowest was T+4h at 1.0 s |
| Doze | `deep=IDLE` at **every** ping, including the three before the exemption |
| Service | `isForeground=true` at every ping, including T+7h45 |

### The two findings that matter

**1. The 6-hour foreground-service cap did not apply.** `ROADMAP-TIER2.md` flags the hard
`dataSync` cap and marks `specialUse` timeout behaviour as assumed-not-verified. Verified now:
the `specialUse` service was still alive and answering at **T+7h45**, 1h45m past where a
`dataSync` service would have been stopped. Four pings bracket the 6h mark (T+5h, T+5h30,
T+6h, T+6h30) and all pass.

**2. The Doze battery exemption was not needed.** The night was deliberately split — the first
three pings ran with the app **not** on the battery-optimisation whitelist, which is the
out-of-the-box state. The device was already in deep Doze (`deep=IDLE`) by T+20m and answered
in 0.25 s, 0.17 s and 0.23 s. The exemption applied at 01:44 changed nothing measurable. A
foreground service with an ongoing notification keeps its network access through Doze, and that
is sufficient. **Tier 2 does not need to ask the user for a battery exemption**, which removes a
setup step and a permission prompt from T2-1.

So the tools are *not* "works-when-recently-used". The pessimistic framing in
`ROADMAP-TIER2.md` — transport graded C+, the platform actively hostile — does not hold for
this transport on this device. Option B (phone calls out, woken over ntfy) is confirmed.

### What this does not prove

- **Hermes still has never loaded the plugin.** Every ping went through the *staged* handler
  run from the repo. `~/hermes-data/plugins/` is root-owned and `plugins.enabled` is untouched
  in the live `config.yaml`. That blocker is unchanged and is now the critical path for T2-1.
- **Wi-Fi only.** The Fold stayed on home Wi-Fi with `wifi_sleep_policy=2` all night. Cellular,
  Wi-Fi→LTE handover, and a Tailscale reconnect after a network change are untested.
- **The phone was stationary.** Doze engaged quickly and stayed; a phone moving in a pocket
  may idle differently.
- **One night, one device.** No reboot happened during the window, so service restart after
  reboot is untested — relevant because the bridge must survive one.
- **The ongoing FGS notification is the mechanism, and it is also a cost.** A permanent
  notification is the price of this reachability; T2-1 should decide deliberately whether that
  is acceptable rather than inheriting it from the gate harness.

### Harness defects found, worth not repeating

- **`pgrep -f <script>.py` self-matches the launcher shell.** The finisher's `running()` check
  matched the still-alive `bash -c` wrapper whose command line embedded the script's own source
  text, so it never detected that the ping run had ended. Teardown did not fire on schedule and
  was completed by hand at 08:02, roughly 28 minutes before the failsafe would have caught it.
  The `:8443` route was up about 35 minutes longer than intended. Match on a PID file, not a
  command-line pattern.
- **`DeviceIdleController` logs nothing on this build.** The collector captured 367 lines over
  the night and zero of them were Doze transitions. The persistent-logcat approach was chosen
  *over* `dumpsys` polling on the theory that repeated adb invocations might perturb Doze. The
  opposite held: polling did not prevent deep Doze, and it supplied the only Doze evidence
  there is. Use `dumpsys deviceidle get deep` at sample points.

## Plugin installed on the live Hermes — 2026-09-18

Closes the "Hermes has never loaded the plugin" gap. Researched from the running Hermes
0.21.1 source (`/proc/<gateway-pid>/root/opt/hermes`) before anything was touched; the source
overrides the upstream docs where they disagree.

### How Hermes plugin discovery actually works here

| Question | Answer, from source |
|---|---|
| User plugin dir | `get_hermes_home()/plugins` → `~/hermes-data/plugins/`. **Our docs were right**; `plugins/AGENTS.md` says `~/.hermes/plugins/` but that is generic prose for a non-container install. |
| Layout | `<plugins>/<dir>/plugin.yaml` (or `.yml`, or portable `plugin.json`). Category nesting one level, depth cap two. |
| Enable key | `key = f"{prefix}/{dir}" if prefix else name` (`plugins_manifest.py:413`) — for a flat install it is the manifest's **`name:` field**, not the directory name. Ours is `phone_ping_gate`. |
| Enable gate | `plugins.enabled` is an opt-in allow-list. A missing key means *nothing* is enabled, not "everything". `plugins.disabled` is a deny-list that wins. |
| Platform exposure | `api_server.py:2122` resolves `_get_platform_tools(config, "api_server")`. Plugin toolsets are **on by default** unless default-off or already "known" for the platform (`tools_config.py:538`). |
| Capabilities | Seven declarable capabilities, all about overriding built-ins / LLM routing / platform actions. `phone_ping` needs none — which matters, because consent **fails closed in any non-interactive context**. |

`kind: standalone` is the valid default and `provides_tools` is a real known field, so the
manifest written for the gate needed no changes.

### Changes made to the live instance

All three were approved explicitly. The gateway was **not** restarted; it kept PID 1048105
across every step.

1. **`~/hermes-data/plugins/` replaced.** The original root-owned directory was renamed to
   `plugins.root-old` and a user-owned `plugins/` created. No `sudo` was needed: the parent
   `~/hermes-data` is user-writable, and renaming needs write permission on the *parent*, not
   the directory. The four `hermes-launcher-*` directories were re-verified empty first and
   are preserved untouched inside `plugins.root-old`.
2. **Plugin copied** to `~/hermes-data/plugins/phone_ping_gate/`. A **copy, not a symlink** —
   a symlink would point at the repo path, which does not exist inside the container.
3. **`config.yaml`** gained a `plugins.enabled: [phone_ping_gate]` block, appended at end of
   file. **`.env`** gained the three non-secret `PHONE_PING_*` variables. Backups:
   `config.yaml.bak-20260918`, `.env.bak-20260918`, modes preserved, `.env` still `0600`.

### Verified before restarting anything

Hermes' own discovery code was run against the installed directory with `HERMES_HOME` and
`PYTHONPATH` pointed at the live paths — read-only, no gateway involvement:

- Before the config edit: `GATE -> placeholder`, error *"not enabled in config"*.
- After: `GATE -> load`, no error. (`ManifestGate.enabled` is a dataclass default on the
  `load` branch and is meaningful only for `placeholder` records; the action is the verdict.)
- `config.yaml` parses, 24 top-level keys, exactly one `plugins:` block.
- The installed copy imports, `_config()` resolves the `.env` values, `register(ctx)` calls
  `register_tool(name=phone_ping, toolset=phone_ping_gate)`, and `phone_ping` degrades to
  `phone_unreachable` with the phone service stopped.

### Two corrections to earlier docs

- **The container shares the host network namespace.** The gateway's `127.0.0.1:8642` socket
  appears in the *host's* `ss` output with its own PID, as does ntfy on `127.0.0.1:2586`. So
  the plugin reaches ntfy over loopback from inside the container and the **server side needs
  no tailnet route at all**. The `:8443` Serve route is only for the *phone*.
- **`docker` is installed** at `/usr/bin/docker`, so "the docker CLI is not available to this
  user" is imprecise. The binary exists; the daemon socket is `/var/run/docker.sock`,
  root-owned, permission denied. No `docker exec`, no `docker restart`. That socket path also
  makes the "rootless Docker" description doubtful — a rootless daemon would use
  `/run/user/1000/docker.sock`, which does not exist here.

### Rollback

```bash
cd ~/hermes-data && mv config.yaml.bak-20260918 config.yaml && mv .env.bak-20260918 .env \
  && rm -rf plugins && mv plugins.root-old plugins
```

### Gateway restarted and the plugin loaded — 2026-09-18 01:39 PDT

The restart risk turned out to be small. The gateway's direct parent is
`s6-supervise gateway-default`, so `kill -TERM` on the gateway PID is a supervised restart,
not an outage: **the new process was up and answering in 6 seconds** (PID 1048105 →
1210127), and `gateway-starts.log` recorded the new start. Container logs are UTC; the host
is PDT.

Pre-flight before the signal, all through Hermes' own code rather than a YAML parse:
`load_config()` succeeded with 95 keys, `plugins.enabled` resolved to `['phone_ping_gate']`,
and `_get_platform_tools(config, "api_server")` **already included `phone_ping_gate`** — so
the platform exposure was confirmed by the real resolver rather than left to the default.

**Confirmed loaded** by `GET /v1/toolsets` (`platform: api_server`), which lists 28 toolsets
ending with `phone_ping_gate | tools: ['phone_ping']`.

> `/v1/toolsets` is **not** in `docs/hermes-api.md`. It was read from the installed source
> (`api_server.py:1511`) and used here only as a read-only diagnostic, not as client surface.
> It is now verified live and is a candidate for the contract file if the app ever needs it.

### End-to-end through a real session

Session `api_1789720850_a262a904` (`source: api_server`), one real inference call:

1. The agent called `tool_describe` and got the plugin's schema — the tool is genuinely
   visible to the model, not merely registered.
2. It called `phone_ping`, which executed **inside the gateway process**, published to ntfy
   over container loopback, waited, and returned
   `{"ok": false, "error": "phone_unreachable", "detail": "No reply within 25 seconds"}`.
3. The assistant reported that verbatim. **It did not hang** — which is the accept criterion
   `ROADMAP-TIER2.md` states for the unreachable case.

Not verified in this run: the SSE event names. The parser keyed on a field inside the JSON
payload rather than the SSE `event:` line and collected nothing, so the evidence above comes
from message history, not from the stream. The stream event contract is unchanged from what
`docs/hermes-api.md` already records; this run simply did not re-test it.

### What is left to close Gate T2-0 completely

**One thing: a real `phone_pong` through the real plugin.** Everything else is done. The
phone was off the LAN at 01:45 (no adb, no ping to `192.168.254.134`), so the ping service
could not be started. When the phone is back:

1. Re-enable the route (it was turned off again — with the phone unreachable it exposed ntfy
   to the tailnet for no benefit, and the *server* side needs only loopback).
2. Start `PingBridgeService` with the two night topics.
3. Re-run the same prompt and expect `{"ok": true, "echo": "...", "received_at_ms": ...}`.

The plugin, the config, the toolset exposure and the failure path are all proven. What
remains is the success path.

### Decided 2026-09-18: T2-1 closes the join, not the harness

**Do not spend further effort closing this with the gate harness.** The reason the last step
needs adb is that `PingBridgeService` lives in `app/src/debug/` with **no in-app entry point** —
nothing in the app references it, so the only way to start it is an external intent. That is a
property of the disposable probe, not of the design.

T2-1 already requires **a kill switch — one toggle that stops answering without uninstalling
anything**, which is an in-app control in the release build. So the real bridge starts and stops
from the app UI and never touches adb, and the one untested step — Hermes calling the plugin
while a phone is actually listening — is exercised by construction the first time T2-1 runs,
with the real separately-revocable credential instead of an unauthenticated probe topic.

Closing it now with the harness would prove a configuration that is about to be deleted.

Why the residual risk is low: the installed plugin is **byte-identical** to the module the phone
answered 11/11 times overnight (`md5 cf25991dc260325f40c35da86e39593d` for both
`phone-ping-plugin/__init__.py` and `~/hermes-data/plugins/phone_ping_gate/__init__.py`). Last
night was not a mock of this code; it *was* this code, invoked from the repo rather than by
Hermes. Both halves are proven independently and they meet over the same topics and JSON.

Reachability from outside the LAN was confirmed while writing this: the Fold showed `idle` on the
tailnet from a different network, and `tailscale nc 100.97.154.105 <port>` returned **connection
refused** rather than a timeout — packets reach the phone; only `adbd` is not listening. A local
TCP bridge through `tailscale nc` would work (direct TCP cannot: `tailscaled` runs
`--tun=userspace-networking`, so this host has no route into `100.64.0.0/10`). It was not used.

### Security note to carry into T2-1

The gate service is declared `android:exported="true"` with **no permission guard**, and it takes
`server`, `request_topic` and `reply_topic` as intent extras. Any app on the device could
therefore start it and point it at a server of its choosing. That is why adb can start it at all.

Acceptable for a debug-only probe carrying nothing but an echo string on a personal phone. **The
real bridge must not inherit it.** A Tier 2 non-negotiable is that the phone answers because a
capability is on the allowlist, not because something asked — an externally startable service
aimed at an arbitrary endpoint is that same hole one layer down. The T2-1 service should be
unexported (or permission-guarded), take its endpoint from stored configuration rather than from
the intent that starts it, and log every start to the on-device audit log.
