# Android 0.14.5 experimental: voice, media and reconnect fixes

October 22, 2026. Plugin remains **0.6.0-integration-preview**; this increment
changes the Android client only. Existing profiles, input model and output
selection are preserved. No personal-server or Moe configuration changes.

## Changes

- Kokoro 82M v1.0 CPU output: four US English voices; verified model download,
  preview, save, load/unload/delete and interruption. FP32 uses four CPU threads.
  Settings → Voice output → Kokoro 82M → Kokoro model → Download Kokoro, then
  Preview and Save voice output. Download totals **336,784,293 bytes**.
- Voice exit: phone logs identified Android rejecting a CallStyle notification
  after foreground-service teardown. Shutdown now fences/cancels the state
  observer before closing voice or removing the foreground notification. STOPPED
  never posts another call notification.
- OpenCode activity is under the conversation's top-right **More → OpenCode
  activity** menu. The Chat/Activity chip row is removed. Returning to chat and
  background task observation retain their existing behavior.
- PNG/JPEG/WebP artifacts preview inline. **Settings → Display → Image preview
  size** offers Off, Small, Medium and Large. Tap previews for the full viewer.
  Auto-fetch is capped at 8 MiB per image, downsampled to 768 pixels maximum edge,
  with 12 MiB memory/64 MiB disk caches. Authentication, byte count and SHA-256
  checks use the existing artifact endpoint. Loader and cache are profile scoped.
- Active replay and saved history share one response bubble for an identified
  durable turn. Saved prose prefixes are removed from the display projection;
  raw streaming data remains intact for speech/recovery. Notification-observed runs require server admission timestamps and a matching
  replay prefix. Separate or ambiguous repeated prompts are not deduplicated. Completed runs use persisted sync
  evidence or timestamp-fenced final-message evidence when compaction removed
  their original prompt.

## Physical phone measurements

Pixel 10 Pro Fold, GrapheneOS Android 17/API37; disposable fixture package.
All inference ran on CPU with the pinned ONNX Runtime 1.28.2 build. The same
4.9-second, two-sentence output was generated twice for each configuration:

| Model | Threads | Generation time, seconds |
|---|---:|---:|
| INT8 | 1 | 6.52, 6.44 |
| INT8 | 2 | 7.66, 7.45 |
| INT8 | 4 | 7.03, 6.58 |
| FP32 | 1 | 6.97, 6.95 |
| FP32 | 2 | 3.97, 3.83 |
| **FP32 (selected)** | **4** | **2.50, 2.26** |

Four-thread FP32 is **2.86× faster** than four-thread INT8 on this sample.
A separate five-phrase run across all four voices measured **RTF 0.50–0.53**,
with warm first PCM **0.78–2.72 s** and cold verification/load **4.53 s**.
See [raw format/thread comparison](kokoro-benchmark.json) and
[final FP32 results](kokoro-results.json). These include numerical runtime checks;
short samples do not establish broad pronunciation or sustained thermal quality.

The JNI adapter uses existing isolated ORT linkage; no added shared ORT, all-model
Sherpa AAR, eSpeak, Piper or Python runtime. FP32/voice weights come from
[ONNX Community](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX),
revision `1939ad2a8e416c0acfeecc08a694d14ef25f2231`. The
[Misaki G2P bundle](https://huggingface.co/nekwo/misaki-en-us-g2p-onnx), revision
`4b3a605d2f605318ab4f762ab9855c44106528b7`, supplies dictionaries and an ONNX neural
fallback. Apache-2.0 license and attribution are bundled; models stay outside Git.

## Validation

- Phone: all four voices, cold/warm reuse, unfamiliar-word G2P, numbers/currency,
  finite/nonempty PCM, native playback/drain, cancellation and subsequent reuse.
- Phone: Telecom call survives screen-off, pause/resume, notification hang-up,
  then three consecutive in-app closes with no surviving call/notification and
  normal audio mode restored. The process survived every close.
- Full plugin suite: **168 tests, one legacy skip**. Current stock Hermes coding
  integration: **25 tests, one skip**. CPU speech helper: **6 tests passed**.
- JVM/Compose: regression coverage for replay projection, compacted completion,
  no merging across turns, single live/saved bubble, artifact hash verification,
  thumbnail cache/bounds/profile closure, UI resizing/hiding/opening, settings
  persistence, menu navigation, Kokoro validation/normalization and chunking.
- Full Android checks: **725 JVM tests declared, 724 passed, one opt-in live-provider
  skip** (653 app, 45 client including the skip, 27 model). All **187 screenshot
  baselines verified**; 18 intentional settings/voice UI baselines were visually
  reviewed and updated. Lint: **0 errors, 79 warnings, 5 hints**.
- Both ARM64 and x86_64 release native libraries compiled. The signed build is
  **0.14.5-experimental / code37**. APK SHA-256:
  `b20e924e03673f3151c42d547d590dea7195fcbf1f3b7a2b6928cf95b9991f99`.
- The Telecom scenario also passed with **Kokoro selected inside VoiceService**,
  covering model preparation and repeated call teardown together.
- [Automatic speech round trip](kokoro-transcription-check.json): the local CPU
  Moonshine recognizer recovered the test phrases, including currency and an
  unfamiliar word. This is an intelligibility smoke check, not subjective listening
  or a pronunciation benchmark.

## Limits

Kokoro currently exposes US English only; without a POS tagger, words such as
“read” use their default dictionary pronunciation. Names may still be imperfect.
Speech starts after a phrase is synthesized, not after the first phoneme. Stop
immediately clears playback; CPU inference checks cancellation between native
operations/phrases. Bluetooth head-unit acoustics, real incoming-call arbitration
and long car sessions still require testing in the actual vehicle.

Thumbnail previews cover registered server image artifacts; arbitrary external
Markdown URLs are not fetched. Images above the automatic size limit remain
available through View/Download. No broad server-history cleanup or deletion of
repeated assistant messages was performed.

## Installed experimental build

Signed APK installed in place over 0.14.4/build36. ADB reports 0.14.5/build37,
unchanged UID **10308** and `/data/user/0/org.praxisog.hermes`. The installed base
APK's SHA-256 matches the release artifact above. Release signing certificate
SHA-256 is `c1211fd4dbb2b4c0710fe35858bbc4d19eb2d2d27abb18eb5ad51e8192db55bf`.
The previous installed APK was backed up locally and matches the prior release
hash. Personal preferences and model files were not cleared; Kokoro's separate
model download and selection remain available in Settings.
