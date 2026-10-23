## 0.5.5 launch repair — October 12

## Current: Kokoro 82M, Android 0.14.6

Settings → Voice output → Kokoro 82M → Kokoro model → Download Kokoro.
Choose Heart, Bella, Michael or Fenrir, preview, then Save voice output.
The 336.78 MB verified download contains FP32 Kokoro v1.0, four voices and an
English Misaki dictionary plus 0.75M neural pronunciation fallback. It generates
24 kHz speech locally through the existing CPU runtime, with no server plugin
change. Moonshine speech input is independent. Existing output selections are
preserved until explicitly changed.

On Pixel 10 Pro Fold, four-thread FP32 generated 4.9 seconds of speech in
2.26–2.50 seconds versus 6.58–7.03 seconds for INT8. FP32 is therefore the initial
choice despite its larger download. A separate five-phrase test measured RTF
0.50–0.53, warm first audio 0.78–2.72 seconds, and cold verified load 4.53 seconds.
These are short CPU samples, not a promise of universal conversational latency.
[Measurements, acceptance checks and limits](evidence/voice-media-20261022/README.md).

The frontend is US English only. Heteronyms use default dictionary entries;
unknown words use neural G2P. Generation is divided into bounded phrases,
playback overlaps subsequent synthesis, and Stop clears queued audio immediately.
An active ONNX operator finishes before the native worker can release its context.
No reference-voice cloning, GPU, TPU or non-English presets are exposed.

Sentence streaming (0.14.6): live voice and Read aloud use the same sentence
splitter, with common English abbreviations, decimals and closing quotes kept
together. Long sentences can start at clause boundaries; very long text is split
into bounded requests without dropping the ending. Complete sentences start while
Hermes is still generating; an unfinished final fragment waits for completion.
Kokoro generates one sentence ahead while the current audio plays. Its PCM queue
holds up to 60 seconds per request (about 11.5 MB across active and ahead queues),
instead of blocking synthesis behind a 1.6-second buffer. Only one future request
is prepared. Stop cancels both queues. Each native inference still generates a
whole bounded phrase: this is sentence-based playback, not token-by-token audio.
[Phone timing comparison and tests](evidence/kokoro-sentences-20261022/README.md).

The earlier release notes below are historical.


The original 0.5.5 launch crash was repaired at `c913243`: the folder validator now
accepts the shipped safe `qwen-0.6b` ID while rejecting traversal/unsafe paths.
Eight focused JVM regressions and one isolated phone startup/preflight/Activity
check passed. The corrected signed 0.5.5 is installed; personal app left stopped.
See [exact artifact and signing evidence](release.md). This does **not** establish
Pocket synthesis, route recovery, acoustic quality or personal-provider behavior.
Voice output screenshot baselines were subsequently reviewed/updated and the API-31
communication callback guard corrected on the shared 0.5.5 branch.

# Local voice output

Settings → Voice input chooses transcription. Settings → Voice output independently
chooses spoken answers and message Read aloud for each Bellpull server profile.
Default Android voice uses the installed offline engine. Qwen3-TTS is an explicit
CPU experiment: download, select, save, optionally preview. Missing weights block
starting voice. No transcription URL, speech-service key or Hermes plugin upgrade
is needed for local synthesis; Bellpull plugin 0.3.0 is unchanged.

Qwen checkpoint: Qwen3-TTS-12Hz-0.6B-CustomVoice, Q4_K_M; GGUF revision
`b7ee2e8c7459c3bea99da23e3d178125a7d1713c`. Talker 604,878,080 bytes and codec
254,974,752 bytes are SHA-256 checked before use. Runtime qwentts.cpp
`51512f129a7419567f4b8abfb06801451789b8f1`, GGML
`2eddaf94a8cdbbf1c30017d28313cd661a9da543`, CPU only, optimized in debug too.
Model files are private to the app, excluded from backup. Completed download files
survive retry; incomplete files never count as installed. Delete removes only this
model's owned files. Unload invalidates active work and waits for compute to exit
before releasing a native context.

Controls: preset speaker/language, playback speed, volume and Talker sampling.
0.6B has no natural-language style/emotion instruction support. The acoustic
sub-talker uses upstream defaults. There is no GPU/TPU selector for unsupported
backends. Text arrives as phrases; native PCM begins during phrase synthesis.
Interruption clears queued audio and cancels generation. Severe thermal status
blocks/ends synthesis. No automatic fallback to another voice engine.

## Measured on this phone: tested 0.5.4 snapshot

Pixel 10 Pro Fold, isolated fixture app, arm64 CPU, no Hermes/provider contact.
Six samples (three phrases, two repetitions) plus native playback/cancel checks.
See [raw rows and methodology](../benchmarks/voice-output/pixel-10-pro-fold-2026-10-11/README.md).

| Metric | Measurement |
| --- | --- |
| Cold verification + model load | 3.242 seconds |
| First PCM after load | 0.662–1.110 seconds |
| Working: 1.68 seconds of audio | 13.226 seconds synthesis cold; 11.630 warm |
| Processing seconds / audio second | 6.92–7.87 |
| Peak process PSS | 2,755,315 KiB (~2.63 GiB) |
| Thermal status | NONE throughout short comparison |
| Thermal headroom | ~0.62–0.67 |
| Battery temperature | 31.2 °C |

This is too slow for fluid conversation. First PCM latency does not describe gaps
later in the answer or time to the last spoken word. A successful direct-native
callback cancellation does not establish mid-compute human barge-in latency.
The short CPU comparison is not a sustained output thermal qualification.

Before the phone left: 519 tests passed, one opt-in live-server skip; 161 screenshot
baselines verified; debug/release build and lint passed. Two isolated phone checks
passed: native CPU synthesis/metrics/cancellation, and real AudioTrack playback/drain/
stop. Logs are retained under ignored `build/qwen-fixture/` and
`build/qwen-final-checks.log`. No current CI run exists: nothing was pushed.

The signed **0.5.4/code 16** APK is `build/releases/0.5.4/Bellpull.apk`, SHA-256
`8341e1830a4bc8225e9c7acbbeaed5af3adb93cbc9fa86555e722197ca4a5e22`.
ADB disconnected before personal installation or fixture cleanup. The personal
app remains **0.5.3/code 15**, stopped. The temporary fixture app/test package and
Qwen test weights needed removal when ADB returned. **October 12 update:** the
user requested and received 0.5.5/code 17 directly over 0.5.3; 0.5.4 was never
installed. Signing/UID/data-directory identity was preserved and fixture packages/
weights removed. The app was left stopped, with no personal server access.
See [installation evidence](HANDOFF.md). No new functional tests were run.

## Pending acceptance

Further functional tests remain deferred. Human listening,
speaker echo, headsets/car/Bluetooth, interrupted mid-compute synthesis, screen lock,
background lifecycle and personal-provider responses remain unverified. Resume
fixture-only checks when testing resumes. Test tap/hold dictation,
activity announcements, missing/corrupt weights, selection switching, unload/delete,
retry/restart, streaming, stop/restart and severe-thermal cancellation.

## Implemented offline, untested: 0.5.5/code 17

Pocket is now selectable as an explicit experiment, with independent download,
load/unload/delete controls and missing-weight preflight. Checkpoint
`sherpa-onnx-pocket-tts-int8-2026-01-26`, revision
`e715955cf50d18d919d37231513c0e914b83661a`: 198,342,307 bytes including the
five ONNX components, vocab/scores and the 1.05-second Loona reference WAV.
Main/flow/decoder are INT8; encoder/text conditioner FP32. English only.
The SHA-256/size manifest is in `VoiceOutputModels.kt`; staged completed files
survive retry, and only a verified full set becomes installed.

Actual configured backend: CPU, four threads per session, Sherpa-ONNX 1.13.8
`11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`, ONNX Runtime 1.28.2. No GPU,
TPU, NNAPI or partial fallback is selected. Qwen and Pocket share an exclusive
runtime owner: loading either unloads the other before allocation. Selecting
Android also unloads local output models off the main thread. Model choices and
sampling settings remain profile-local; downloaded weights are device-wide.

Pocket exposes real runtime controls: flow steps (1–10), temperature (0–1.5),
decoder chunk frames (1–30), int32 seed (-1 random), volume and playback speed.
This runtime generates a phrase's latents before decoding PCM chunks; do not
claim first audio during latent generation. Cancellation probes between latent/
flow/decoder steps and a bounded PCM queue discard stale work on interruption.
The decoder applies backpressure when it outruns playback. Cancellation is not
instantaneous during an ONNX operator. Custom reference import is deferred.

The stock all-model Sherpa AAR is excluded because it bundles GPL eSpeak/piper.
Only Pocket/model/tokenizer/helpers are compiled from pinned source. A checked
build-time adapter removes optional FST normalization/Eigen/silence scaling,
turns process-exit failures into caught exceptions, suppresses content logs and
adds cancellation probes. ONNX Runtime is statically linked with hidden symbols
inside `libbellpull_pocket.so`; it cannot collide with Moonshine's differently
versioned `libonnxruntime.so`. API/JNI libraries and model licenses are included
in app assets. Pocket model/reference assets are **CC BY 4.0**, attributed to
Kyutai and the sherpa-onnx conversion by csukuangfj2; runtime Apache-2.0, ORT/json
MIT. No model/reference bytes are changed.

`ANDROID_HOME=/home/praxisog/Android/Sdk ./android/gradlew -p android
:app:assembleDebug --console=plain` compiled both ABIs. Build inspection confirmed
one Moonshine shared ORT per ABI and no shared ORT dependency in Pocket. These are
build/source checks, **not inference/device tests**. No Pocket latency, quality,
memory or temperature measurement is available. Qwen's table above applies only
to the earlier tested 0.5.4 snapshot. Tests, screenshots, lint and device checks
must run before a 0.5.5 install; none were rerun after the user's “minus tests”.

Voice service also observes communication-route and audio-mode changes. Pause
releases its route/focus/mode; explicit Resume reacquires them only if a call is
not using audio. Notification/media controls expose Resume. Stale mode/route
callbacks consult current Android state. No permission to read call details is
added. Bluetooth/navigation/call/lock-screen behavior remains untested.

Future candidates: Qwen 1.7B for style instructions, Kokoro 82M, Supertonic 3
(separate license review required). Failed acceleration experiments do not block
working CPU choices. No plugin changes, push, phone access or server access in
this offline slice.

Offline source review also fixed output startup recovery: pausing during slow model
loading cannot let a late readiness callback reopen capture. Explicit Resume
retries preparation if it was canceled before readiness, with audio ownership
checked again. Android TTS preparation uses a separate cancellation generation and
shuts down the prior instance on retry. This change is compiled but untested.

Startup reconciliation is retained across canceled/retried output loading: a
previously admitted voice request is matched/cleared through the original recovery
path before listening resumes, rather than exposing it as a fresh unsent turn.
This source correction is also awaiting regression/device checks.

Resume notification/headset actions are accepted only while paused; stale Play/
Resume actions cannot reset an active turn. This guard is also untested.
