# Plugin rediscovery repair — #39

Plugin **0.8.3-reload-preview** includes the combined Kanban/Canvas source and
repairs [#39](https://github.com/PraxisOG/Bellpull/issues/39). Android production
code is unchanged; the published 0.18.2 client can use this repair.

The regression reproduces the old failure on stock Hermes 0.21.5: capabilities
initially returns 200, then forced discovery of an unrelated plugin leaves the
existing native HTTP routes returning 503. Hermes retains its frozen router and
deduplicates Bellpull's newly registered factory by name.

Bellpull now retains weak references to its HTTP bindings on the owning plugin
manager. Successful re-registration of the same bundle resumes those bindings
on their original event loop, after draining old observers and transports.
Routes, native sessions, run admission, and operation receipts are retained.
Bindings never cross manager/profile boundaries. Explicit unload immediately
revokes the routes; disabling the plugin in configuration prevents reactivation.

The complete Python/manifest/compatibility bundle and Bellpull service environment
are fingerprinted internally. Changed code or service configuration is not
hot-swapped into old routes: capabilities returns HTTP 503 with
`error.code=bellpull_restart_required` and `error.restart_required=true`.
The message requests a gateway restart when idle. Fingerprints and environment
values are not exposed to clients. A failed resource restart also fails closed.

Install the complete plugin and restart the gateway when idle **once to acquire
this repair**, including when the old plugin is already stranded. Rediscovery
alone cannot retrofit lifecycle handling into a previously installed router.
Thereafter, unchanged-bundle rediscovery recovers without a gateway restart.
Continue probing authenticated `/api/bellpull/capabilities` alongside native
health; native health alone does not establish Bellpull readiness.

Validation uses disposable homes and synthetic inference, without Moe access:

- Regression fails against source `66f3fd2` with the reported 503.
- Six native lifecycle tests pass on stock Hermes 0.21.1 and 0.21.5: repeated
  discovery, authentication, saved sessions, speech transport recovery, worker
  thread cleanup, one observer, explicit/configured disable, profile isolation,
  changed-bundle restart recovery, disable winning over queued reloads, and an active run retaining its ID and single
  execution through repeated submission.
- The full extension-host plugin suite runs 216 tests: 215 pass, one existing skip.
- CI runs the lifecycle tests on both pinned stock images.
- Physical Pixel 10 Pro Fold validation passes over wireless ADB (one test,
  7.6 seconds): three reloads, disable/re-enable, unchanged task/run IDs, and
  exactly one synthetic execution. The separate Canvas fixture was upgraded to
  0.19.0/build 48; the primary Bellpull installation remains 0.18.2/build 47.

The wireless Android regression uses `tools/plugin-reload-device-fixture.py`, a
loopback-only native API with disposable data and a blocked synthetic agent.
Build `:app:assembleDebug :app:assembleDebugAndroidTest` with
`-PbellpullCanvasFixture=true`, install the separate preview and instrumentation,
then forward `tcp:18659` with `adb reverse` over the wireless ADB connection.
Run `org.praxisog.hermes.PluginReloadDeviceTest` using AndroidJUnitRunner. It checks
the same task and run across three reloads, unavailable-state reporting after
disable, re-enable recovery, and one execution after the run is released.
Its control routes exist only in this fixture, never in the shipped plugin.

This change does not establish acceptance for the separate Canvas conversation
opening failure tracked in #38, owner-memory integration in #45, or real-car voice
behavior. No production update or GitHub release is implied by these tests.
