# Cellular connection audit and recovery repair

The reported symptom was an approximately 30-second disconnect, usually on
cellular, followed by repeated Gemma fallback prompts or a local chat. The
selected phone profile was the existing PowermacG3 Test HTTPS endpoint through
Tailscale. Measurements used the same authenticated capabilities request as the
production connection monitor, from an isolated Android package on the owner's
Pixel 10 Pro Fold. The production app remains Android 0.20.0/build 50.

## Measured behavior

| Path | Requests | Successful | Median | p95 | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: |
| Phone, Wi-Fi | 30 | 30 | 95.5 ms | 151 ms | 527 ms |
| Phone, stationary cellular | 120 | 120 | 209 ms | 300 ms | 791 ms |
| Same client across four Wi-Fi/cellular transitions | 100 | 99 | 111.5 ms | 485 ms | 5,044 ms |

The first Wi-Fi-to-cellular handoff produced one five-second transport timeout.
The next authenticated request succeeded 2,564 ms after that failure returned.
The remaining three transitions did not produce another failed probe. This
reproduces a short handoff interruption, not the full reported 30-second outage.

The separate workstation-to-host sample made 270 requests across `/health`,
`/v1/capabilities` and a one-row session list. All returned HTTP 200; per-route
medians were 8.7–10.9 ms and the maximum was 37.9 ms. Response contents were
discarded; no personal conversations were included in the evidence.

Cellular latency was higher than Wi-Fi but comfortably below the probe deadline
in the stationary sample. Direct versus DERP routing of the actual phone-to-host
connection was not established. Tailscale documents that relayed paths can add
latency, but these measurements do not establish a relay fault:
[connection types](https://tailscale.com/docs/reference/connection-types) and
[performance troubleshooting](https://tailscale.com/docs/reference/troubleshooting/poor-performance-tailnet).

## Confirmed client defects and repair

Previously, one failed five-second capabilities probe immediately marked the
entire workspace offline. The monitor then slept 12 seconds before trying again.
A failed session-list refresh could independently latch the offline state even
while later capabilities probes succeeded. Authentication and throttling errors
also entered the same fallback path. Local model loading defaulted on; tapping
New chat while marked offline silently chose a new local chat.

The candidate changes this behavior:

- A failed reachability probe first shows a stable **Reconnecting to server**
  status. Remote chat remains selected and available during this tentative state.
- Offline requires at least three consecutive temporary failures spanning ten
  seconds. One successful authenticated probe clears it. Recovering/offline
  probes retry after two seconds; healthy probes retain the 12-second interval.
- Network availability and transport changes wake the monitor, including a VPN
  that retains its Android Network while switching underlying radios.
- Successful probes coalesce retries of stale/failed conversation lists and
  refresh history after reconnect. They never submit or replay a chat turn.
- Authentication, TLS, throttling and protocol errors retain their actual
  explanation and do not offer automatic local fallback. Retry-After is respected
  by the periodic monitor, bounded to five minutes.
- Local loading defaults off. Existing explicit opt-ins remain saved, but require
  a minute of continuous observed failure and a fresh probe before native loading.
  Neither a short outage nor a stale foreground snapshot is sufficient.
- New chat always targets the selected server. New local chat and Create local
  fork remain explicit actions. A confirmed prolonged outage can expose the fork
  action without repeatedly showing model-loading prompts.

No inference timeout, run identity, retry submission policy, server deployment,
Tailscale configuration or cellular setting was changed. Wi-Fi was restored after
the tests. The temporary app packages, ADB reverse and test container were removed.

## Validation

The physical-device test runs the real MainActivity against stock Hermes
HTTP/SQLite behind a disposable fault proxy. Its synthetic worker produces no
live model requests. It verifies plugin snapshot list recovery; a JVM regression
also exercises recovery through the native session-list route.
During a controlled 30-second outage it interrupts the live event transport,
finishes the original worker while the phone is disconnected, and taps New chat.

Connection status recovered **510 ms** after the outage ended. The saved reply
appeared in its original conversation. There was exactly **one submission and one
execution**, zero local forks, and no local model load, even with automatic
loading explicitly enabled for this test. New chat surfaced its remote failure
without changing the current conversation. The failed creation notice remains
visible until the user acts again; reconnect does not replay that action.

All **903 Android JVM tests passed**, with one optional live-server test skipped.
Screenshot verification passed without changing baselines; lint reported zero
errors. Focused regressions cover isolated timeouts, repeated network flapping,
30-second and prolonged outages, authentication/TLS/rate-limit errors, explicit
loading preferences, reconnection UI and stale-list recovery.

[Machine-readable validation](evidence/connection-reliability-20261115/validation.json)
records the timings, test counts and limits. Its directory also contains the raw
timing samples, device result and build log.
[Recovered conversation](evidence/connection-reliability-20261115/recovered.png).

## Reproduction and limits

`ConnectionDeviceTest` accepts an explicitly provisioned private
`files/connection-audit.json` with `url` and `key` in the disposable
`org.praxisog.hermes.fixture` sandbox. It deletes that file immediately, performs
only authenticated GET probes, and writes content-free timing records. Runner
arguments `samples` and `scenario` select the bounded sample count and output
label. Never place a real credential in repository files, shell arguments or logs.

`tools/connection-device-fixture.py` runs in the stock Hermes fixture image
`2fd023efbb8d`. It listens on loopback port 18664, proxies a disposable host on
18665, and exposes only synthetic fault controls. Mount the current plugin at
`/opt/hermes/plugins/bellpull` and tools at `/connection-tools`, then run
`/opt/hermes/.venv/bin/python /connection-tools/connection-device-fixture.py`.
Build with `-PbellpullDeviceFixture=true`, reverse port 18664 through ADB, and run
`ConnectionRecoveryDeviceTest`. The native fixture uses a fresh temporary home.

This audit does not establish reliability during carrier tower handoff, driving,
dead zones, prolonged background/Doze, or all server loads. The exact reported
30-second cellular interruption remains unlocated. The source-confirmed fallback
amplification is repaired and controlled outage recovery is verified; the network
itself is not claimed fault-free. No live-model speed or throughput claim is made.

The connection changes require no server plugin update. This local candidate also
contains the earlier priority repairs; the earlier background-result delivery
repair still requires its matching 0.9.1 plugin. No release has been published and
the personal phone installation has not been upgraded.
