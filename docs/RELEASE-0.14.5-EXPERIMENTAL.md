# Bellpull 0.14.5 experimental

Kokoro 82M now runs locally for spoken replies and Read aloud. The update also
fixes the voice-exit crash, moves OpenCode activity into the conversation menu,
adds adjustable image thumbnails and repairs reconnect response duplication.

- **Android:** 0.14.5-experimental, build37.
- **Plugin:** 0.6.0-integration-preview, unchanged; no plugin update required.
- **Kokoro:** Settings → Voice output → Kokoro 82M → Kokoro model → Download
  Kokoro, then Preview and Save voice output. Four US English voices; 336.78 MB
  verified download. Existing voice selection is preserved.
- **Performance:** four-thread FP32 generated 4.9 seconds of speech in 2.26–2.50
  seconds on Pixel 10 Pro Fold, about 2.86× faster than INT8 on the same sample.
  Short-phrase first audio takes about 0.8–2.7 seconds warm; cold verified loading
  took 4.5 seconds. Phrase generation and playback overlap.
- **Images:** Settings → Display → Image preview size. Tap thumbnails to open.
- **OpenCode:** conversation's top-right More → OpenCode activity.

Validation: 724 passing JVM tests and one opt-in skip; 187 screenshot baselines;
Android lint with no errors; ARM64/x86_64 release builds. Physical-phone tests
cover native Kokoro synthesis/playback/interruption, all four voices, and Telecom
screen-off/pause/resume/hang-up, including repeated app exits with Kokoro active.
Plugin suites: 168 legacy-host tests (one skip), 25 modern stock coding tests
(one skip); six CPU speech-helper tests.

English heteronyms use default dictionary pronunciations. Real car head-unit
acoustics and long-session behavior still need vehicle testing. Full evidence and
raw performance data: [voice/media acceptance](evidence/voice-media-20261022/README.md).

Update the APK in place to preserve profiles, chats, settings and downloaded
models. Source, plugin, roadmap and checksums accompany the prerelease:
[android-v0.14.5-experimental.1](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.14.5-experimental.1).

APK SHA-256: `b20e924e03673f3151c42d547d590dea7195fcbf1f3b7a2b6928cf95b9991f99`.
