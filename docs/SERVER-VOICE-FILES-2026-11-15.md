# Server voice and live files release

Android **0.20.2-experimental / build 52**, Bellpull plugin
**0.9.2-voice-files-preview**. This experimental release includes the previous
reliability fixes and completes the first two requested work items. Notifications,
memory and Canvas follow-ups remain on hold. Development used disposable fixtures.
The signed phone update was subsequently installed in place at the user's request.
Gateway plugins, speech services and personal source documents remain unchanged.

## Result

Server Kokoro sends the first sentence while later sentences are still being
generated. The proxy preserves the declared 24 kHz mono PCM16 format; Android
converts it explicitly for the existing 48 kHz player. Sentence prefetch now
reaches the server engine and overlaps playback, with bounded queues and
cancellation. WAV callers and legacy MOSS 48 kHz PCM remain compatible. The
installed ROCm Moonshine/Kokoro implementation was brought into source control
without changing its GPU configuration or introducing CPU fallback.

Files replaces the Tools navigation entry and swaps the left conversation pane
for pinned/recent documents. Settings begins with recent files and See all files,
followed by the compact expandable GPU panel and existing settings. The agent
registers exact paths through `bellpull_file`; its packaged `bellpull:live-files`
skill explains registration, refresh and explicit per-file editing grants.
Markdown and text display natively. Read-only is the default. Granted checklists
support toggles and adding plain-text items, saved as Markdown in the original
file. A stale ETag conflicts, and durable operation IDs prevent duplicate adds
when a response is lost. [File contract and limits](LIVE-SERVER-FILES.md).

## Measured speech

Measurements use synthetic text, the real ROCm GPU helper on an isolated port,
the stock Hermes speech proxy and the separate phone fixture app. They are local
ADB/loopback measurements, not a claim about cellular/Tailscale latency.

| Check | Result |
| --- | --- |
| Helper first PCM | 342–438 ms |
| Helper generation real-time factor | 0.102–0.105 |
| Simultaneous Moonshine recognition | Correct 3.25-second synthetic clip in 642 ms |
| Helper cancellation/reuse | 508 ms including next short synthesis |
| Phone first playback callback | 467 ms |
| Phone sentence gaps | 46 and 67 ms |
| Phone stop call | 14.6 ms |

The real AudioTrack test also verifies 24 kHz content retains its duration and
next-sentence generation begins before current playback finishes. A playback
callback is not an acoustic microphone measurement. Streaming is by bounded
sentence batches, not tokens. ROCm STT remains batch recognition.

## Validation

- 906 Android/JVM tests passed, one existing skip. All 221 screenshot baselines
  verified after reviewing 38 intended label/layout updates. Lint: zero errors,
  96 warnings and six hints. Targeted voice and file tests passed again after
  the final file-toolbar change.
- Ten speech-helper tests and 21 targeted stock-Hermes plugin tests passed,
  including authenticated file routes, read-only enforcement, conflicts,
  symlink/hard-link rejection, ETags, retry recovery and streamed PCM headers.
- The real agent registry dispatched `bellpull_file` for synthetic documents and
  discovered the plugin skill. Hermes' own skill validator accepted its format.
- The phone test passed unfolded and at cover-screen dimensions, exercising
  the full MainActivity: file navigation, row checkbox
  taps, adding an item, source-file readback, pinning, Settings recent files,
  See all files, read-only interaction and reopening/refresh.
- Publication checks on the compatible release server image passed: **236 tests,
  four host-specific skips**, zero failures/errors. [Full release-host log](evidence/voice-files-20261115/plugin-release-host.log).
- A broad stock-Hermes plugin run had 29 failures/errors, identical by test name
  to the untouched starting commit. These are existing legacy-host compatibility
  checks plus the existing native callback-restoration check. The candidate added
  no failures to that comparison; this is not a claim that the entire plugin suite
  is green. See the [baseline comparison](evidence/voice-files-20261115/plugin-baseline-comparison.json).
- The signed APK passed 16 KiB alignment verification and was installed over
  production 0.20.0 without uninstalling or clearing data. The app ID, data-directory
  path and original installation time are unchanged; the installed APK hash matches
  the release asset. Both saved profiles and the new Settings layout were visible,
  and the app reopened successfully. Server runtime deployment remains separate.
  [Phone upgrade verification](evidence/voice-files-20261115/phone-upgrade.json).

[Validation data](evidence/voice-files-20261115/validation.json) ·
[Phone speech timing](evidence/voice-files-20261115/phone-voice.json) ·
[Files screenshot](evidence/voice-files-20261115/files-unfolded.png) ·
[Settings screenshot](evidence/voice-files-20261115/settings-unfolded.png) ·
[Cover-screen files](evidence/voice-files-20261115/files-cover.png).

The isolated gateway has no GPU monitoring CLI, so its Settings screenshot shows
the telemetry-unavailable state. Existing GPU expansion tests passed; live
temperature monitoring was not redeployed. Source references are limited to
2 MiB UTF-8 regular files and top-level checklist editing. External writers are
checked optimistically and do not share the Bellpull edit lock.

Deploy the APK, complete matching plugin bundle and speech-helper sources as a
coherent update. The helper deployment instructions preserve an existing ROCm
runtime and cached models: [speech helper](../server-speech/README.md).

## PowerMac deployment and CPU comparison, 2026-11-24

The installed ROCm helper recognized speech but rejected Android’s Kokoro streaming
request with HTTP 400. Its whole-WAV request still returned 24 kHz audio. Updating
the helper enabled streaming. The installed gateway plugin then dropped the PCM
sample-rate header, which the Android player needs. Installing the published
0.9.2 voice-files plugin restored the header. The gateway continued advertising
all previously enabled capabilities.

Through the updated live gateway, Moonshine recognized the synthetic 2.86-second
clip in 123 ms. Kokoro returned its first PCM in 129 ms and 5.275 seconds of
24 kHz mono audio in 250 ms. Android’s player on an API 36 emulator played three
utterances, 9.05 seconds in total. Playback started in 161 ms, the gaps were
2.4 and 2.1 ms, and Stop returned in 1.2 ms. A loopback test relay substituted
the gateway bearer at the host boundary and forwarded the live stream and headers.

The table gives medians from three warm runs on the same Ryzen 5 7600 host.
The real-time factor is elapsed synthesis time divided by generated audio duration.

| Input | CPU first PCM | CPU real-time factor | ROCm first PCM | ROCm real-time factor |
| --- | ---: | ---: | ---: | ---: |
| Short | 367 ms | 0.302 | 107 ms | 0.058 |
| Long | 496 ms | 0.247 | 125 ms | 0.044 |
| Punctuation-heavy | 282 ms | 0.280 | 95 ms | 0.056 |

Simultaneous Kokoro synthesis increased median Moonshine latency from 879 to
1,278 ms on CPU and from 127 to 256 ms on ROCm. All transcripts matched the
warm-up result. The Android CPU playback run started in 584 ms, with 2.1 and
2.2 ms between sentences. Two CPU thread-setting experiments traded latency
between tasks. The production settings were retained. The Android timings
measure the playback callback. Vehicle routing and acoustic onset need a physical
route measurement.

Run `tools/benchmark-server-voice.py sample.wav --port 8645` in the helper’s
Python environment with its configured `BELLPULL_SPEECH_KEY`. Supply a synthetic
or public 16 kHz mono PCM16 clip. The harness records timing and transcript
agreement. [Raw results](evidence/server-voice-20261124/results.json).

A subsequent Android recognition test uncovered a negotiation defect that direct
`/stt` probes miss. The phone attempts WebSocket recognition first. With a loaded
batch-only ROCm recognizer the helper returned HTTP 503, and the phone correctly
refused to replay to a server reporting a temporary failure. The helper now
returns HTTP 501 when a loaded recognizer lacks streaming support. A missing
model or a transient failure retains its original error behavior. Against the
live PowerMac gateway, the same Android test failed before the change and passed
afterward, observing one 501 handshake followed by exactly one successful batch
request. Moonshine recognized “green apple” and “kitchen table” with local
fallback disabled. The Android client code used for both runs was identical.

## PowerMac live GPU transcription after the 0.20.4 release

The ROCm helper now transcribes microphone frames over the existing WebSocket.
On four public recordings replayed at microphone speed, the first nonempty
partial arrived in 0.86–2.53 seconds. All four final transcripts matched the
same model's one-shot result, including its recognition errors. An API 36
Android emulator using the live PowerMac gateway received ten changing
partials during the 12.56-second clean recording. The first arrived at 2.57
seconds and the final followed the last audio by 616 ms. The test fails if
Android replays a multipart request. The packaged 0.20.4 Android client
already supports this protocol. [Fixture results](evidence/server-voice-20261124/rocm-streaming.json)
