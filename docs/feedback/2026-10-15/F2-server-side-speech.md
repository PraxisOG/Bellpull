# F2 — Server-side dictation and speech generation

Type: improvement / architecture evaluation. Reported October 15, 2026.
Separate from [F1: continuous call-style session](F1-continuous-call-style-voice.md).
Related: [B1](B1-android-auto-heat-and-voice-recovery.md),
[B4](B4-long-dictation-and-retry.md), [current voice output](../../VOICE-OUTPUT.md).

## User goal and preference

The owner reports that Bellpull currently performs dictation and speech generation
on-device. It is impressive for what it is, but recognition and voice quality are
not satisfactory. Evaluate off-device speech processing, preferably on the owner's
self-hosted server, while the phone captures/transports microphone audio and plays
the returned speech.

**Some added latency is acceptable in exchange for better quality and reliability.**
No specific model, provider, protocol, hardware allocation or numeric latency
budget was selected.

## Evaluation and proposed acceptance

- Evaluate server-side speech recognition and speech generation as independently
  selectable/configurable components.
- Establish the existing input/output paths and benchmark them before selecting
  replacements; document actual engine/model choices.
- Prefer incremental audio transport/playback where supported to avoid waiting for
  a whole long answer before speech begins.
- Compare recognition on longer utterances and realistic car noise, listening
  quality, end-of-turn-to-first-audio latency, phrase gaps and full-answer timing.
- Measure phone CPU/memory/thermal behavior, bandwidth and server resource use.
  Reduced phone inference work may help; reduced heat is not yet demonstrated.
- Define network-loss, timeout, cancellation, retry and reconnect behavior without
  duplicate message admission or replay of canceled speech.
- Show the selected processing location and failures. Preserve the repository's
  explicit-choice/no-silent-model-substitution rule.
- Define any new Bellpull-owned API contract in `docs/hermes-api.md` before
  implementing it; no speech endpoint is assumed to exist.

## Relationship to other work

F1 can establish continuous call behavior without changing the speech engines.
F2 can improve speech processing without proving seamless call/session handling.
Keep the requests separate and coordinate their integration/acceptance.
Neither replaces diagnosis of B1 or repair of B4's retry path.

This note authorizes evaluation as a requested feature; it does not authorize
personal-server deployment, an external speech-provider purchase or a new release.
