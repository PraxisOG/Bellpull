# Kokoro sentence streaming — 0.14.6

The user requested sentence chunks to mimic streaming. Live voice and Read aloud
now share sentence boundaries, including closing quotes and common English titles,
initials and decimals. Long sentences split at clauses/word boundaries. Code and
links use the existing spoken-text cleanup. Final fragments and long answers are
retained; reconnect/replay handling remains in the existing sentence tracker.

Kokoro still generates each bounded phrase in one ONNX invocation. Its former
1.6-second PCM queue blocked the model behind playback before it could start the
next sentence. The Kokoro queue now holds up to 60 seconds per request, matching
the native output limit. Active and one-ahead queues hold about 11.5 MB maximum.
Other engines retain the smaller buffer. VoiceSession replenishes lookahead after
every sentence, including when the server has finished and sends no more updates.
Stop invalidates and clears active/ahead work; it does not await an ONNX operation.

## Phone measurement

Pixel 10 Pro Fold, GrapheneOS/API 37, CPU FP32/four threads, Heart voice, speed 1.0,
real Android AudioTrack, public fixture text only. The isolated fixture app used
the same warmed native Kokoro engine for both runs. Selecting the generic queue
configuration in the baseline changes buffering only; no Qwen model was loaded.

| Metric | Former 1.6-second queue | Sentence lookahead |
| --- | ---: | ---: |
| First playback callback, model already loaded | 2.053 s | 2.100 s |
| Second sentence generation begins | 4.031 s | 2.045 s |
| Third sentence generation begins | 7.484 s | 5.948 s |
| Gaps between sentence callbacks | 35 / 186 ms | 68 / 61 ms |
| Entire three-sentence playback | 13.181 s | 13.090 s |

With lookahead, sentence two finished generating at 3.607 s while sentence one
continued playing until 5.923 s. Sentence three finished at 7.885 s while sentence
two continued until 9.350 s. Stop during another playback returned in **14.25 ms**;
the output successfully spoke a fresh request afterward, with no stale completion.
[Raw timing rows](timings.json).

These are two short runs, not a statistically stable speed benchmark. The model
itself is unchanged. Callback gaps exclude silence inside synthesized audio and
are not microphone measurements. Initial loading, long-session thermals, acoustic
quality and actual car/head-unit latency were not remeasured in this increment.

## Validation

- 731 declared JVM checks: **730 passed, one existing opt-in skip** (app 659,
  core-hermes 45 with one skip, core-model 27).
- Tests cover sentence and quoted-boundary handling, decimals/abbreviations,
  lazy later-sentence phonemization, long replies and Unicode, one-ahead reading,
  refilling after a completed server reply, bounded backpressure and interruption.
- Phone `KokoroVoiceDeviceTest#sentenceLookaheadStartsBeforePlaybackFinishes`
  passed: actual synthesis/playback overlap, both buffer configurations, Stop and
  immediate reuse. Fixture packages and weights are removed after testing.
- Android lint: zero errors, 79 pre-existing warnings; signed ARM64/x86_64 release
  assembly passed. No UI layout or snapshot baseline changes.
- Plugin regression suite: 168 checks, 167 passed and one existing skip, against
  the isolated supported-host image with networking disabled.
- An initial all-tests invocation incorrectly used the device-fixture build flag,
  which disables notification capture and caused three notification tests to fail.
  The full suite passed under the regular app configuration. The initial focused
  speech run caught doubled spaces in long replies; corrected before final checks.

Plugin 0.6.0 is unchanged; no server update is required. The experimental release
keeps the existing signing lineage and upgrades the personal app in place, without
changing voice selection, model downloads, profiles or chats.

APK SHA-256: `ee0e3806343d2c977e9d13df69df580e22715eaeb018d32b0c663735265b974b`.
