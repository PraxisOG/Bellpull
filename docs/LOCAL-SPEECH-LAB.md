# Local speech experiments — work in progress

Branch: `feature/local-speech-lab`, based on `66ceb2d` (0.5.0).
The user's October 11 addendum supersedes the earlier “Large” request: the first
three models are **Moonshine Tiny, Small and Medium Streaming**. Finish their CPU
integration and baseline measurements before additional models or accelerator ports.

## Current state

Settings has collapsible categories, with Display separate from Appearance and
private speech configuration under Voice. The experimental model UI, verified
download manager, exclusive model owner and benchmark runner are implemented.
**Moonshine Voice CPU SDK 0.1.5 is approved and packaged, but the experimental
screen is not yet connected in MainActivity and local recognition is not operational.**
The user requested a pause immediately after adding the SDK and reclaimed the phone.
Do not use ADB or continue native integration or benchmarks until resumed. No plugin
update is required for local recognition; Bellpull plugin 0.3.0 can continue serving
the existing remote chat interfaces. The separate question about standalone Google
LiteRT libraries concerns a later optional TPU experiment.

| Checkpoint | Quantization | Download bytes | Runtime | Actual backend / phone results |
| --- | --- | ---: | --- | --- |
| Moonshine Tiny Streaming English, `quantized_26_08_21` | INT8 per-channel `.ort` | 45,233,659 | Moonshine Voice 0.1.5 packaged; adapter pending | Paused before native integration; no phone recognition measurements |
| Moonshine Small Streaming English, `quantized_26_08_21` | INT8 per-channel `.ort` | 142,300,974 | Moonshine Voice 0.1.5 packaged; adapter pending | Paused: same |
| Moonshine Medium Streaming English, `quantized_26_08_21` | INT8 per-channel `.ort` | 269,141,623 | Moonshine Voice 0.1.5 packaged; adapter pending | Paused: same |

The pinned eight-file sets and upstream CRC32C values are in
`android/app/src/main/assets/speech-models.json`, sourced from upstream commit
`234f60faa0eb388b01cdf7e60aca232af37aefda`. Optional word-timestamp decoder weights
are not part of these downloads. Their costs must be reported separately if added.
Files live in app-private, backup-excluded storage. Completed files survive a failed
download; an interrupted individual file restarts. The installed marker is published
only after every file passes size/checksum validation. Loading re-verifies bytes.
Deleting removes only the selected model's owned installed/partial directories.

Pinned runtime artifact added to the project:
`ai.moonshine:moonshine-voice:0.1.5`, AAR SHA-256
`ee2d95c21150683c743db8f3aef66281fd5408bcefc94be3ca1d2545ada1f571`.
Bellpull removes its optional microphone permission Activity and retains the existing
capture/permission lifecycle. Unused Google Material and SDK WorkManager transitives
are excluded; required AndroidX types remain. Upstream and native third-party license
notices are retained in `third-party/` and packaged under `assets/licenses/`.
The local build and lint pass with the pinned SDK; native loading is not device-tested.

Sources: [Moonshine Android SDK](https://github.com/moonshine-ai/moonshine/tree/234f60faa0eb388b01cdf7e60aca232af37aefda/android),
[model documentation](https://moonshine-voice.readthedocs.io/en/latest/models/available-models/),
[SDK artifact](https://repo.maven.apache.org/maven2/ai/moonshine/moonshine-voice/0.1.5/moonshine-voice-0.1.5.aar).

## Measurement protocol

Use exactly the same PCM16 mono 16 kHz WAV files for every recognizer. Feed samples
directly, never through playback/capture. Maintain separate cases for clean human
speech, speech mixed with recorded road traffic, measurements/numbers, and natural
pauses. Retain source IDs, licenses, verified reference transcripts, audio hashes,
mix level and any timing annotations. A script or TTS prompt alone is not a verified
transcript. An automatic alignment is an estimate and must be labeled accordingly.
The four WAVs have been prepared with source-checked transcripts. See
`benchmarks/speech/README.md` and the pinned fixture manifest for regeneration,
licenses and limits. No recognizer fixture-suite pass is claimed.

For each case and model:

1. Unload the previous model before loading the next. Record verification plus native
   model-load time separately from recognition. “Cold” here means unloaded model;
   OS file caches are not flushed. Also capture first process/library initialization
   separately when the native adapter becomes available.
2. Run maximum-throughput whole-file recognition, followed by three repeated warm
   runs. Report audio seconds / processing seconds; do not include model loading in
   throughput. Record native settings and thread count.
3. Repeat with 100 ms chunks paced to their actual arrival times. Record first
   nonempty partial, annotated last spoken word → corresponding final transcript,
   early completed lines and whether file-end flushing forced finalization.
   A completed line is only a candidate turn boundary; it is not proof that the
   complete microphone/agent turn flow ended prematurely.
4. Record lexical WER (NFKC, lowercase, punctuation normalization, apostrophes and
   decimals preserved). Numbers and units are not silently rewritten. Include actual
   transcripts so formatting errors can be separated from recognition errors.
5. Record process PSS/RSS sampled every 250 ms, including loading. These are sampled
   maxima, not instantaneous allocation peaks or isolated model memory.
6. Run at least three minutes of repeated recognition after warm-up, with thermal
   monitoring. Cool to the same baseline before comparisons. Fix charging, display
   brightness and fold posture, record them, and restore any temporary settings.
   Stop at Android thermal status SEVERE or above. A native call may finish before
   cooperative cancellation takes effect; never free its model concurrently.

Imported WAVs have an optional manually supplied last-word boundary. Without one,
last-word latency is unavailable. Forced finalization is reported separately. The
runner retains completed results if stopped and writes app-private, timestamped
`noBackupFilesDir/speech-benchmarks/*.json` after each run. WAV hashes identify
the exact input; later comparisons do not overwrite earlier batches. The three-minute switch
adds sustained runs; it does not claim a completed thermal test by itself.

The app's thermal snapshot records Android thermal status, thermal headroom
(at most once per ten seconds, with sample age), battery temperature and plugged
state. Unavailable/NaN headroom remains unavailable.

External ADB monitoring, without changing charging/display/posture:

```sh
python3 tools/speech_thermal_monitor.py \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial PHONE_SERIAL \
  --output build/speech-lab/thermal.jsonl --seconds 240
```

The monitor uses **Current temperatures from HAL**, never Cached temperatures.
It keeps exact sensor names and HAL types, labels vendor non-temperature quantities
separately, and treats invalid sentinel readings as unavailable. At severe status
it stops only `org.praxisog.hermes.fixture`. Never substitute the personal app ID.
ADB monitoring is supplementary: use the app API for thermal headroom.

## Evidence so far

- **Measured on this phone:** read-only idle thermal/logging smoke check, not ASR.
  Pixel 10 Pro Fold, GrapheneOS/Android 17, OPENED posture, USB connected,
  brightness setting 7 with adaptive brightness enabled. Thermal status 0;
  battery approximately 26.5°C, VIRTUAL-SKIN approximately 28.34°C, BIG/LITTLE
  approximately 31°C. GPU/TPU readings unavailable. Adaptive brightness must be
  fixed and restored for actual comparisons.
- **Implemented and checked locally:** model installation integrity/recovery/deletion,
  CRC32C compatibility fallback, strict WAV parsing, lexical WER, model reuse,
  unload-before-switch, failed-switch/cancel cleanup, settings organization and
  cover/unfolded screenshots. `test verifyRoborazziDebug assembleDebug lintDebug`
  passed: 461 tests passed and one opt-in live-server test skipped; 154 screenshot
  baselines, including a repeat after adding the SDK. The APK contains its arm64
  native libraries and matching license assets; its merged manifest excludes the
  SDK permission Activity. Two thermal-parser Python tests passed. The corpus generator completed
  for all four files and verified references against the primary dataset.
  Rerun checks after final native integration.
- **Implemented but untested on hardware:** the benchmark runner's timing, PSS/RSS,
  app thermal-headroom sampling and sustained-run controls. Native recognition,
  app model downloads, switching and end-to-end offline dictation are unverified.
- **Paused:** all Moonshine phone recognition metrics until native integration and
  device testing resume. No cold-load, partial/final latency, WER, throughput or sustained
  thermal numbers are represented as measured.

## Later experiments

Keep model choice separate from backend choice. Initial integration offers CPU
only; native provider evidence must confirm the actual backend. A future GPU/TPU
path must report partial CPU fallback rather than claiming full acceleration.

Earmarked: IBM Granite Speech 5.0 TurboCTC; Whisper Tiny/Base/Small/Medium/Large
v3 Turbo; Qwen3-ASR 0.6B/1.7B; Parakeet TDT 0.6B v3; Parakeet Realtime EOU 120M.

[Google's LiteRT speech sample](https://github.com/google-ai-edge/litert-samples/tree/main/samples/litert/speech_recognition)
documents a Parakeet TDT Pixel 10 TPU route. That does **not** establish Realtime
EOU compatibility or compatibility with this phone's GrapheneOS vendor drivers.
Vulkan and TPU ports remain unimplemented and unmeasured.

Test EOU standalone first. If later paired with a separate transcriber, report
combined memory, processing, thermal cost and end-to-end latency. Expose only actual
runtime timing/threshold controls. Bellpull's microphone silence-pause control is
not a neural EOU sensitivity setting.

## Before delivery

- After the user resumes work, implement the native adapter for the added SDK.
- Wire Settings → Voice → Experimental and explicit local/private recognizer choice.
  Missing selected local weights must never cause silent off-device transcription.
- Preserve profile-scoped private credentials and drafts; offline STT does not make
  remote Hermes inference available offline.
- Run native repeated-stream tests, model download/restart/delete/switch tests, direct
  fixture benchmarks, sustained thermal comparisons and separate acoustic tests in
  the fixture app. Do not access the personal Hermes server.
- Replace the blocked table with measured per-fixture cold/warm/chunked results and
  identify exact runtime/checkpoint/backend. Keep unavailable metrics explicit.
- Run `./android/gradlew -p android test verifyRoborazziDebug assembleDebug lintDebug`;
  inspect screenshot differences. Install only a validated APK with the existing
  signing lineage. No merge or release publication.
