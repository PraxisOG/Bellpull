# Local speech experiments — on-device CPU preview

Branch: `feature/local-speech-lab`, based on `66ceb2d` (0.5.0).
Current development APK: 0.5.3, version code 15; installed in place, not published.
The follow-up controls branch is `feature/voice-turn-taking`; it preserves the model
selection/downloads and adds tap-to-converse, hold-to-dictate, activity announcements
and chat Stop on confirmed interruption. See [voice controls](PHASE-5-VOICE.md).
The user's October 11 addendum supersedes the earlier “Large” request: the first
three models are **Moonshine Tiny, Small and Medium Streaming**. Finish their CPU
integration and baseline measurements before additional models or accelerator ports.

## Current state

Settings has collapsible categories, with Display separate from Appearance and
private speech configuration under Voice. The experimental model UI, verified
download manager, exclusive model owner and benchmark runner are implemented.
**Moonshine Voice CPU SDK 0.1.5 is connected to voice conversations and the
experimental screen in MainActivity. All three models completed native phone benchmarks and sustained CPU tests.**
The user resumed local integration after the SDK-only pause and returned ADB.
[Phone results and raw evidence](../benchmarks/speech/pixel-10-pro-fold-2026-10-11/README.md)
cover 206 benchmark measurements. No plugin
update is required for local recognition; Bellpull plugin 0.3.0 can continue serving
the existing remote chat interfaces. The separate question about standalone Google
LiteRT libraries concerns a later optional TPU experiment.

| Checkpoint | Quantization | Download bytes | Runtime | Actual backend / phone results |
| --- | --- | ---: | --- | --- |
| Moonshine Tiny Streaming English, `quantized_26_08_21` | INT8 per-channel `.ort` | 45,233,659 | Moonshine Voice 0.1.5 / explicit ONNX Runtime CPU | Measured on Pixel 10 Pro Fold |
| Moonshine Small Streaming English, `quantized_26_08_21` | INT8 per-channel `.ort` | 142,300,974 | Moonshine Voice 0.1.5 / explicit ONNX Runtime CPU | Measured on Pixel 10 Pro Fold |
| Moonshine Medium Streaming English, `quantized_26_08_21` | INT8 per-channel `.ort` | 269,141,623 | Moonshine Voice 0.1.5 / explicit ONNX Runtime CPU | Measured on Pixel 10 Pro Fold |

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
The local build and lint pass with the pinned SDK. Native loading, repeated recognition and app-managed downloads passed on all three models on the phone.

Sources: [Moonshine Android SDK](https://github.com/moonshine-ai/moonshine/tree/234f60faa0eb388b01cdf7e60aca232af37aefda/android),
[model documentation](https://moonshine-voice.readthedocs.io/en/latest/models/available-models/),
[SDK artifact](https://repo.maven.apache.org/maven2/ai/moonshine/moonshine-voice/0.1.5/moonshine-voice-0.1.5.aar).

## Local setup and compatibility

1. Open Settings → Voice. New profiles default to **On device**. Existing saved
   private-service configurations retain **Private server** until explicitly changed;
   their encrypted URL/key/model fields remain available when switching back.
2. Select Tiny, Small or Medium Streaming. The selected model and its tuning are
   device-wide; private service credentials and voice drafts remain profile-scoped.
3. Download the model. The UI shows its exact total download size, installed/partial
   status and retry/delete controls. Loading verifies its bytes again.
4. Choose **Load model**, or start voice to load it on the first captured utterance.
   **Unload** releases native model state without deleting the downloaded files.
   A switch unloads the old model before creating the new one. Model work stays off
   the UI thread. Closing/cancelling voice never frees a model during native inference.
5. Model settings expose the actual Moonshine VAD probability threshold and streaming
   update interval. Settings persist separately per model; saving unloads the current
   model so the next load applies them. The interval is a minimum and stretches under
   load; it affects chunked benchmark partials. Conversation recognition currently
   transcribes a bounded utterance after Bellpull's microphone pause detector finishes.
   Bellpull's end-of-turn pause and playback rate are separate conversation controls.
6. CPU is the only offered backend. The adapter passes `ort_providers=cpu`; the
   [pinned SDK documents CPU-only shipped providers](https://github.com/moonshine-ai/moonshine/blob/v0.1.5/docs/execution-providers.md).
   No GPU/TPU fallback is claimed. Native API version, tuning and SDK thread defaults
   are included in benchmark result metadata.

Start voice is disabled while the selected model is missing or a speech experiment
is busy. It shows the selected model's download instructions. The same preflight
checks actual file presence and sizes before each permission/service boundary and
recognizer creation, even if the cached installed status is stale. A downloaded
model may remain unloaded until its first transcription; no automatic download or
model substitution occurs. Checksum verification still runs on the loading worker.
Missing/corrupt local weights and native loading failures produce a local error;
they never invoke the private service. Local WAV recognition needs neither Hermes
nor a service URL/key. Sending a recognized prompt still needs Hermes. Selecting a
model or running experiments ends an active voice session and preserves saved text.
The native adapter uses fresh stream handles and its own completion deduplication
for each utterance, avoiding cross-stream reuse of upstream completed-line IDs.

## Measurement protocol

Use exactly the same PCM16 mono 16 kHz WAV files for every recognizer. Feed samples
directly, never through playback/capture. Maintain separate cases for clean human
speech, speech mixed with recorded road traffic, measurements/numbers, and natural
pauses. Retain source IDs, licenses, verified reference transcripts, audio hashes,
mix level and any timing annotations. A script or TTS prompt alone is not a verified
transcript. An automatic alignment is an estimate and must be labeled accordingly.
The four WAVs have been prepared with source-checked transcripts. See
`benchmarks/speech/README.md` and the pinned fixture manifest for regeneration,
licenses and limits. Phone result claims are listed separately below.

For each case and model:

1. Unload the previous model before loading the next. Record verification plus native
   model-load time separately from recognition. “Cold” here means unloaded model;
   OS file caches are not flushed. The first JNI library initialization is recorded
   separately and included in that model's loading time; later warm calls omit it.
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

## Verification and limits

- **Measured on this phone:** all three downloads verified; each model passed four
  identical clips in whole-file and paced 100 ms modes, with one unloaded-model and
  three warm runs per case, followed by at least 180 seconds of sustained work.
  There are 206 saved measurements. Exact checkpoints, INT8 quantization, runtime,
  explicit CPU backend, cold load, partial/final timing, lexical WER, early segment
  boundaries, forced flushing, PSS/RSS and thermal/headroom data are in the
  [results](../benchmarks/speech/pixel-10-pro-fold-2026-10-11/README.md).
  USB power/100% battery, manual brightness 9 and unfolded posture stayed fixed;
  each model began after cooling to the recorded common baseline. Maximum Android
  thermal status was 0. Peak HAL readings: `battery` 32.3°C, `VIRTUAL-SKIN` 35.66°C,
  `BIG`/`LITTLE` 52°C. GPU/TPU temperature readings were unavailable.
- **Native functional checks:** Tiny → Small → Medium → Tiny switching, saved tuning,
  deletion of Medium without deleting Tiny/Small, and direct captured PCM through
  the real local recognizer into unsent Review passed. App UID network counters
  did not change during that test. The real microphone released/restarted correctly;
  installed offline `en_US` playback and AEC enabled successfully. The default
  1.6-second Bellpull pause detector completed exactly one turn per corpus clip,
  with zero endings before the estimated last word. These detector results are
  separate from Moonshine's more frequent completed transcript segments. All four
  UI checks passed after fixing stale/offscreen accessibility handling in the test
  harness; the initial run passed seven of eight and the final UI rerun passed four
  of four. The personal app was updated in place to 0.5.1/code 13 with unchanged
  signing, UID and data directory inodes, and left stopped. Test apps/weights were
  removed; original display settings restored.
- **Local checks:** 477 tests passed, one opt-in live-server test skipped; all 156
  screenshot baselines verified. Debug build, signed release build, lint and fixture
  instrumentation compilation passed. Tests cover installation integrity/retry,
  strict WAV parsing, lexical WER, exclusive model reuse/switching, failed/cancelled
  native cleanup, explicit CPU options, repeated native line IDs and profile-scoped
  local/private migration. A store integration test feeds real PCM through the local
  adapter with fake native inference and verifies exactly one durable admission.
  Two Python thermal-parser tests passed. The APK retains native license assets,
  excludes the SDK's permission Activity and passes 16 KiB ZIP alignment checks.
- **Implemented but still unverified in real use:** acoustic transcription quality
  from this phone's microphone, car/headset behavior, echo/barge-in, screen-lock
  behavior with local inference and other phones/ABIs. Starting voice after a fully
  offline restart is not established; voice entry currently needs an opened chat.
  This short audiobook corpus includes distance/time numbers but no decimals;
  alignment boundaries are estimates, and three minutes does not establish a
  long-term thermal steady state. No personal Hermes/provider test was performed.

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

## Manual acceptance

1. Keep your existing Hermes connection and plugin 0.3.0. Open Settings → Voice →
   **On device**, select a model and download it. Test weights live only in the
   disposable fixture sandbox; the personal app needs its own download.
   Before downloading, confirm **Start voice** is disabled in all three modes and
   the download instruction names the selected model. Delete the selected model
   later and confirm Start becomes disabled again while Voice settings stays available.
2. Load Tiny, switch to Small, then Medium. Confirm installed/download sizes,
   actual CPU backend and saved per-model tuning. Unload, restart the app and
   confirm the selected model and installed status survive. Delete an unused
   model and confirm the other downloads remain installed.
3. Hold the chat microphone, wait for the chime, speak a short sentence and release.
   Correct the inserted draft and send it. Then tap the microphone for conversation
   mode and tap again to end. Check the reply, activity announcements and offline
   spoken playback. No transcription URL or speech-service key is needed for local
   mode. Enable Voice → Debugging voice interface to use the older review panel.
4. After downloading and loading a model in an already opened idle conversation,
   repeat local review with networking
   disabled. Recognition should work; sending to remote Hermes still needs a
   connection. Review should keep your unsent words until connectivity returns.
5. Test real pauses, measurements/decimals, your accent, road noise, a headset,
   speaker echo and barge-in. These need human/acoustic acceptance; the direct-input
   corpus and microphone lifecycle checks do not establish that behavior.
6. If you use a private service too, switch to **Private server** explicitly and
   confirm its saved profile-scoped fields remain. Switch back to **On device**;
   missing local weights must show a local error without contacting that service.

Do not run the automated instrumentation suite against personal app profiles.
Use the fixture package for repeatable tests; no merge or release publication is
part of this development branch.
