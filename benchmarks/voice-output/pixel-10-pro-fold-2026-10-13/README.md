# Pixel 10 Pro Fold CPU speech measurements — 2026-10-13

Measured in `org.praxisog.hermes.fixture` on the connected Pixel 10 Pro Fold,
GrapheneOS/API 37. Phone plugged in over USB. Temperature/headroom/status and peak
process PSS are recorded per inference. No personal profile, voice choice, saved
account or Hermes server was accessed. The current personal engine/tuning was not
read; these are explicit isolated default configurations.

## Results

RTF is synthesis seconds / output audio seconds; lower is better. Throughput is
its reciprocal. These are medians including the first inference after each load,
with model-load time excluded. They are measurements, not a subjective quality
ranking or a promise that a graph-only benchmark is a complete Android engine.

| Engine | CPU threads | Inferences | Median RTF | Throughput | Peak process PSS MiB |
|---|---:|---:|---:|---:|---:|
| Paradee | 1 | 10 | 0.175 | 5.72× | 290.1 |
| Paradee | 4 | 10 | 0.305 | 3.28× | 298.5 |
| Inflect | 1 | 10 | 0.661 | 1.51× | 388.1 |
| Inflect | 4 | 10 | 0.285 | 3.51× | 397.7 |
| Pocket defaults | 4 | 10 | 0.528 | 1.90× | 674.1 |
| Qwen defaults | existing native default | 3 | 6.862 | 0.15× | 2688.3 |

Candidates and Pocket use the same five texts, twice per configuration. Qwen uses
the first three matching texts once, followed by cancellation and real playback
checks. Qwen's median therefore uses a smaller short-text suite; compare matching
rows in the JSON when assessing individual phrases. Qwen's seeded defaults are
Ryan/English, Q4_K_M. Pocket uses Loona, 5 steps, temperature 0.7, chunk frames 15,
seed 42. No engine was silently changed in the personal app.

Candidate first PCM arrives only when the entire bounded phrase graph completes;
its latency equals graph synthesis time. Official Python phonemization runs on the
host and is **excluded** from candidate timings. The staged token arrays are
recorded so native CPU graph measurements can be repeated independently. Native
Inflect uses a seeded C++ normal generator rather than NumPy's bit-identical RNG;
this is a throughput experiment, not a numerical parity claim.

Paradee load was 3.38 seconds with one thread / 2.45 with four. Inflect load was
0.68 / 0.66 seconds. Those are graph session loads; Pocket/Qwen load rows also
include hash verification, so their load values should not be compared directly.

## Sentence-gap repair

Actual Pocket AudioTrack playback measured the same two phrases with and without
one-phrase synthesis ahead. Gap measured from the first phrase's drain/completion
callback to the next phrase's first PCM write:

- Without synthesis ahead: **1478.5 ms**.
- With synthesis ahead: **38.8 ms**.

This is one paired comparison, not a latency distribution. It measures callback
and PCM-write timing rather than an external microphone's acoustic silence.
The producer queues are bounded to 16 × 2,400 samples per phrase; at most one
phrase is ahead. Stop cancels both producers and flushes active playback. The
cancellation/backpressure regression and actual Qwen AudioTrack Stop/drain tests
passed. A slow engine still cannot sustain uninterrupted speech when its
synthesis consistently runs slower than playback.

The optional Working announcement remains an audible admission barrier when on;
turning it off skips that cost and submits through the same checks. Qwen's Working
sample took 12.4 seconds of synthesis after 7.6 seconds of cold load/verification.

## Candidate integration decision

[Paradee's official model card](https://huggingface.co/sahilmahendrakar/Paradee-8M-v1.0)
describes a small single American-English `af_heart` voice, with a Misaki frontend
and a matching conversion if eSpeak is used. These Pixel graph results justify
further native frontend work. It remains a benchmark candidate; production voice selection is unchanged.

[Inflect's official export](https://huggingface.co/owensong/Inflect-Micro-v2-ONNX)
has separate duration/decode graphs and an official Python/eSpeak-ng frontend.
Four threads improved its longer phrases in this experiment; its tiny Working
phrase remained slower than its own audio duration on four threads. Native
normalization/phonemization and eSpeak licensing/dependency handling still need a
production design. Both candidate model weights are Apache-2.0; neither replaces
Pocket voice cloning. Fixed sample WAVs are saved with the local build artifacts
for owner listening; no subjective listening approval is claimed.

Candidate JNI code is compiled **only** with the device-fixture build property
and is excluded from the signed personal APK. Production continues to offer
Android offline speech, Pocket and Qwen. The scheduling repair applies to the
existing local engines and message Read aloud.

## Reproduce

1. Create a disposable Python venv and install the official Paradee package plus
   soundfile. The exact measured frontend environment is in
   `frontend-environment.json`; model graph revisions and SHA-256s are in
   `fixtures.json`.
2. Run `tools/prepare_tts_candidates.py /tmp/bellpull-tts-candidates`.
3. Build `assembleDebug assembleDebugAndroidTest -PbellpullDeviceFixture=true`.
   Install only the fixture APKs. Stage model files and `fixtures.json` under the
   fixture app's `files/tts-candidates` using `adb run-as`.
4. Run `CandidateTtsDeviceTest`. Stage the app's existing hash-verified baseline
   models under the fixture's `no_backup/voice-output-models`, then run
   `PocketBaselineDeviceTest` and `QwenVoiceDeviceTest`.
5. Copy the JSON/sample artifacts out, then remove the fixture packages/staged
   files and restore any display-size override. Keep personal app data untouched.

The signed app update and broader acceptance record are in
[EXPERIMENTAL-ISSUE-REPAIRS.md](../../../docs/EXPERIMENTAL-ISSUE-REPAIRS.md).
Physical car/headset routes, real phone-call interruption and long sustained
candidate thermal testing were not exercised. The actual car cutoff trigger and
reported sound remain unestablished.
