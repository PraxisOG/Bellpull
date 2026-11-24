# Bellpull 0.20.4 experimental

Android build **54** pairs with plugin **0.9.3-mobile-preview**. Install the signed APK over 0.20.3. Update the plugin for shared file paths, compaction events and the plugin build identity. Operators running the batch-only ROCm speech helper should also apply the included helper update. [Plugin installation](../plugins/bellpull/README.md) · [Speech helper](../server-speech/README.md)

## Changes

- Choose server voice, on-device voice, or server voice with on-device fallback for each conversation. Android can transcribe through a batch-only GPU helper and stream 24 kHz Kokoro playback. The GPU returns the transcript after recording ends. CPU Moonshine supports live partials.
- Enable Bellpull Dictation as an Android keyboard to use an installed Moonshine model in other apps. Dictation inserts at the cursor and blocks password fields.
- Edit the voice-turn instructions or enable a separate typed-turn preset in Settings. Chat shows when Hermes is compacting history and restores the composer's position when the keyboard closes.
- Open a text file by path within directories the server owner has shared. Refresh it in place, save a copy or share it. [Shared paths](LIVE-SERVER-FILES.md)
- Tap shapes or connectors to include them in a Canvas request. Bellpull keeps the draft when the selected revision changes. Export SVG or PNG through Android's document picker or upload it to the current conversation. [Canvas](SHARED-CANVAS.md)
- Review individual pending approvals in Today while keeping the current chat open. Bellpull rechecks the run, operation and request before sending a decision. The host controls which approvals it makes available.
- The plugin reports its protocol, version and source checksum. A packaged installation also reports the release commit.

## Verification

An API 36 foldable emulator exercised keyboard dismissal, chat instructions, per-chat voice selection, dictation into Android Settings, shared paths, and Canvas export. SVG and PNG files saved through the document picker matched their conversation-library downloads by SHA-256 in compact and wide layouts.

An Android client using the live PowerMac gateway transcribed a synthetic sentence with local fallback disabled. Streamed read-aloud began in 167 ms in the combined-build check. The benchmark records CPU and GPU latency, including the cost of simultaneous transcription and synthesis. Physical car routing, human audio quality and the physical Canvas check over Tailscale remain open. [Speech measurements](SERVER-VOICE-FILES-2026-11-15.md)

The release includes the signed APK, plugin and speech-helper bundle, source archive with recursive submodules, phase roadmap, source revision manifest and checksums. [Download 0.20.4](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.20.4-experimental.1)
