# Settings action regression evidence — October 29, 2026

Build43 failed both physical regressions: the notification toggle reverted to off
with relay events absent, and Choose default assistant returned without opening
settings. `reproduced-build43.txt` records these expected failures.

Build44 passed the same controls test plus notification permission denial/recovery.
The runs use the disposable package on the Pixel 10 Pro Fold's 1080×2364 cover
screen, preserving density override 440. A loopback stock Hermes 0.21.5 fixture
advertised planning but no notification relay, matching the failing capability
combination. The tests verify actual taps, persisted settings, connection-service
start/stop, Android assistant settings launch, and permission recovery. They never
select a different assistant or use a personal server. Fixtures were removed.

`controls-device.txt` and `permission-device.txt` record three passing tests.
Screenshots contain synthetic fixture settings only. JVM: 763 passing checks,
one existing opt-in skip; all screenshots verified, lint and release assembly
passed. The only updated baseline is the notification settings view, reviewed
for the new Android notification settings button.
