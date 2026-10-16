# Pixel 10 Pro Fold — Moonshine CPU results

Measured October 11, 2026 (Pacific time), on the user's Pixel 10 Pro Fold running
GrapheneOS / Android 17. Tests used only `org.praxisog.hermes.fixture`, public
licensed audio and downloaded model weights. No personal Hermes server or inference
provider was contacted. [Every measurement](measurements.md) and raw evidence are
preserved here; this is a small smoke corpus, not a general accuracy ranking.

All three checkpoints are **Streaming English `quantized_26_08_21`, INT8 per-channel
`.ort`**, using **Moonshine Voice Android 0.1.5 / native API 30000 / ONNX Runtime CPU**.
The adapter explicitly selects CPU; no GPU/TPU provider or CPU fallback is involved.
Settings: minimum streaming update 500 ms, native VAD threshold 0.5, SDK thread
defaults (the runtime does not report its resolved thread count).

| Model | Download MB | Cold verify + load s | Clean batch speed × real time | Clean first partial s | Clean last word → final s | Warm corpus WER % | Peak PSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Tiny Streaming | 45.23 | 2.97 | 4.91 | 1.40 | 1.11 | 2.29 | 302.6 |
| Small Streaming | 142.30 | 8.44 | 2.37 | 1.67 | 2.17 | 2.94 | 537.3 |
| Medium Streaming | 269.14 | 15.76 | 1.78 | 1.70 | 3.56 | 2.94 | 824.7 |

Speed and partial/final values are three-run warm medians for the clean clip.
WER pools three warm repeats of all four clips in both feed modes (612 reference
words per model); it excludes cold/sustained runs. PSS is the maximum sampled
whole fixture process across all model runs, including the instrumentation runner.
Cold includes integrity verification and model loading with existing OS file caches;
it is not a cache-flushed boot. Timing boundaries come from dataset forced alignment.
Tiny is the useful starting point on these clips: larger models cost more time and
memory without improving this corpus's pooled WER. More accents, spontaneous speech,
decimals and real phone recordings are needed before choosing on accuracy.

## Protocol and thermal results

Four identical 16 kHz mono PCM16 WAVs: clean human speech, the same speech mixed
with recorded road traffic, distance/time numbers and natural pauses. See
[corpus provenance and licenses](../README.md). Each model used a fresh app process,
whole-file and paced-stream recognition, one unloaded-model and three warm
repeats per clip/feed, producing 32 baseline rows, then at least 180 seconds of repeated clean-clip batches.
There are **206 total saved measurements**: 86 Tiny, 64 Small and 56 Medium.

Each model began after three readings ten seconds apart at Android thermal status 0,
`VIRTUAL-SKIN` ≤31.5°C, `battery` ≤29.5°C and `BIG` ≤37°C. Actual starting temperatures
are recorded in [conditions](benchmark-conditions.json). USB power and 100% battery,
manual brightness 9/255 and OPENED posture stayed consistent. Hinge readings were
approximately 175–180°. Ambient temperature and unrelated system workload were not
controlled. Original adaptive brightness and screen timeout were restored afterward.

| Model | Sustained batches | Native processing during ≥180 s wall test | Peak battery °C | Peak VIRTUAL-SKIN °C | Peak BIG / LITTLE °C | Maximum Android thermal status |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Tiny | 54 | 159.0 s | 30.9 | 34.08 | 50 / 50 | 0 |
| Small | 32 | 168.4 s | 31.9 | 35.08 | 51 / 51 | 0 |
| Medium | 24 | 175.6 s | 32.3 | 35.66 | 52 / 52 | 0 |

Sensor names retain their HAL labels. GPU/TPU temperature readings were unavailable;
invalid sentinel values were not treated as temperatures. App thermal headroom and
sample ages are in each raw row; headroom stayed below 1.0. The app and external
monitor stop experiments at severe status. Three minutes is a sustained smoke test,
not proof of a long-term thermal steady state. Details include first/last ten-run
medians and peak RSS.

## Turn boundaries and native functional acceptance

Moonshine completed several internal transcript segments before the last word;
[measurements](measurements.md) report those counts as **early completed lines**.
They are not assistant turns. Medium needed file-end forcing in 3 of its 12 warm
stream runs; Tiny and Small needed none. The raw JSON field `premature_endings`
retains the early-line metric for compatibility, despite its historical name.

Bellpull's separate default 1.6-second pause detector was also tested directly with
the exact same audio, without recognition or a server:

| Clip | Completed turns | Premature turns | Estimated last word → capture end ms |
| --- | ---: | ---: | ---: |
| Clean | 1 | 0 | 1510 |
| Road noise | 1 | 0 | 1450 |
| Numbers/measurements | 1 | 0 | 1530 |
| Natural pauses | 1 | 0 | 1460 |

These are direct-input detector results, not acoustic/headset or Parakeet EOU
measurements. Normal voice currently transcribes the closed utterance in batch;
live partial decoding is exercised by the experimental chunked benchmark. Voice
entry currently needs an opened conversation; a fully offline app restart has not
been accepted.

Eight distinct functional/UI checks passed across the final runs:

- Tiny → Small → Medium → Tiny native switching; previous models unload first.
- Persisted model tuning unloads the current model; deleting Medium preserves Tiny/Small.
- Real local recognition of captured PCM reaches unsent Review with an empty service
  URL. App UID RX/TX counters remained unchanged during switching/tuning/recognition.
- Real offline `en_US` TTS playback; microphone release/restart; AEC enabled.
- Encrypted voice configuration/playback/draft isolation and clearing.
- On-device/private-service controls, actual CPU reporting, model selection and Save.
- Keyboard-reachable project Save, installed About version and drawer filters.
- Default conversation pause detector against all four clips.

The initial eight-test run passed seven and failed the new UI test. Follow-up
attempts exposed stale/offscreen Android accessibility nodes. The test harness now
clears that cache on API 34+, scrolls to controls and waits for stable bounds;
all four UI tests then passed. [Initial device log](device-functional-initial.txt)
and [final UI log](device-ui-final.txt) retain that history. No product checks were
weakened. The three [benchmark runner results](benchmark-runners.txt) all passed.

Local validation also passed: **477 tests, one intentional live-server skip,
156 verified screenshot baselines**, debug/signed APK builds, lint, fixture compilation,
two thermal-parser Python tests and 16 KiB ZIP alignment. The personal app was updated
in place to **0.5.1/code 13** with the existing signing certificate and unchanged UID
and data directory inodes; it was left stopped. Fixture apps/weights were removed.
The personal app needs its own model download in Settings → Voice → On device.

## Implemented, unverified and blocked work

| Status | Work | Limit or reason |
| --- | --- | --- |
| Measured on this phone | Three Moonshine CPU models, downloads/restart reuse, repeated streams, switching/tuning/deletion, local Review, microphone/TTS lifecycle, corpus pause detector | Public corpus and isolated fixture only |
| Implemented but unverified in real use | Personal-server voice conversations, microphone recognition quality, car/headset echo and barge-in, screen-lock behavior with local recognition | Requires human/acoustic/server acceptance; personal server was not accessed |
| Not implemented | Whisper family, Qwen3-ASR 0.6B/1.7B, IBM Granite Speech, Parakeet TDT and Realtime EOU | Earmarked; additional runtimes/ports are not integrated or approved dependencies |
| Blocked / not attempted | GPU/Vulkan/Pixel TPU experiments and combined EOU + transcriber | Shipped SDK is CPU-only; separate runtime approval and GrapheneOS compatibility evidence needed. Parakeet TDT TPU samples do not establish Realtime EOU support |

## Reproduce and inspect

Run from the repository root:

```sh
python3 tools/summarize_speech_benchmarks.py \
  benchmarks/speech/pixel-10-pro-fold-2026-10-11
```

The summarizer reads compressed thermal evidence directly. Per-model JSON files
retain every transcript, audio SHA-256, cold/warm identity, actual backend, load/
processing/partial/final timings, word edits, sampled PSS/RSS and app thermal snapshots:
[Tiny](results-moonshine-tiny.json), [Small](results-moonshine-small.json),
[Medium](results-moonshine-medium.json). [Pause-detector results](pause-gate.json),
[HAL samples](thermal-full.jsonl.gz), [conditions](benchmark-conditions.json) and
[benchmark fixture APK hash](benchmark-apk-sha256.txt) complete the evidence.

See [local speech setup and manual acceptance](../../../docs/LOCAL-SPEECH-LAB.md).
