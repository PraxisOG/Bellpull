# Building and upgrading Bellpull

The public download remains 0.3.3 preview. This Phase 4 branch builds **0.4.0,
version code 11**, paired with **Bellpull plugin 0.3.0**. It starts at the pushed
0.3.7 repair commit `743dbe4`. Phase 4 remains a development branch, not a public release.
Do not reuse a version code after release.

The user returned ADB access and authorized installation, testing, fixes and a
branch push. The signed 0.4.0/code 11 update was installed over 0.3.7 with the
existing lineage and the same Android UID, without clearing data or uninstalling
the personal app. That app was kept stopped; personal-profile decoding and the
actual server/provider remain user acceptance. The personal Hermes server remains
off limits. See [Phase 4](PHASE-4-PROJECTS.md) for verification and its acceptance
checklist. Older delivery evidence below describes the completed 0.3.7 work.

For the next isolated device acceptance, start a fresh disposable stock fixture:

```bash
python tools/stock-device-fixture.py --hermes-source /PATH/TO/STOCK/HERMES --projects
./android/gradlew -p android -PbellpullDeviceFixture=true :app:assembleDebug :app:assembleDebugAndroidTest
```

For isolated testing, install only the separate fixture application/test APKs,
forward port 18646, and select `org.praxisog.hermes.ProjectsDeviceTest` explicitly.
The test is guarded against the personal app package. It checks encrypted cache
restoration, drafts, separate profile cache, project creation/moves/pin/new chat,
native branching and real compaction. No provider call is needed. The authenticated
`/fixture/projects/...` controls exist only in the disposable helper, never in
the shipped plugin. Restart the fixture before repeating the test. Select
`ProjectsUiDeviceTest` separately for real keyboard/Save touches and About
version/status checks at each Fold display state. It uses public Android APIs;
the current Espresso InputManager hook fails on Android 17 before executing a
Compose device test. JVM Compose screenshot/interaction tests remain in use.

0.4.0 device acceptance passed eight test executions (six distinct checks):
files and project persistence, two UI checks at each width, conversation sources,
and the 0.3.7 initial-stream/compaction/restart regression. Temporary display
overrides, port forwarding, fixture apps and local servers were removed afterward.

Build personal-app updates using `python3 tools/release.py build`, preserving
the same signing lineage. Compilation/signature verification alone is not device acceptance.

The completed 0.3.7 manual test delivery paired that APK with **bellpull plugin 0.2.2**.
Use the stock installation instructions in `plugins/bellpull/README.md`; Docker
and `server-host/` are not required for persistent generated-file sharing. The
user manages the personal server and has explicitly prohibited agent access.
Phone checks use `-PbellpullDeviceFixture=true`, which produces the separate
`org.praxisog.hermes.fixture` package and cannot load personal app profiles.
The preview-to-release signing transition was verified on the Fold. Use the same
signing lineage for the 0.3.7 update. The user authorized ADB installation and
isolated fixture acceptance of 0.3.7; the personal Hermes server remains off limits.
See [the audit repair report](AUDIT-0.3.6-REPAIR.md) for the final evidence.

0.3.6 fixture acceptance passed on the unfolded Fold with stock non-Docker Hermes:
all conversation sources, growing thinking, interim prose/tools, saved completion
and byte-identical PNG downloads. The cover-state attempt reached the lock screen;
its override was reset. Both widths pass JVM screenshot checks. See `HANDOFF.md`
for the exact evidence and remaining actual-provider/upgrade acceptance.

To reproduce the isolated streaming fixture, run the helper with a disposable
stock source checkout and its Python environment:

```bash
python tools/stock-device-fixture.py --hermes-source /PATH/TO/STOCK/HERMES --streaming
./android/gradlew -p android -PbellpullDeviceFixture=true :app:assembleDebug :app:assembleDebugAndroidTest
```

Install only the fixture APK/test APK, forward USB port 18646, and select
`PortableFilesDeviceTest` and `ConversationSourcesDeviceTest` explicitly. Do not
run the whole instrumentation suite on a personal phone. The source test leaves
the fixture profile configured for the visible chat check; authenticated POSTs
to `/fixture/advance` release each reasoning/tool/reply step. These loopback test
controls never ship in the plugin. Remove both fixture packages and forwarding
afterward; leave personal Bellpull stopped throughout.

For the 0.3.7 audit regression, start a fresh fixture with `--audit-repair` instead
of `--streaming`, then select `PortableFilesDeviceTest`,
`ConversationSourcesDeviceTest`, and `AuditRepairDeviceTest` explicitly. The last
test drives its own authenticated fixture controls: first SSE request fails,
thinking/answer reconnect, the prompt is compacted, completion precedes the final
saved reply, and encrypted tracking is restored before reconciliation. It asserts
one inference and no stale pending prompt. Restart the disposable fixture before
rerunning it; the fixture intentionally admits only one inference per process.

The 0.3.7 personal-app install is a signed `adb install -r`, preserving the installed
app rather than clearing data. Do not launch the personal profile for these tests:
the personal Hermes server remains off limits. Actual saved-profile/draft access
must be checked by the user. See the audit report for device and signing evidence.

## Checks before a release

```bash
./android/gradlew -p android test verifyRoborazziDebug assembleDebug lintDebug
docker build -f server-host/Dockerfile -t bellpull:checks .
docker run --rm --network none -e HERMES_HOME=/tmp/bellpull-tests \
  -e BELLPULL_NTFY_URL= --entrypoint python bellpull:checks \
  -m unittest discover -s /opt/hermes/plugins/bellpull/tests -v
```

CI runs these checks without signing secrets, production mounts or inference.
Inspect intentional screenshot differences before updating baselines. Check cover,
unfolded, dark/light and large-text layouts. Test the signed APK on the Fold;
rendered screenshots cannot establish actual upgrade, fold or network behavior.

## Stable signing

The published preview certificate SHA-256 is
`b8ff7d02769b42f0a41ab922334875bfc2766086c78b2caa581ba5f5b6da53cf`.
Do not uninstall the app to get around a certificate mismatch: that loses private
connections, drafts, read state and recovery records.

```bash
python3 tools/release.py init
python3 tools/release.py build
```

`init` is idempotent. It verifies the original preview certificate, snapshots its
key, creates a dedicated RSA-4096 release key, and constructs an Android signing
lineage. Secrets live in `~/.config/bellpull/signing/` (directory 0700, private
files 0600), outside Git. The password is randomly generated and is not printed.
Keep this whole directory, including the lineage and preview key, in a secure
offline backup before publishing. No backup to a second device has been made by
this repository's build scripts. Losing the release key prevents future updates.

The script zipaligns and signs `android/app/build/outputs/apk/release/Bellpull.apk`,
then verifies the signature and writes `SHA256SUMS`. Rotation targets API 28+, so
the lineage applies to all supported devices (Bellpull's minimum is API 29).
The old key also supplies compatibility signatures. See the official
[apksigner rotation contract](https://developer.android.com/tools/apksigner).
Future release builds must use this script and the same signing directory;
Gradle's optional `keystore.properties` signing alone does not attach the lineage.

Before publishing, install over the existing preview with `adb -s SERIAL install -r
Bellpull.apk`. Verify the same profile, drafts, encrypted settings and chat cache
remain accessible; restart the app and check run recovery. A debug APK cannot be
assumed to update a release-signed installation. Use the stable signing path for
subsequent upgrades. Keep a private data backup before a signing transition.

## Publication and rollback

After device acceptance and maintainer approval, tag the reviewed commit and
upload the signed APK with its SHA-256 and release notes. Never publish the
keystore, password, server configuration, bearer key, or private screenshots.
Publishing is a separate action; neither CI nor the release script performs it.

Keep the preceding APK and server image. Android normally rejects lower version
codes; prefer a fix with a higher code signed through the same lineage. Server
rollback is described in [the plugin migration guide](../plugins/bellpull/README.md).
