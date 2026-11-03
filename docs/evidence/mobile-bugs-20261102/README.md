# Mobile bug repair acceptance

Local date: November 2, 2026. Source base: `6efc813`; integration branch:
`fix/mobile-bugs-20261102`. App/plugin version fields still identify the 0.19.0 /
0.8.3 development base; this is not a new published release.
Runtime repairs are committed through `0fc5644`; SHA256SUMS identifies the tested
fixture APKs. The final device run passes in 12.303 seconds.

## Server

The actual `hermes-gateway` container runs Hermes 0.21.5 from local image
`2fd023efbb8d`. A separate process inside that container loads this branch's
plugin on loopback port 18662 with a disposable data home and synthetic agent.
No real inference, main gateway restart, configuration edit or model change is
part of the acceptance test.

The focused regression suite uses the same image in a temporary container with
network disabled and the plugin mounted at `/opt/hermes/plugins/bellpull`:

```sh
python -m unittest \
  plugins.bellpull.tests.test_native_attachments \
  plugins.bellpull.tests.test_reasoning \
  plugins.bellpull.tests.test_artifacts \
  plugins.bellpull.tests.test_artifact_lineage \
  plugins.bellpull.tests.test_rediscovery \
  plugins.bellpull.tests.test_profiles
```

32 pass. On older local image `63bfb6d732f4`, attachment and rediscovery modules
run 14 checks: 13 pass, one live-desktop-owner test is skipped because the older
host does not have that feature. See the retained logs.

## Android

```sh
bash tools/build-local-lab.sh test verifyRoborazziDebug lintDebug --max-workers=4
bash tools/build-local-lab.sh :app:assembleDebug :app:assembleDebugAndroidTest \
  -PbellpullDeviceFixture=true --max-workers=4
```

729 app tests, 48 core Hermes tests and 37 core model tests pass. One opt-in
live-server test is skipped. Screenshot verification and lint pass. New SVG and
noise controls are captured at cover and unfolded widths; their images and the
updated local-settings baselines were opened for visual review.

## Physical phone

Pixel 10 Pro Fold, GrapheneOS Android 17, wireless ADB, unfolded display.
Only `org.praxisog.hermes.fixture` and its test package are installed for this
check. The separately installed normal app and Canvas fixture are preserved.

```sh
adb reverse tcp:18662 tcp:18662
adb shell am instrument -w \
  -e class org.praxisog.hermes.MobileBugsDeviceTest \
  org.praxisog.hermes.fixture.test/androidx.test.runner.AndroidJUnitRunner
```

The first attempt timed out in draft selection. The test setup was corrected
to supply explicit ClipData URI grants and await attachment capabilities before
tapping the destination. The corrected test passes: two shared files enter an
unsent draft, upload through real plugin endpoints, enter one native durable
run, reconcile into history once, expose a generated file, and render the SVG
with working zoom/reset controls. The agent is synthetic, so this does not
establish inference quality or live-provider behavior.

The first visual review also showed small SVGs were being enlarged from their
intrinsic bitmap dimensions. Previews now render at a bounded 2048-pixel size
(768 for thumbnails), including diagrams without a viewBox. Large, tiny and
extreme-aspect dimensions stay bounded.

- [Shared files awaiting Send](attachment-draft.png)
- [Native SVG preview](native-svg-preview.png)

Both phone images were opened and visually inspected. Test-created MediaStore
files are deleted by the test. The temporary process/home, reverse mapping and
this task's disposable phone packages are removed after evidence collection.

Real car routing, navigation/call interruptions and acoustic noise rejection
remain physical acceptance work. No issue is closed based only on these tests.
