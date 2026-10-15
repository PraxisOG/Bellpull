# B4 — Dictation fails after one or two sentences; Retry never succeeds

Type: user-reported bug. Reported October 15, 2026.
Related: [B1](B1-android-auto-heat-and-voice-recovery.md),
[F1](F1-continuous-call-style-voice.md), [F2](F2-server-side-speech.md).

## Observed behavior and reproduction

1. Start a voice utterance and attempt to speak a longer thought.
2. After roughly one or two sentences, transcription fails.
3. The app offers Retry transcription (the owner's description, not a verified
   literal UI string).
4. Retry never succeeds in the owner's experience.

Longer spoken messages are effectively unusable. Exact duration, error code,
engine selection, failure timing during versus after capture, and whether audio
or partial text remains available are unknown. Do not claim the recording or
transcript is lost without checking.

## Expected behavior

The owner can finish a longer thought without an unexplained sentence-length
cutoff. If transcription fails, recovery works and clearly explains whether the
original recording can be retried or a new recording is required.

## Investigation and proposed acceptance

- Trace capture bounds, endpointing, duration/size limits, decode resources,
  cancellation and error/retry state transitions; no root cause is yet confirmed.
- Exercise short inputs and progressively longer continuous and naturally paused
  dictation, including car noise. One or two sentences is a symptom, not a measured
  time limit.
- Preserve captured audio or partial text when feasible and appropriate; never
  imply recoverability if the source is no longer available.
- Retry retained audio successfully after a recoverable transcription failure;
  otherwise offer an honest re-record path without a permanent retry loop.
- Ensure retry cannot submit a message twice or reuse stale session results.
- Keep this separate from B1: B1's voice Retry occasionally works, whereas this
  transcription Retry has never worked for the owner.
