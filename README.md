<div align="center">

<img src="docs/images/bellpull-icon.png" alt="Bellpull bell cord icon" width="112" height="112">

# Bellpull

**Your Hermes conversations, projects, voice, and ongoing work.**

A native Android companion for self-hosted [Hermes Agent](https://github.com/NousResearch/hermes-agent), built for phones and foldables.

[![Android 10+](https://img.shields.io/badge/Android-10%2B-59091e?style=flat-square)](#get-started)
[![Development preview](https://img.shields.io/badge/preview-0.8.3-59091e?style=flat-square)](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.8.3-integration-preview.1)
[![MIT license](https://img.shields.io/badge/license-MIT-59091e?style=flat-square)](LICENSE)

[**Download the preview**](https://github.com/PraxisOG/Bellpull/releases/download/android-v0.8.3-integration-preview.1/Bellpull.apk) · [**Install the plugin**](#connect-hermes) · [**Phase roadmap**](docs/ROADMAP.md) · [**Release notes**](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.8.3-integration-preview.1)

</div>

---

## A place for the work already in Hermes

Chat with live thinking and tool activity. Organize conversations into projects.
Talk with on-device speech recognition. Follow coding work without leaving the
conversation, and keep reminders and calendar blocks nearby.

| Conversations and context | Fold-aware workspace |
| :---: | :---: |
| [<img src="docs/images/unfolded-dark.png" alt="Dark conversation view with compact responses and a context panel" width="430">](docs/images/unfolded-dark.png) | [<img src="docs/images/unfolded-maroon.png" alt="Maroon chat layout adapted around a foldable camera cutout" width="430">](docs/images/unfolded-maroon.png) |

| Projects | Coding activity | Calendar blocks |
| :---: | :---: | :---: |
| [<img src="docs/images/projects-preview.png" alt="Project overview with a description, pinned chat and running conversation" width="250">](docs/images/projects-preview.png) | [<img src="docs/images/coding-preview.png" alt="OpenCode activity showing tool output and a response" width="250">](docs/images/coding-preview.png) | [<img src="docs/images/calendar-preview.png" alt="Day calendar with overlapping appointments and an unfinished reminder" width="250">](docs/images/calendar-preview.png) |

*Rendered screenshots use fictional data. The conversation gallery shows 0.3.3;
the project, coding and calendar panels show the development preview.*

## What is in the preview?

| Area | What you can do |
| :--- | :--- |
| **Chat and recovery** | Stream text/thinking, inspect tool activity, preserve drafts, recover admitted runs, branch and edit where the server supports it. |
| **Projects** | Move chats into one project, pin references, add a description, archive folders, and search within a project or across conversations. |
| **Voice input** | Use downloaded Moonshine Tiny, Small or Medium Streaming locally; tap for conversation mode or hold for dictation. |
| **Voice output** | Use the installed Android voice, or experiment with on-device Qwen3-TTS and Pocket TTS. Qwen remains slow on the test phone. |
| **Coding activity** | Open a chat → **Activity** to observe Hermes-owned OpenCode children, retained output and read-only workspace diffs. |
| **Reminders and routines** | Use **Today** for persistent reminders and supported native Hermes routine controls. |
| **Calendar** | Opt into selected phone calendars, view day/week blocks, and separately enable writes. Hermes proposals require a phone Apply action. |
| **Files and profiles** | Preview/share persistent files and keep credentials, caches, drafts and organization scoped to each Hermes connection. |

No Firebase or Google Play Services. A Hermes server and its model provider remain
yours to configure. Speech input can run entirely on the phone; model downloads
are required. Bellpull never silently substitutes a speech engine or inference model.

> [!IMPORTANT]
> **This is an experimental prerelease:** Android **0.8.3-integration-preview / build 22**
> pairs with Bellpull plugin **0.4.1-integration-preview**. New server features need
> the plugin and compatible Hermes capabilities. Background/offline reminder alerts
> are planned for Phase 10. Real OpenCode/provider, car audio and cloud calendar sync
> still need acceptance. Supported-host checks and historical compatibility
> failures are reported separately in the evidence.

## Get started

1. Download [**Bellpull.apk**](https://github.com/PraxisOG/Bellpull/releases/download/android-v0.8.3-integration-preview.1/Bellpull.apk) from the development release.
2. Install it on Android 10 or newer. Update an existing Bellpull installation in place;
   preserve its data and signing identity rather than uninstalling it.
3. Open the app. **Explore a sample chat** works without a server.
4. Add your server under **Settings → Server profiles → Add server**, using its HTTPS
   API URL and bearer key. Served native profile URLs such as `/p/work` are supported.
5. For on-device voice, choose and download a model in **Settings → Voice input**.
   Choose speech playback separately under **Voice output**.

[Complete source archive](https://github.com/PraxisOG/Bellpull/releases/download/android-v0.8.3-integration-preview.1/Bellpull-source-0.8.3.tar.gz) ·
[Build instructions](docs/BUILDING.md) ·
[Earlier 0.3.3 preview](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.3.3-preview.1) ·
[Checksums](https://github.com/PraxisOG/Bellpull/releases/download/android-v0.8.3-integration-preview.1/SHA256SUMS)

## Connect Hermes

The release includes a [**matching plugin bundle**](https://github.com/PraxisOG/Bellpull/releases/download/android-v0.8.3-integration-preview.1/bellpull-plugin-0.4.1-integration-preview.tar.gz).
Unpack it on your Hermes host, back up the data home, then preview the installer:

```bash
python3 tools/install-plugin.py --home /YOUR/HERMES/DATA/HOME
python3 tools/install-plugin.py --home /YOUR/HERMES/DATA/HOME --apply
HERMES_HOME=/YOUR/HERMES/DATA/HOME hermes plugins disable bellpull_gpu
HERMES_HOME=/YOUR/HERMES/DATA/HOME hermes plugins enable bellpull
```

Restart the gateway with its existing service manager, then reconnect Bellpull to
refresh discovery. Skip disabling `bellpull_gpu` if it is not installed. Use the
actual data home, including for a named Hermes profile. **Stock installation uses
one plugin; no Docker or core-file replacements are required.** Existing legacy
core-overlay installations need the documented migration rather than an overlay.

[Installation, compatibility and rollback](https://github.com/PraxisOG/Bellpull/blob/repair/experimental-open-issues/plugins/bellpull/README.md) ·
[Combined acceptance and known failures](https://github.com/PraxisOG/Bellpull/blob/repair/experimental-open-issues/docs/INTEGRATION-6-8-9.md)

## Verification and scope

[The October 14 project audit](docs/AUDIT-2026-10-14.md) covers app 0.8.3/build22
and plugin 0.4.1. **Six reproduced defects remain unresolved**, including Git filter
execution during workspace preview and a race that can restore calendar data after
sharing is revoked. Dependency findings and physical audio-route acceptance also
remain open. This release is experimental, with those limitations carried forward.

The audit's fresh checks passed **574 Android tests with one skip**, **172 screenshot
baselines**, debug/release builds and lint. The plugin suite passed **126 tests with
one skip**; supported stock-host suites also passed. Earlier isolated phone checks
cover the voice repairs and compact prompt disclosure. The published APK was
verified byte-for-byte against the phone's installed 0.8.3 build22.

[Release verification and current CI status](docs/RELEASE-0.8.3-EXPERIMENTAL.md) ·
[Voice repair evidence](docs/EXPERIMENTAL-ISSUE-REPAIRS.md) ·
[Compact prompt evidence](docs/VOICE-PROMPT-DISCLOSURE.md)

## For contributors and agents

The preview source is on **[`repair/experimental-open-issues`](https://github.com/PraxisOG/Bellpull/tree/repair/experimental-open-issues)**.
The default branch retains the earlier app code with updated documentation;
use the repair branch or release tag to inspect or build this preview.

```bash
git clone --branch repair/experimental-open-issues --recurse-submodules https://github.com/PraxisOG/Bellpull.git
cd Bellpull
./android/gradlew -p android test verifyRoborazziDebug assembleDebug lintDebug
```

Use JDK 21, Android SDK platform 37/build-tools 37.0.0, NDK 29.0.14206865 and
CMake 3.31.6. Local fixtures use Python 3. Debug APKs are separate from the signed
release and must not be assumed to update a personal installation. Instrumentation
uses the disposable fixture package; some tests clear credentials and must never
be run indiscriminately against a configured personal app.

| Read first | Purpose |
| :--- | :--- |
| [Build from source](docs/BUILDING.md) | Recursive clone, complete source archive, pinned dependencies and signing. |
| [Phase roadmap](docs/ROADMAP.md) | Phases 0–17, revised scope and honest implementation status. |
| [Handoff](https://github.com/PraxisOG/Bellpull/blob/repair/experimental-open-issues/docs/HANDOFF.md) | Current evidence, limitations and historical decisions. |
| [API contract](https://github.com/PraxisOG/Bellpull/blob/repair/experimental-open-issues/docs/hermes-api.md) | Verified native contracts and Bellpull-owned routes. |
| [Repository instructions](https://github.com/PraxisOG/Bellpull/blob/repair/experimental-open-issues/CLAUDE.md) | Scope, dependencies and deployment boundaries. |
| [Build/signing notes](https://github.com/PraxisOG/Bellpull/blob/repair/experimental-open-issues/docs/release.md) | Signing lineage, updates and rollback limits. |

The [Linux Mint emulator launcher](https://github.com/PraxisOG/Bellpull/tree/repair/experimental-open-issues/desktop/mint-emulator)
is a separate earlier experiment. Desktop packaging, on-device LLMs, offline agent
conversations and Android Auto are not requirements for this preview.

---

Bellpull is [MIT licensed](LICENSE). Hermes and bundled libraries retain their own
notices in the repository and in **Settings → About**. No server credentials,
private phone captures or model weights are included in the release.
