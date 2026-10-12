# Qwen voice output: Pixel 10 Pro Fold

Measured in the isolated fixture app with Qwen3-TTS-12Hz-0.6B-CustomVoice,
Q4_K_M, GGUF revision b7ee2e8c7459c3bea99da23e3d178125a7d1713c,
qwentts.cpp 51512f1 / GGML 2eddaf9. Actual backend CPU (no acceleration/fallback).
Optimized arm64 debug native code; four CPU threads from the upstream default.

| Phrase | Audio seconds | First PCM, ms (two runs) | Synthesis seconds (two runs) |
| --- | ---: | ---: | ---: |
| Working. | 1.68 | 1018 / 763 | 13.226 / 11.630 |
| Your answer is ready. | 2.32 | 662 / 953 | 17.587 / 17.291 |
| The board measures twenty four inches by twelve inches. | 4.32 | 730 / 1110 | 31.734 / 31.401 |

Cold verification/loading: 3242 ms, excluded from first-PCM/synthesis columns.
RTF (processing/audio): 6.92–7.87; **larger than 1 means slower than real time**.
Peak process PSS 2,755,315 KiB. This is process memory, not model weights alone.
The seed was fixed; repeated runs measure warmed runtime, not voice diversity.

USB charging remained plugged=2, brightness 6, unfolded 2076×2152. Thermal status
NONE throughout; available battery sensor 31.2 °C start/end, headroom 0.62–0.67.
Headroom values include their reported age in the raw JSON. No unlabeled HAL value
or cached/sentinel GPU/TPU temperature is presented as a current measurement.
These brief runs are not a sustained thermal test. No listening-quality score,
barge-in benchmark or acoustic car/headset result is claimed.

Callback cancellation immediately after first PCM returned in 0.342 ms (status -5).
This is not a bound on cancellation during a native compute step. A separate
AudioTrack test verified playback/drain/stop (15.203 seconds test time).

Raw metrics: [qwen-cpu.json](qwen-cpu.json). Model hashes and installation status:
[voice-output handoff](../../../docs/VOICE-OUTPUT.md). An earlier unoptimized debug
probe was discarded as non-comparable; the table contains the optimized build only.
