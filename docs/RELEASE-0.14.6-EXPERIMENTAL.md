# Bellpull 0.14.6 experimental

Kokoro now uses sentence-based playback consistently in voice mode and Read aloud.
It prepares the next sentence while the current one plays, including after Hermes
has finished the reply. Common abbreviations, decimals and quoted sentences stay
together; long sentences split into bounded phrases. Stop clears queued speech.

- **Android:** 0.14.6-experimental, build38.
- **Plugin:** 0.6.0-integration-preview, unchanged; no server update required.
- **Setup:** select Kokoro in Settings → Voice output. Sentence streaming is
  automatic. Existing voice/model selections are preserved.
- **Phone check:** first playback around 2.1 seconds with the model loaded;
  61–68 ms sentence transitions in the short sample; Stop returned in 14 ms.
  This overlaps whole-phrase generation and playback; initial model loading and
  native synthesis speed are unchanged.

Validation: 730 passing JVM checks and one existing opt-in skip, lint with no
errors, ARM64/x86_64 signed release build, 168 plugin checks (one existing skip),
and physical-phone synthesis/playback, lookahead, interruption and reuse tests.
Detailed methodology and limitations:
[sentence-streaming evidence](evidence/kokoro-sentences-20261022/README.md).

Update in place to preserve profiles, chats, settings and downloaded models.
[Download the experimental release](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.14.6-experimental.1).

APK SHA-256: `ee0e3806343d2c977e9d13df69df580e22715eaeb018d32b0c663735265b974b`.
