## Current development release — 0.8.5 / build 26

The experimental line is **Android 0.8.5-integration-preview/build26** with
**plugin 0.4.3-integration-preview**, published under
`android-v0.8.5-integration-preview.1` from `repair/qwen-asr-server-stt`
(master 0.8.4 merged in, then version bump). Historical releases are retained
for reference and rollback.

The release contains the signed `Bellpull.apk`, complete
`bellpull-plugin-0.4.3-integration-preview.tar.gz`, recursive
`Bellpull-source-0.8.5.tar.gz`, phase roadmap and `SHA256SUMS`. The source
archive includes pinned native submodules and `SOURCE-REVISIONS.json`. It
excludes model weights, credentials, signing material, build caches and
untracked personal files. Use the [build guide](BUILDING.md) or the
[plugin installer](../plugins/bellpull/README.md).

New since 0.8.4: MOSS-TTS-Nano on-device engine, server-offload voice routes
(`GET /api/bellpull/speech/health`, `POST /api/bellpull/speech/tts|stt`,
capability `bellpull_speech`, plugin 0.4.3) proxied to the loopback-only
speech helper, and the fix for the MOSS on-device load crashing the process
(complete ORT 1.23.2 native libs vendored; `Throwable`-level catches in voice
output). Master's audit consolidations from 0.8.4 are included via merge.

Validation: CI on the pushed branch; plugin suite in the pinned legacy image.
Signed APK shares the published signing lineage (cert
`c1211fd4dbb2b4c0710fe35858bbc4d19eb2d2d27abb18eb5ad51e8192db55bf`), so it
upgrades the installed app in place.

See the [October 15 reliability audit](EXPERIMENTAL-ISSUE-REPAIRS.md#october-15-re-audit)
for ten closed issues and six remaining open issues. Physical car/headset routes,
sustained speech heat, pin touch behavior, title recovery and provider/cloud
acceptance remain explicit limitations. This remains a development preview.

## Historical experimental release — 0.8.3 / build 22

The user authorized publishing Android 0.8.3-integration-preview/build22 with
plugin 0.4.1-integration-preview. ADB confirms the installed APK is exactly the
signed release artifact; no reinstall was needed. See the
[0.8.3 release record](RELEASE-0.8.3-EXPERIMENTAL.md) for checksums, installation,
known unresolved audit findings and CI results. Source is on
`repair/experimental-open-issues`; tag `android-v0.8.3-integration-preview.1`.
Older version and deployment statements below are historical records.

## Local combined integration preview — 0.8.1 / build 20

`integration/phase6-8-9` combines the independently tested OpenCode and planning
branches. App `0.8.1-integration-preview`, plugin `0.4.0-integration-preview`.
Signed local artifacts are in `build/releases/0.8.1-integration-preview/` with
`SHA256SUMS`. The user authorized publishing the matching
[experimental prerelease](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.8.1-integration-preview.3),
including the phase roadmap. This is not a stable-release declaration.
The APK uses the existing signer/rotation lineage; signature, version metadata
and 16-KiB ZIP alignment were checked. SHA-256:
`84069d410f42cefea93dea8d53f03475e89aed335f767fd747c1e81bab86d92b`.
Local checks and remaining CI/provider limits are in
[the combined handoff](INTEGRATION-6-8-9.md). ADB was returned October 13 and the
combined fixture passed 11 final device-test executions, including both widths and
the real calendar permission flow. The initial navigation-harness timing failure
was fixed; production APK bytes are unchanged. Fixture cleanup is complete.
The user then requested the latest personal app. Signed build20 was installed
over corrected 0.5.5 with the same certificate, UID10260 and CE/DE data-directory
inodes 117766/116002. The previous installed APK was copied to `personal-upgrade/`
first; installation and package verification logs are beside it. The app was left
stopped for the user to open. No personal plugin deployment, personal-server
access occurred during installation. Subsequent GitHub publication is separately
authorized; preview app code remains on `integration/phase6-8-9` and the default
branch receives only the front-page/roadmap documentation update.

## Corrected 0.5.5 installation — 2026-10-12

The first 0.5.5 APK from `c5dceec` crashes on launch: the shipped Qwen checkpoint
ID `qwen-0.6b` was rejected by the new folder validator. **Do not use that artifact**
(SHA-256 `42c5d4ff4c943bdef7f36bcd064491b374602c9d5fa77143d54e4762a7c847cb`).
Fix `c913243` preserves the ID/downloads and permits the safe decimal dot. Eight
focused JVM tests and one isolated real-phone fixture startup/preflight/Activity
check passed. The corrected signed 0.5.5/code 17 APK was installed in place:
SHA-256 `1e7121c0a32658ded36fcb02122b7b581f8c5dd0a8993646b0f25f28f20415d5`.
Signer, UID 10260 and both data-directory inodes were preserved. Personal app was
left stopped; fixture packages removed. No personal server was accessed.
This startup check does not verify Pocket synthesis, audio routes or provider use.
Later local baseline/API-level guard corrections are in `feature/local-voice-models`;
Phase 6 and Phase 8/9 previews inherit them and remain independent branches.
No preview installation, push, merge or release publication is implied.

# Building and upgrading Bellpull

The current `feature/voice-turn-taking` branch builds **0.5.3, version code 15**.
Tap the microphone to start/end conversation mode; hold and release to dictate into
the message draft. The active button pulses, and a brief chime signals microphone
readiness. Settings → Voice → Using voice documents the controls; Debugging voice
interface restores the previous panel. See [voice behavior and limits](PHASE-5-VOICE.md).
No plugin update, new dependency or provider thinking-control change is required.
The current experimental repair permits complete long narration; Brief voice responses
is an optional style preference that honors explicit requests for detail. Live activity
announcements use actual events, and recognized non-echo speech requests the normal
chat Stop before one replacement admission. Completed runs still syncing history are
allowed to reconcile without a redundant Stop. Failures/30-second timeout keep new
words for review instead of waiting indefinitely or resubmitting the interrupted run.

0.5.3 validation passed **501 local tests**, one opt-in live-server skip, **158
screenshot checks**, debug/release builds, lint and five isolated unfolded-phone
checks. See [the current verification record](PHASE-5-VOICE.md#verification-of-053-voice-controls).
The signed APK passed signature and 16 KiB ZIP-alignment verification. SHA-256:
`e42c207a9bd8535a6ca13b35bf6b0266355d1f4f6e8bf5450f1087b2afd16d82`.
It was installed over 0.5.2 with the same signing lineage, UID and data-directory
inodes; personal data was not cleared and the personal app stayed stopped. No
personal Hermes/provider call was made. Model selection and downloads were not
changed. Test packages, our two reverse ports and local fixture servers were removed.
Signed delivery and prior rollback APKs remain under ignored `build/releases/0.5.3/`
and `build/releases/0.5.2/`; logs are under `build/speech-lab/voice-controls-*` and
`build/speech-lab/personal-upgrade/0.5.3/`. Do not uninstall or clear app data to
roll back; Android's version-code downgrade restrictions still apply. No public
release was published. Real acoustic/provider acceptance remains open.

The previous local-speech branch built **0.5.2, version code 14**, from the Phase 5
foundation. See [local speech status and setup](LOCAL-SPEECH-LAB.md) and
[Phase 5 acceptance](PHASE-5-VOICE.md). No plugin update is required for voice;
download Moonshine for local CPU recognition, or explicitly select your own private
transcription service. Answers use the phone's installed offline voice. Phone
recognition, native switching/local review and thermal comparisons passed in the
isolated fixture app; [results](../benchmarks/speech/pixel-10-pro-fold-2026-10-11/README.md)
include limitations. 0.5.2 disables Start voice until the selected local model is
installed and model operations have finished. It checks files again before permissions,
service startup and recognizer creation; an unloaded installed model can still start.
Missing/incomplete/deleted-file regression tests and all three disabled voice modes
are covered. Private-server mode remains independent of local downloads.
Local validation passed 481 tests with one opt-in live-server skip, all 156 screenshot
baselines, debug/release builds and lint. Signed APK SHA-256:
`a681d69a0d326bd914d365907fea3ec0021d0f399acfeafc1cffb748b2eb2fd6`.
Local signed APK/rollback copies are under ignored `build/releases/0.5.2/`,
`build/releases/0.5.1/` and
`build/speech-lab/personal-upgrade/`. No release was published; do not test against
the personal Hermes server.

Two isolated phone regressions passed: real taps cannot start any of the three voice
modes without a model, and About reports the installed version. The signed 0.5.2
update was installed over 0.5.1 with the same signing certificate, UID and data
directory inodes. The personal app was left stopped and fixture apps removed.
No personal Hermes/provider call was made. Local logs for this patch are under
ignored `build/speech-lab/voice-start-guard-*.txt`; the 0.5.1 benchmark data above
remains the measured recognition/thermal evidence.

Isolated voice checks use `tools/voice-device-fixture.py` on loopback 18647 and
`mock-hermes/server.py` on loopback 18646 with `MOCK_HERMES_KEY=voice-device-hermes-key`.
These are fake inference/transcription, never deployment services. Build the fixture
APK/test APK with `-PbellpullDeviceFixture=true`, install only those for testing,
forward both ports with ADB, and explicitly select `VoiceDeviceTest` and
`ProjectsUiDeviceTest`. The latter checks drawer filters, About and keyboard Save. The current unfolded
run passed; the cover attempt timed out behind keyguard. Both screenshot widths
passed. Never run the full device suite on the personal app.

The public download remains 0.3.3 preview. The Phase 4 branch builds **0.4.0,
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

## 0.5.4 local voice-output snapshot — not installed

Signed APK `build/releases/0.5.4/Bellpull.apk`, SHA-256
`8341e1830a4bc8225e9c7acbbeaed5af3adb93cbc9fa86555e722197ca4a5e22`.
Signer SHA-256 `c1211fd4dbb2b4c0710fe35858bbc4d19eb2d2d27abb18eb5ad51e8192db55bf`;
signature and 16-KiB zip alignment verified. Local test/build evidence is in
[VOICE-OUTPUT.md](VOICE-OUTPUT.md). The phone disconnected before installation,
so personal app remains 0.5.3/code 15. Fixture package/test APK and Qwen weights
remain to be cleaned up when ADB returns; never remove the personal app.
No push/release publication is authorized until user acceptance/green light.

## 0.5.5 local candidate — installed 2026-10-12

Version 0.5.5/code 17 adds Pocket CPU synthesis/model lifecycle and audio-route/
focus recovery. Debug and signed release builds compile ARM64 and x86_64. On the
user's October 12 request, Phase 5 commit `c5dceec` was signed and installed over
0.5.3 using the existing lineage. Signer, UID 10260 and both data-directory inodes
are preserved. Personal app left stopped; no server access. Fixture packages and
their test weights removed. No fresh functional tests, screenshots or benchmarks
were run; Pocket/recovery acceptance remains pending. Nothing pushed or published.

Signed APK `build/releases/0.5.5/Bellpull.apk`; SHA-256
`42c5d4ff4c943bdef7f36bcd064491b374602c9d5fa77143d54e4762a7c847cb`.
Signature and 16-KiB ZIP alignment verified; install/package evidence is under
`build/releases/0.5.5/personal-upgrade/`. The Phase 6 prototype is excluded.
See [VOICE-OUTPUT.md](VOICE-OUTPUT.md) for pending acceptance.
