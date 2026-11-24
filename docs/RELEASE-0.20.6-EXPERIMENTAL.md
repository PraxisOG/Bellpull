# Bellpull 0.20.6 experimental

Android build **56** fixes hold-to-dictate in the chat composer on devices without a working text-to-speech engine. Install the signed APK over build 55. Existing installations of Bellpull plugin **0.9.4-mobile-preview** already support this update.

On build 55, the system keyboard could insert a transcript while the composer paused with a speech output error. Build 56 checks the selected recognizer and starts a microphone-only session for the held gesture. Releasing the mic inserts the words into your draft. If the field becomes unavailable, the recognized text remains available for review. [Android fix](https://github.com/PraxisOG/Bellpull/pull/74)

## Verification

The Android device comparison uses real touch gestures, a virtual microphone and on-device Moonshine Tiny inference on an API 36 emulator. It replays four pinned public speech clips into the same chat draft through both the keyboard and the composer. The corpus covers three speakers, natural pauses, spoken measurements and traffic noise. The full reference texts and audio hashes are in the [fixture manifest](../benchmarks/speech/fixtures-manifest.json). Physical microphone and other-model comparisons remain open in [issue #32](https://github.com/PraxisOG/Bellpull/issues/32).
