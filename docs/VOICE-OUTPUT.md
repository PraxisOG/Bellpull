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
Qwen test weights need removal when ADB returns. Preserve personal app data and
signing lineage. Do not describe 0.5.4 as installed.

## Pending acceptance

No further tests while the phone is away, per user instruction. Human listening,
speaker echo, headsets/car/Bluetooth, interrupted mid-compute synthesis, screen lock,
background lifecycle and personal-provider responses remain unverified. Resume
fixture-only checks before a signed personal update. Test tap/hold dictation,
activity announcements, missing/corrupt weights, selection switching, unload/delete,
retry/restart, streaming, stop/restart and severe-thermal cancellation.

Pocket TTS's source dependency is pinned/approved. The stock Sherpa AAR contains
unrelated GPL eSpeak/piper code; it is not bundled. A smaller Pocket-only runtime
is required before Pocket can be selected. Future candidates: Qwen 1.7B for style
instructions, Kokoro 82M and Supertonic 3 (separate license review required).
