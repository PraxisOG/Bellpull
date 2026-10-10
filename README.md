# Bellpull — Hermes Android

**[Download Bellpull 0.2.0 for Android (.apk)](https://github.com/PraxisOG/Bellpull/releases/download/android-v0.2.0-preview.1/Bellpull.apk)** · [Release notes and checksum](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.2.0-preview.1)

## Try it on your phone

1. Download **Bellpull.apk** on an Android 10 or newer phone.
2. Open the download and allow your browser or file manager to install this app if Android asks.
3. Open **Bellpull** and tap **Explore a sample chat**. No account or server connection is required.
4. When you want to connect an agent, open **Settings → Server profiles → Add server**.
   Enter a profile name, your Hermes API server URL and API key, then tap **Save and connect**.
   Settings is also available from the chat's three-dot menu.

The sample conversation is local and fictional; it does not run an LLM or contact
an agent. You can explore the layout, copy/read sample messages and change appearance.
No agent, server account or API key is included. This development preview is signed
with the project's current debug certificate; it can update the matching development
build without uninstalling it. Existing connections migrate into an **Existing server**
profile with their local chats and drafts preserved.

Use a reachable HTTPS URL, or your server's Tailscale address. Optional Bellpull
features need the [server extensions](server-patches/README.md); the app checks
what your server supports. Source/build details follow below.

A native Android chat client for a self-hosted [Hermes Agent](https://github.com/NousResearch/hermes-agent), built with Kotlin and Jetpack Compose. Designed for compact, adaptive conversation layouts on a Pixel Fold running GrapheneOS, without Firebase or Google Play Services.

## Current state

Bellpull Phase 2 is implemented: streaming conversations, compact tool/thinking rows, searchable history, context panels, message copying and read-aloud, selected-message branching, prompt editing, themes and Android wallpaper colors. Durable runs recover from disconnects and app restarts; unread conversations rise to the top. Optional ntfy-backed notifications and exact-request tool approvals use the accompanying server extensions.

Phase 2 also includes encrypted named server profiles, switching, sign-out and removal
of saved credentials. Chats, drafts, unread state and run recovery are isolated by profile.
New installations open a useful disconnected workspace with optional setup in Settings.

Projects and Today currently have placeholder pages. Attachments, model/reasoning controls and GPU metrics are planned for Phase 3. Settings now include experimental immersive mode with camera-cutout-aware headers. Side panels slide in 180ms unless Reduce motion or Android's animation settings disable motion. Phone testing of this experiment is complete; see the handoff for the physical-versus-rendered coverage.

The latest completed validation passed 266 JVM tests with one opt-in live smoke test skipped, verified 89 screenshot baselines, passed five emulator instrumentation tests, built the debug APK and reported zero lint errors. Two synthetic servers exercised profile switching during a run, independent drafts, restart and credential removal. The physical phone passed upgrade migration, sign-out, disconnected exploration and reconnection. See [the handoff](docs/HANDOFF.md) for device evidence, existing lint warnings and operational limits. The screenshot gallery in [beauty-shots](beauty-shots/README.txt) uses fictional data and illustrates earlier UI revisions; test baselines reflect the current tested UI.

## Build and test

For the experimental Linux Mint launcher that runs a Bellpull APK in an isolated
Android emulator, see [desktop/mint-emulator/README.txt](desktop/mint-emulator/README.txt).
It builds a `.deb` and portable archive, keeps its own Android data and never targets
a connected phone. This is a desktop preview; a native Compose Desktop port remains
future work. Its existing packaged release contains the earlier 0.1.0 phone preview;
the Android download above contains the current profile and disconnected-workspace update.

Requires JDK 17, Android SDK Platform 37, Python 3 for the local HTTP fixtures, and network access to download Gradle dependencies. Set `ANDROID_HOME` to your SDK directory or create an untracked `android/local.properties` containing `sdk.dir=/absolute/path/to/Android/Sdk`.

From the repository root:

```sh
./android/gradlew -p android test verifyRoborazziDebug :app:assembleDebug :app:lintDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. These checks run locally without a phone or live inference; the live smoke test is opt-in. Device instrumentation includes a credential-clearing test and should not be run indiscriminately on a configured personal phone.

Android 10 (API 29) is the minimum supported version; the current target is API 37. Release signing is optional and configured through ignored `android/keystore.properties` and your own keystore. A release build without those credentials is unsigned.

## Connect a server

Open **Settings → Server profiles → Add server** and enter a name, reachable Hermes API
URL and bearer key. The verified deployment uses Tailscale; provide your own server
access and credentials. Keys are stored through Android encrypted preferences and
are never part of this repository.

Save separate profiles for a desktop test server and a compute server, then tap
**Use profile** to switch. **Sign out** returns to the disconnected workspace while
keeping saved profiles. **Remove** deletes one profile; **Remove all credentials**
deletes all saved server URLs, keys and local account data, including cached chats,
drafts and recovery records. Neither option deletes remote chats or revokes server
keys. Switching or signing out leaves remote work running; saved profiles recover
their work when selected again. Notifications follow the active profile.

[docs/hermes-api.md](docs/hermes-api.md) is the API contract. Some features require the capability-gated extensions in [server-patches](server-patches/README.md); they are not assumed to exist in an arbitrary upstream Hermes installation. The patches include their tested base versions, build order, isolated tests and deployment caveats. No live server configuration or credentials are included.

## Where to start

- [docs/HANDOFF.md](docs/HANDOFF.md): latest implementation, verification and remaining work, followed by historical entries.
- [ROADMAP.md](ROADMAP.md) and [Bellpull-Implementation-Prompt.txt](Bellpull-Implementation-Prompt.txt): Bellpull direction and phased work. Later user refinements recorded in the handoff supersede the original brief.
- [CLAUDE.md](CLAUDE.md): repository conventions and constraints; older status sections should be read alongside the latest handoff.
- `android/app`: Compose UI, local state, recovery, notifications and Android services.
- `android/core-hermes` and `android/core-model`: HTTP/SSE client and shared data types.
- `mock-hermes`: local contract-test fixtures.
- `desktop/mint-emulator`: isolated Linux Mint emulator launcher, packaging and smoke fixture.
- `server-patches`: versioned server extensions needed by the current client.
- `phone-ping-plugin` and [ROADMAP-TIER2.md](ROADMAP-TIER2.md): the older phone-bridge track, separate from Bellpull Phase 2.

The upstream Hermes code included under `server-patches` retains its [MIT notice](server-patches/LICENSE.hermes-agent). No project-wide open-source license has been selected for the app.
