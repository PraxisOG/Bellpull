# Moonshine phone benchmark results

Device: Pixel 10 Pro Fold; build: `google/rango/rango:17/CP3A.261005.005/2026100601:user/release-keys`.
Display brightness setting: 9; adaptive mode: 0 (0 = manual). Fold state and battery conditions are retained in raw thermal evidence.
Direct 16 kHz mono PCM16 input; no speaker/microphone loop. Three warm repeats per case.
Cold means unloaded model with existing OS caches. Last-word boundaries are dataset forced-alignment estimates.
First-partial and final latency are warm medians; final latency includes chunk arrival/decoding and distinguishes forced finalization.

REALTIME elapsed time includes paced arrival; its audio/elapsed ratio is not maximum decoding throughput. Early-line and forced-final counts total the three warm repeats.
Peak PSS is the sampled whole fixture process (including the test runner), not isolated model allocation.

| Model | Input | Feed | Cold load ms | Warm process ms | Audio/process × | First partial ms | Last word → final ms | WER % | Early completed lines | Forced finals | Peak PSS MiB |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| moonshine-tiny | clean | THROUGHPUT | 2965.7 | 2555.3 | 4.91 | unavailable | unavailable | 0.00 | 0 | 0 | 278.2 |
| moonshine-tiny | road-noise | THROUGHPUT | 2903.0 | 2444.7 | 5.14 | unavailable | unavailable | 0.00 | 0 | 0 | 283.2 |
| moonshine-tiny | numbers-measurements | THROUGHPUT | 2870.3 | 1762.2 | 3.82 | unavailable | unavailable | 6.25 | 0 | 0 | 276.8 |
| moonshine-tiny | natural-pauses | THROUGHPUT | 2967.1 | 4021.6 | 4.14 | unavailable | unavailable | 2.63 | 0 | 0 | 275.2 |
| moonshine-tiny | clean | REALTIME | 2942.0 | 12631.4 | 0.99 | 1399.0 | 1106.5 | 0.00 | 3 | 0 | 291.5 |
| moonshine-tiny | road-noise | REALTIME | 2956.2 | 12599.9 | 1.00 | 1426.8 | 1229.0 | 0.00 | 3 | 0 | 297.9 |
| moonshine-tiny | numbers-measurements | REALTIME | 2938.0 | 6819.1 | 0.99 | 1477.5 | 1334.9 | 6.25 | 0 | 0 | 288.2 |
| moonshine-tiny | natural-pauses | REALTIME | 2917.1 | 16713.7 | 1.00 | 1555.3 | 996.7 | 4.39 | 18 | 0 | 288.8 |
| moonshine-small | clean | THROUGHPUT | 8437.1 | 5300.7 | 2.37 | unavailable | unavailable | 0.00 | 0 | 0 | 513.3 |
| moonshine-small | road-noise | THROUGHPUT | 8634.1 | 4957.8 | 2.53 | unavailable | unavailable | 0.00 | 0 | 0 | 525.8 |
| moonshine-small | numbers-measurements | THROUGHPUT | 8536.5 | 3095.4 | 2.18 | unavailable | unavailable | 6.25 | 0 | 0 | 513.1 |
| moonshine-small | natural-pauses | THROUGHPUT | 8616.6 | 8677.3 | 1.92 | unavailable | unavailable | 5.26 | 0 | 0 | 505.9 |
| moonshine-small | clean | REALTIME | 8391.2 | 12604.5 | 1.00 | 1666.8 | 2166.5 | 0.00 | 3 | 0 | 522.5 |
| moonshine-small | road-noise | REALTIME | 8467.5 | 12569.2 | 1.00 | 2613.3 | 2315.7 | 0.00 | 3 | 0 | 531.0 |
| moonshine-small | numbers-measurements | REALTIME | 8658.0 | 8382.5 | 0.80 | 1889.1 | 4028.9 | 6.25 | 0 | 0 | 516.5 |
| moonshine-small | natural-pauses | REALTIME | 8580.2 | 16736.1 | 0.99 | 1577.1 | 2533.1 | 5.26 | 17 | 0 | 513.4 |
| moonshine-medium | clean | THROUGHPUT | 15756.6 | 7047.0 | 1.78 | unavailable | unavailable | 4.17 | 0 | 0 | 816.0 |
| moonshine-medium | road-noise | THROUGHPUT | 15817.9 | 6514.9 | 1.93 | unavailable | unavailable | 0.00 | 0 | 0 | 815.6 |
| moonshine-medium | numbers-measurements | THROUGHPUT | 16393.1 | 4608.5 | 1.46 | unavailable | unavailable | 6.25 | 0 | 0 | 796.6 |
| moonshine-medium | natural-pauses | THROUGHPUT | 15687.7 | 12501.9 | 1.33 | unavailable | unavailable | 2.63 | 0 | 0 | 781.6 |
| moonshine-medium | clean | REALTIME | 15669.1 | 13702.6 | 0.92 | 1704.0 | 3560.5 | 4.17 | 2 | 0 | 802.2 |
| moonshine-medium | road-noise | REALTIME | 15792.4 | 13636.2 | 0.92 | 1771.1 | 3545.8 | 0.00 | 0 | 1 | 812.2 |
| moonshine-medium | numbers-measurements | REALTIME | 16009.1 | 8342.4 | 0.81 | 2023.2 | 4006.8 | 6.25 | 0 | 0 | 811.1 |
| moonshine-medium | natural-pauses | REALTIME | 15949.5 | 17998.5 | 0.93 | 2232.8 | 3858.2 | 2.63 | 18 | 2 | 790.0 |


## moonshine-tiny

- Checkpoint: `quantized_26_08_21`; quantization: `INT8 per-channel (.ort)`.
- Runtime: `Moonshine Voice 0.1.5; native API 30000; update_ms=500; vad_threshold=0.5; SDK thread defaults`; backend: `CPU (ONNX Runtime)`.
- First JNI library initialization: 8.0 ms (included in that cold load).
- Sustained test: at least 180 s wall time; 54 runs, 159.0 s native processing (the remainder includes sampling, saving and test-runner overhead); peak PSS 302.6 MiB, peak RSS 486.3 MiB.
- Sustained clean-clip processing, first/last ten-run medians: 2956.7 / 2858.9 ms. This is observed drift, not an isolated throttling measurement.
- Android thermal headroom range: 0.540–0.635 (0 forecast seconds, 1.0 is the severe threshold; sample ages retained in raw rows).
- Cooled baseline: VIRTUAL-SKIN 31.38°C; battery 29.30°C; BIG 34.00°C; Android status 0.
- Maximum Android thermal status: 0.
- Peak `battery` (HAL label): 30.90 °C.
- Peak `VIRTUAL-SKIN` (HAL label): 34.08 °C.
- Peak `BIG` (HAL label): 50.00 °C.
- Peak `LITTLE` (HAL label): 50.00 °C.
- Peak `GPU` (HAL label): unavailable.
- Peak `TPU` (HAL label): unavailable.


## moonshine-small

- Checkpoint: `quantized_26_08_21`; quantization: `INT8 per-channel (.ort)`.
- Runtime: `Moonshine Voice 0.1.5; native API 30000; update_ms=500; vad_threshold=0.5; SDK thread defaults`; backend: `CPU (ONNX Runtime)`.
- First JNI library initialization: 5.6 ms (included in that cold load).
- Sustained test: at least 180 s wall time; 32 runs, 168.4 s native processing (the remainder includes sampling, saving and test-runner overhead); peak PSS 537.3 MiB, peak RSS 714.3 MiB.
- Sustained clean-clip processing, first/last ten-run medians: 5185.9 / 5295.9 ms. This is observed drift, not an isolated throttling measurement.
- Android thermal headroom range: 0.529–0.669 (0 forecast seconds, 1.0 is the severe threshold; sample ages retained in raw rows).
- Cooled baseline: VIRTUAL-SKIN 31.36°C; battery 29.30°C; BIG 34.00°C; Android status 0.
- Maximum Android thermal status: 0.
- Peak `battery` (HAL label): 31.90 °C.
- Peak `VIRTUAL-SKIN` (HAL label): 35.08 °C.
- Peak `BIG` (HAL label): 51.00 °C.
- Peak `LITTLE` (HAL label): 51.00 °C.
- Peak `GPU` (HAL label): unavailable.
- Peak `TPU` (HAL label): unavailable.


## moonshine-medium

- Checkpoint: `quantized_26_08_21`; quantization: `INT8 per-channel (.ort)`.
- Runtime: `Moonshine Voice 0.1.5; native API 30000; update_ms=500; vad_threshold=0.5; SDK thread defaults`; backend: `CPU (ONNX Runtime)`.
- First JNI library initialization: 5.8 ms (included in that cold load).
- Sustained test: at least 180 s wall time; 24 runs, 175.6 s native processing (the remainder includes sampling, saving and test-runner overhead); peak PSS 824.7 MiB, peak RSS 993.3 MiB.
- Sustained clean-clip processing, first/last ten-run medians: 7324.7 / 7255.3 ms. This is observed drift, not an isolated throttling measurement.
- Android thermal headroom range: 0.525–0.689 (0 forecast seconds, 1.0 is the severe threshold; sample ages retained in raw rows).
- Cooled baseline: VIRTUAL-SKIN 31.31°C; battery 29.30°C; BIG 35.00°C; Android status 0.
- Maximum Android thermal status: 0.
- Peak `battery` (HAL label): 32.30 °C.
- Peak `VIRTUAL-SKIN` (HAL label): 35.66 °C.
- Peak `BIG` (HAL label): 52.00 °C.
- Peak `LITTLE` (HAL label): 52.00 °C.
- Peak `GPU` (HAL label): unavailable.
- Peak `TPU` (HAL label): unavailable.

## Raw evidence

The JSON files retain every transcript, audio SHA-256, actual backend, runtime settings, PSS/RSS and thermal/headroom snapshots.
Headroom interpretation follows [Android PowerManager](https://developer.android.com/reference/android/os/PowerManager#getThermalHeadroom(int)); sensor labels retain their HAL names.
A completed recognizer line before the annotated last word is a candidate early boundary, not a measured premature assistant turn.
These short excerpts do not establish conversational or general recognition quality. Acoustic echo, barge-in and microphone quality need separate tests.
