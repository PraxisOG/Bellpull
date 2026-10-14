# Bellpull 0.8.3 — experimental app and plugin release

Android **0.8.3-integration-preview / build 22** is paired with Bellpull plugin
**0.4.1-integration-preview**. GitHub tag:
[`android-v0.8.3-integration-preview.1`](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.8.3-integration-preview.1).
This is an experimental prerelease with known unresolved audit findings.

## Changes since the 0.8.1 experimental release

- Voice startup, focus interruption and echo handling share a recoverable playback
  queue. Read aloud explains when conversational voice must be ended first.
- Independent tool, web-search, thinking and working announcement settings;
  activity announcements no longer replay preceding response prose.
- Complete long narration without the former cumulative playback cap. Pocket and
  Qwen synthesize one phrase ahead to reduce gaps; benchmark candidates remain
  excluded from the normal release.
- Direct About button, accepted-send keyboard dismissal, and global conversation
  pins, including Unfiled chats. Global pins need plugin 0.4.1.
- Bellpull voice instructions collapse behind a tappable **Bellpull voice enabled**
  row while preserving the complete submitted and stored prompt.

Implementation and earlier isolated phone acceptance are recorded in the
[issue repair record](EXPERIMENTAL-ISSUE-REPAIRS.md) and
[voice disclosure record](VOICE-PROMPT-DISCLOSURE.md).

## Downloads and installation

| Asset | Contents |
|---|---|
| `Bellpull.apk` | Signed 0.8.3/build22, using the existing upgrade signing lineage |
| `bellpull-plugin-0.4.1-integration-preview.tar.gz` | Complete plugin, installer, license, installation/API documentation and audit evidence |
| `Bellpull-source-0.8.3.tar.gz` | Tracked project source plus pinned Qwen, nested GGML and Sherpa trees; includes `SOURCE-REVISIONS.json` |
| `Bellpull-Phase-Roadmap.md` | Current phase roadmap |
| `SHA256SUMS` | Checksums for the four assets above |

Install the APK as an in-place update; keep existing app data and signing identity.
A phone already on build22 with the checksum below does not need reinstallation.
For the plugin, unpack the archive and follow `plugins/bellpull/README.md`: back up
the actual Hermes data home, preview/apply the bundled installer, enable the single
Bellpull plugin, restart the existing gateway and reconnect the app. No personal
server deployment is performed by publishing this release.

Use the complete source archive or clone the release tag recursively. GitHub's
automatic source archives omit submodule contents. See [build instructions](BUILDING.md).
Model weights, credentials, signing keys, untracked personal files and build caches
are excluded from release bundles.

## Installed APK verification

On October 14, ADB reported `org.praxisog.hermes` version
`0.8.3-integration-preview`, code **22**, installed on the Pixel 10 Pro Fold.
The APK pulled from the installed package matched the release APK byte-for-byte.
No reinstall or app launch was needed; the user's running/stopped state was left
unchanged. No personal server or calendar data was accessed.

APK SHA-256:

```text
6e34b41c29926b4aae5db08857d5c5941617deaed94e52472e04e43f38832257
```

Signing certificate SHA-256:

```text
c1211fd4dbb2b4c0710fe35858bbc4d19eb2d2d27abb18eb5ad51e8192db55bf
```

The APK's signature, package/version metadata and 16 KiB ZIP alignment passed fresh
verification. These are the same signed bytes previously installed and tested;
publication changes documentation and packaging, not app/plugin runtime code.

## Validation and limits

The October 14 audit reran Android checks: **574 passed, one expected live-server
skip**, **172 screenshot baselines**, debug/release builds and lint. The full plugin
suite had **126 passes and one expected skip**; supported stock-host suites passed.
GitHub [run 38533375295](https://github.com/PraxisOG/Bellpull/actions/runs/38533375295)
passed both jobs for the audited product source.

The subsequent documentation-only audit commit's
[first CI attempt](https://github.com/PraxisOG/Bellpull/actions/runs/38536700756/attempts/1)
passed the plugin job but timed out in `ProjectsScreenshotTest.projectViewUnfoldedDark`
and `VoiceStoreTest.confirmedInterruptionCallsTheExistingStopEndpointBeforeOneReplacementAdmission`.
The failure is retained as evidence; it is not a passing check. All **19 focused
local tests** passed, and the [second CI attempt](https://github.com/PraxisOG/Bellpull/actions/runs/38536700756/attempts/2)
passed Android, with the plugin job's earlier success retained. No source changes
were made to obtain that retry result. The intermittent failure remains recorded;
this release does not claim its timing cause has been fixed.

Archive validation compares every bundled tracked file to its exact Git blob,
including all native submodule trees. The extracted plugin installer passed both
preview and apply against a disposable home, and its installed files matched the
bundle. The release APK checksum remains identical to the installed phone APK.

**The six reproduced defects in the [project audit](AUDIT-2026-10-14.md) remain
unfixed.** The high-priority findings are Git clean-filter execution during a
workspace preview and an in-flight calendar upload restoring data after revocation.
Other findings cover private-transcription URL validation, proposal retention,
workspace hard links and calendar title normalization. Host dependency advisories
also remain open. Current plugin/version documentation is updated for this release.

Physical car/headset/call audio routes, real provider/OpenCode workflows and cloud
calendar sync still need acceptance. Passing automated checks does not resolve
these limits or turn this experimental build into a stable release.
