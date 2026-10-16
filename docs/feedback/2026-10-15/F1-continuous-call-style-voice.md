# F1 — Continuous call-style voice session

Type: feature request. Reported October 15, 2026.
Priority: **Urgent — owner's highest priority.**
Separate from [F2: server-side speech](F2-server-side-speech.md).
Related bugs: [B1](B1-android-auto-heat-and-voice-recovery.md) and
[B4](B4-long-dictation-and-retry.md).

## User goal

The owner wants the previously discussed voice-call simulator working urgently.
Current use repeatedly produces microphone-ending/interruption sounds after chat
exchanges. The desired experience is one sustained call-like conversation across
turns, with smooth listening/speaking transitions.

The sound's actual source and the current microphone/session lifecycle have not
been verified in this reporting session. Do not claim a particular Android call
API, routing mode or continuous-capture change will eliminate it before testing.

## Requested behavior and proposed acceptance

- Maintain one coherent voice session across multiple user/assistant exchanges.
- Avoid unnecessary microphone/audio-session teardown and reacquisition between
  turns. Preserve legitimate pause/end and competing-call handling.
- Verify that the reported repeated sounds/interruptions disappear during normal
  exchanges, including actual Android Auto use.
- Keep listening/speaking state clear and allow the user to end the session.
- Verify long spoken input and sustained streamed output, interruptions, recovery,
  background/screen-lock transitions and car audio behavior.
- Measure sustained thermal/resource behavior; F1 is not established as a fix
  for overheating or transcription failure.
- Record remaining platform/route limitations honestly.

## Scope

This is a documented request, not an implemented call service. Choose a supported
audio/session design after inspecting current ownership and platform behavior.
A continuous session does not by itself change ASR/TTS quality or processing
location; those are F2's separate concerns.
