# Phone bridge — Gate T2-0

Started 2026-09-16. This is the evidence log for the Tier 2 reachability gate in
`ROADMAP-TIER2.md`. A network probe is not a `phone_ping` result: the gate remains open until
the app and Hermes plugin round-trip in all four device states.

## Pause checkpoint — 2026-09-16

Work is paused at the user's request on branch `tier2-gate-phone-ping`. The debug APK
containing the ping service is
installed on the Fold, and its service is **stopped**. Tailscale Serve port 8443 is **off**;
the existing Hermes route on `/` remains. The live Hermes config and plugin directory were
not changed. No one-hour or overnight screen-off run has started.

Verification before pausing: `./gradlew test`, `:app:assembleDebug`, and
`:app:assembleRelease` passed; the release merged manifest contains no ping service or
foreground-service permission. The plugin handler registered against a test context and
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
| Screen off one hour | Pending | |
| Screen off overnight | Pending | This is the decisive persistence check. |
| Phone unreachable | Pass in handler, 2026-09-16 13:55 PDT | Publishing to an unhandled request topic returned `phone_unreachable` after 25 seconds. Hermes tool rendering remains untested. |

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
