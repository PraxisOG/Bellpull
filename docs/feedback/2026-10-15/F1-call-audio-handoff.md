# Bellpull 0.8.5 call/audio repair — installation handoff

This branch is based on `repair/qwen-asr-server-stt` commit `cb159af`, the
published 0.8.5 source. The app is `0.8.5-call-audio`, version code 27. No phone
installation, uninstallation, data clearing, or live server changes were made
by this task. Build with the existing installation's signing key and update in
place. Preserve the current profiles and downloaded models.

## Changes

- Tapping the conversation microphone starts **Call mode**. It keeps one
  `AudioRecord` open through transcription, waiting, and playback, without
  per-turn beeps. Frames during transcription are drained and discarded;
  simultaneous recognition of a second utterance is not implemented.
- Pause, End, capture failure and competing-call handling release the mic.
  Notification controls remain available with the screen off. A renewable,
  bounded partial wake lock keeps the active session running; pause/end releases
  it. Bluetooth route acquisition has a 30-second deadline and tolerates the
  intermediate routing callbacks before connection.
- MOSS and server MOSS play 48 kHz PCM; Qwen TTS keeps 24 kHz. Stream usage and
  sample rate are passed independently. Preview audio uses the requested route.
- MOSS uses the complete pinned ONNX runtime AAR. Only Moonshine's reduced
  runtime is removed by an artifact transform. The broad packaging exclusion
  and duplicate checked-in runtime binaries are removed. The resulting APK's
  runtime matches the official AAR after Android's normal symbol stripping and
  exports the required NNAPI symbol.
- The native MOSS tokenizer now uses SentencePiece's binary ModelProto reader,
  with its license notices, instead of a text-vocabulary reader that exits the
  process on the bundled model.
- Prefetch waits for the current synthesis producer before acquiring the model
  lock, preventing a full ahead queue from blocking the audible phrase.
- Held push-to-talk keeps quiet speech without requiring the hands-free onset
  threshold. Empty server recognition is treated as silence. Invalid TTS content
  types are rejected before playback, and server failures identify whether the
  profile, plugin, or helper needs attention.

Android communication routing reference:
[self-managed AudioManager calls](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager).
This uses communication audio routing; it does not register a carrier call or
add Android Telecom/Android Auto call UI integration.

## Validation completed

- ARM64 `:app:assembleDebug` on the 0.8.5 base.
- `:app:lintDebug`: zero errors; existing warnings remain.
- 83 focused tests covering call mode, voice session transitions, signal gating,
  PCM formats, playback/prefetch, MOSS and server transport.
- Call-mode regression: 100 turns, one capture start, zero per-turn capture
  stops or chimes. Separate tests cover pause, interruption, retry, quiet held
  speech, and discarding frames during recognition.
- Five server proxy tests against the pinned Hermes container: authentication,
  multipart audio, streaming, timeouts, and size limits.
- Six voice-panel screenshots recorded and verified for cover/unfolded layouts.

Build and targeted test command, from `android/`:

```sh
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest \
  --tests '*CallModeTest' --tests '*VoiceSessionTest' \
  --tests '*VoiceSignalTest' --tests '*QwenVoiceOutputTest' \
  --tests '*VoicePcmFormatTest' --tests '*ProfileSpeechTransportTest' \
  --tests '*ServerSpeechTest' --tests '*MossVoiceOutputTest' \
  -Pandroid.injected.build.abi=arm64-v8a --max-workers=4
```

## Server installation still required

Read-only checks of this machine's `hermes-gateway` found no
`/opt/data/plugins/bellpull/speech.py`. The configured/default helper's `/health`
was unreachable from the container. Client changes alone cannot enable those
missing services. This may differ from the server profile used by the local
agent; verify the intended profile's actual host.

1. Install this branch's `plugins/bellpull` (`0.4.3-integration-preview`) using
   the existing [plugin installation procedure](../../../plugins/bellpull/README.md).
   Restart the gateway after active runs finish so it registers speech routes.
2. Start the existing speech helper on the intended host. Set
   `BELLPULL_SPEECH_URL` to an address reachable **inside the gateway container**;
   the default is `http://127.0.0.1:8645`. Set `BELLPULL_SPEECH_KEY` there if the
   helper requires it. Keep helper credentials off the phone.
3. Check authenticated `GET /api/bellpull/speech/health` through the phone's
   existing profile URL/key. Check one real microphone WAV through `/stt` and a
   streamed phrase through `/tts`, following the
   [speech API contract](../../hermes-api.md#bellpull-speech-proxy--043-integration-preview).
   The helper implementation and its Qwen ASR model deployment are external to
   this repository; the automated proxy tests use a synthetic helper.
4. Install the app update with the existing signing key, keeping app data.
   Confirm the selected on-device and server engines separately, then call mode
   with the actual car audio route and screen locked.

No live-server ASR/TTS quality test, real-car acceptance, or multi-hour thermal/
screen-off soak was completed. Network loss still pauses voice for an explicit
resume/retry, preserving the existing protection against duplicate turns.
