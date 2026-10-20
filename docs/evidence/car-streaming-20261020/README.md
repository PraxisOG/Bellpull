# Car audio and CPU streaming speech — October 20, 2026

Android **0.14.4-experimental / build 36**, plugin **0.6.0-integration-preview**.
This increment strengthens Phase 5; it is not an Android Auto application.

## Behavior

- User-started Call mode registers one Android Telecom audio call for the whole
  conversation. Telecom owns communication focus/routing across silent gaps,
  recognition and playback. System hold/resume/end and microphone mute reach the
  voice session. A call notification provides pause/resume and hang-up. Other
  voice modes retain their existing route/focus handling.
- Server recognition uploads 16 kHz mono PCM as the user speaks and displays
  partial transcripts. Only a final transcript can enter the existing reviewed
  voice-turn flow. Partials never submit chat messages.
- Plugin routes use existing root/profile authentication; helper credentials
  stay on the server. Disconnect cancels the stream, and queues/audio/concurrency
  are bounded. An older server's unsupported route permits whole-recording
  fallback. A failed accepted stream requires Retry, unless the user explicitly
  enabled local transcription fallback.
- PowerMac uses a warm **FP32 Moonshine medium** CPU model. Original FP32 weights
  are exported with Moonshine's public tool into native incremental graphs.
  The service checks a pinned manifest; it does not silently choose INT8.
- This helper provides STT only. TTS engines and their settings retain their
  existing behavior.

On the phone, use **Settings → Voice input → Transcribe on server**, then
**Save voice settings**, for server streaming. With the debugging voice interface
off, tapping the composer microphone starts Call mode; tapping again ends it.
Hold-to-dictate remains separate. The detailed panel can be enabled under
**Settings → Voice input → Debugging voice interface**.

## CPU measurements

Host: PowerMac, AMD Ryzen 5 7600, six cores/twelve threads. Four pinned public
fixtures total 48.5 seconds and 102 reference words, with three warm runs per clip.
RTF is processing seconds divided by audio seconds; lower is faster.

| Backend | Aggregate RTF | Speed relative to real time | Word errors |
| --- | ---: | ---: | ---: |
| Transformers FP32 medium, six threads | 0.097 | 10.3× | 2/102 |
| Native INT8 medium, runtime threads | 0.157 | 6.4× | 2/102 |
| Native INT8 small, runtime threads | 0.126 | 7.9× | 3/102 |
| Native exported FP32 medium, runtime threads | 0.074 | 13.5× | 3/102 |

The original comparison includes runtime and thread differences, not just
quantization. The native FP32 export was about 2.1× faster than native INT8 medium.
Its extra error was the homophone “tale” for “tail.” Four clips, including a
synthetic traffic mix, do not establish broad accuracy or car-microphone quality.
Whole-process peak RSS includes benchmark/import/hash overhead; see raw JSON.

The first INT8 streaming replay produced first partials in 0.67–1.40 seconds and
finalized 6–24 ms after the last uploaded audio. The initial physical-phone path
produced 17 partials, first at 1.339 seconds, and finalized in 93 ms through ADB
forwarding. These are **historical INT8 measurements**, not claims about FP32.
The final FP32 replay through the deployed gateway produced first partials in
0.61–1.23 seconds, with 6–25 ms finalization after upload. Its phone check
produced 17 partials, first at 1.254 seconds, and finalized in 50–59 ms across the two FP32 checks. The original
FP32 export timing was 0.081 RTF; the final repeated run was 0.074. Raw results
and conditions are recorded separately in the JSON/logs.
The fixtures contain about two seconds of trailing silence: final-after-upload
latency is not end-of-speech latency, and excludes Bellpull's turn-end gate,
network variability, Hermes inference and TTS.

## Validation and boundaries

- 714 JVM checks, one existing live-server skip; 187 unchanged screenshot
  baselines; lint; ARM64/x86_64 release assembly and signing.
- Plugin: 168 checks, one existing legacy skip; 15 checks against modern stock
  Hermes. Real WebSocket tests cover partials, cancellation, auth, profile paths,
  redirects and compatibility fallback.
- Helper: six tests covering auth, separate stream state, overload, malformed
  input and cleanup, including cancellation during native creation.
- Pixel 10 Pro Fold / GrapheneOS Android 17: a disposable fixture package tests
  the actual Telecom call, ten seconds of screen-off operation, hold/resume,
  hang-up and restoration of normal audio mode. A separate native test replays a
  public WAV through the actual Android client, plugin proxy and CPU model.
- Signed personal-app upgrade preserves UID/data directory, with installed APK
  hash matching the release artifact. Test code never clears personal app data.

Remaining acceptance: physical car/headset audio, navigation and real incoming
cellular-call arbitration, extended screen-off/thermal sessions, cellular
reconnection and acoustic echo/cutoff. No claim that these passed. PowerMac was
updated; Moe was not changed.

## Reproduce

See [helper setup](../../../server-speech/README.md), the pinned
`fp32-export-manifest.json`, `tools/benchmark-server-stt.py`,
`tools/benchmark-streaming-stt.py`, and the fixture hashes in each result.
Model weights and private credentials are excluded from the repository/release.
An extra HTTPS probe to a hostname from historical setup notes failed DNS on this
host; it is not evidence of working tailnet/cellular transport. The production
gateway tests above use loopback, and the phone tests use ADB forwarding.
