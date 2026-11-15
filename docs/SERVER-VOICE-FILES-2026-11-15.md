# Server voice and live files candidate

Android **0.20.2-experimental / build 52**, Bellpull plugin
**0.9.2-voice-files-preview**. This local candidate includes the previous
reliability fixes and completes the first two requested work items. Notifications,
memory and Canvas follow-ups remain on hold. No production APK, gateway plugin,
speech service or personal source document was changed during this work.

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
- A broad stock-Hermes plugin run had 29 failures/errors, identical by test name
  to the untouched starting commit. These are existing legacy-host compatibility
  checks plus the existing native callback-restoration check. The candidate added
  no failures to that comparison; this is not a claim that the entire plugin suite
  is green. See the [baseline comparison](evidence/voice-files-20261115/plugin-baseline-comparison.json).
- The signed APK passed 16 KiB alignment verification. Production upgrade and
  deployment have not been performed.

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
