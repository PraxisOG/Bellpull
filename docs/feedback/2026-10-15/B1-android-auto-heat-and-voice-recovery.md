# B1 — Android Auto plus voice: excessive heat and unreliable recovery

Type: user-reported bug. Reported October 15, 2026.
Related: [F1](F1-continuous-call-style-voice.md), [F2](F2-server-side-speech.md),
[B4](B4-long-dictation-and-retry.md), existing
[#1](https://github.com/PraxisOG/Bellpull/issues/1) and
[#3](https://github.com/PraxisOG/Bellpull/issues/3).

## Observed behavior

While running Android Auto and Bellpull voice mode together, the phone becomes
very hot. Eventually voice asks the owner to retry or reports that another app
is using the microphone. The owner reports no other app using the microphone;
actual microphone ownership has not been inspected.

Retry occasionally restores voice, but usually does not. This has happened
multiple times. Recovery by restarting the app or cooling the phone is unknown.

## Reported reproduction sequence

1. Use Bellpull voice mode while connected to Android Auto.
2. Continue normal conversation until the phone becomes hot and voice fails.
3. Attempt Retry; it usually fails to restore operation.

Time to failure, wired versus wireless connection for these occurrences,
charging/navigation conditions, engine selection and exact error text are unknown.
Without-Android-Auto behavior is **untested by the owner**, who mostly uses voice
in the car.

## Expected behavior

Voice supports sustained car use with reliable recovery and accurate error
reporting. A session must not become stranded behind repeated ineffective Retry.

## Investigation and proposed acceptance

- Correlate thermal status, workload, capture/playback lifecycle, focus/mode/route
  events and errors with timestamps, without recording spoken content or secrets.
- Distinguish actual microphone contention, route/focus loss, thermal cancellation,
  resource leaks and other failures. Do not assume the microphone error is false.
- Thermal throttling is the owner's suspected cause, **not a confirmed diagnosis**.
- Compare sustained voice with and without Android Auto, recording engine,
  connection, charging and navigation conditions.
- Verify recovery after the diagnosed failure without duplicate server submission,
  stale callbacks or multiple concurrent microphone owners.
- Keep behavior during a real phone call and deliberate user pause explicit.
- Record physical-car results separately from automated state tests.
