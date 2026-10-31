<div align="center">

<img src="docs/images/bellpull-icon.png" alt="Bellpull bell cord icon" width="112" height="112">

# Bellpull

**Your Hermes conversations, projects, voice, and ongoing work.**

A native Android companion for self-hosted [Hermes Agent](https://github.com/NousResearch/hermes-agent), built for phones and foldables.

[![Android 10+](https://img.shields.io/badge/Android-10%2B-59091e?style=flat-square)](#get-started)
[![Experimental](https://img.shields.io/badge/experimental-0.18.2-59091e?style=flat-square)](docs/release.md)
[![MIT license](https://img.shields.io/badge/license-MIT-59091e?style=flat-square)](LICENSE)

[**Build this experiment**](docs/BUILDING.md) · [**Install the plugin**](#connect-hermes) · [**Phase roadmap**](docs/ROADMAP.md) · [**Release notes**](docs/release.md)

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
| **Native tasks** | Browse native Kanban boards, create tasks in triage, edit task fields, add comments, park unclaimed cards in triage/blocked, and recover offline drafts and edits. [Scope and validation](docs/PHASE-18-KANBAN.md). |
| **Projects** | Move chats into one project, pin references, add a description, archive folders, and search within a project or across conversations. |
| **Voice input** | Use downloaded Moonshine locally or stream audio to your server; Call mode integrates with Android Telecom. Tap for conversation or hold for dictation. |
| **Voice output** | Use the installed Android voice, Kokoro with sentence streaming, or on-device Qwen3-TTS and Pocket TTS. Qwen remains slow on the test phone. |
| **Coding activity** | Open a chat → **⋮ → OpenCode activity** to observe Hermes-owned OpenCode children, retained output and read-only workspace diffs. |
| **Reminders and routines** | Use **Today** for persistent reminders and supported native Hermes routine controls. |
| **Today overview** | See today's calendar, due reminders, pending decisions, tracked work and unread replies from existing local records. |
| **Phone assistant** | Choose Bellpull in Android’s assistant picker, use explicit phone commands, or continue spoken/typed words as a new chat draft. |
| **Phone context** | Separately opt into notification/calendar snapshots for new server chats or offline chat; cron conversations have a Notifications view. |
| **Calendar** | Opt into selected phone calendars, view day/week blocks, and separately enable writes. Hermes proposals require a phone Apply action. |
| **Offline conversations** | Automatic foreground model loading, encrypted local chats/forks, chunk compaction, source search, Gemma E2B CPU with optional thinking, and reviewed Hermes handoff. |
| **Local chat lab** | Import a GGUF, run CPU text chat, measure performance and validate Vulkan against CPU. |
| **Files and profiles** | Preview/share persistent files and keep credentials, caches, drafts and organization scoped to each Hermes connection. |

No Firebase or Google Play Services. A Hermes server and its model provider remain
yours to configure. Speech input can run entirely on the phone; model downloads
are required. Bellpull never silently substitutes a speech engine or inference model.

> [!IMPORTANT]
> **This is an experimental prerelease:** Android **0.18.2-experimental / build 47**
> pairs with Bellpull plugin **0.8.1-integration-preview**. Server features need
> the plugin and compatible Hermes capabilities. This Kanban build is local; the
> download links below still point to the prior published release. See the
> [Kanban implementation notes](docs/PHASE-18-KANBAN.md). Phase 10's delivery framework
> and Phase 11's overview are included, plus Phase 12's opt-in notification history and context, the Phase 13 local LLM lab, and Phase 14 offline forks and reconnection.
> 0.15.1 repairs the Reply notifications switch and Choose default assistant button.
> [Settings repairs and validation](docs/RELEASE-0.15.1-EXPERIMENTAL.md).
> Phase 15 adds assistant entry, explicit phone commands, bounded phone-calendar/notification context and a separate Notifications view.
> [Implementation, validation and limits](docs/PHASE-15-ASSISTANT.md).
> Kokoro CPU speech, voice-exit repairs, image previews and reconnect fixes are
> [documented with phone measurements](docs/evidence/voice-media-20261022/README.md).
> Use **⋮ → Thinking level** to change the current model’s reasoning directly.
> [Thinking menu release notes](docs/RELEASE-0.14.8-EXPERIMENTAL.md).
> 0.14.9 fixes startup freezes and memory exhaustion while restoring long replies.
> [Startup repair and device evidence](docs/RELEASE-0.14.9-EXPERIMENTAL.md).
> Plugin 0.6.2 fixes thinking controls for Moe’s named `moe/moe-local` route.
> [Plugin update and installation](docs/RELEASE-PLUGIN-0.6.2-EXPERIMENTAL.md).
> 0.14.10 makes phone-only Telecom voice use the loudspeaker instead of the earpiece.
> [Speaker repair, validation and manual checklist](docs/release.md#phone-loudspeaker--01410--build-42).
> Real Doze/reboot, car audio and cloud calendar sync
> still need acceptance. Supported-host checks and historical compatibility
> failures are reported separately in the evidence.

## Get started

1. [Download the latest experimental pair](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.15.1-experimental.1), or [build this branch](docs/BUILDING.md) for the new Kanban experiment.
2. Install it on Android 10 or newer. Update an existing Bellpull installation in place;
   preserve its data and signing identity rather than uninstalling it.
3. Open the app. **Explore a sample chat** works without a server.
4. Add your server under **Settings → Server profiles → Add server**, using its HTTPS
   API URL and bearer key. Served native profile URLs such as `/p/work` are supported.
5. For on-device voice, choose and download a model in **Settings → Voice input**.
   Choose speech playback separately under **Voice output**.

[Complete source archive](https://github.com/PraxisOG/Bellpull/releases/download/android-v0.15.1-experimental.1/Bellpull-source-0.15.1.tar.gz) ·
[Build instructions](docs/BUILDING.md) ·
[Release history](https://github.com/PraxisOG/Bellpull/releases) ·
[Checksums](https://github.com/PraxisOG/Bellpull/releases/download/android-v0.15.1-experimental.1/SHA256SUMS)

## Connect Hermes

For this branch, use `plugins/bellpull` (0.7.0) from the same source checkout.
The [older 0.4.3 bundle](https://github.com/PraxisOG/Bellpull/releases/download/android-v0.12.0-experimental.1/bellpull-plugin-0.4.3-integration-preview.tar.gz)
ships with the published 0.12.0 release and lacks offline memory/handoff. Back up the data home, then preview the installer:

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

[Installation, compatibility and rollback](plugins/bellpull/README.md) ·
[Combined acceptance and known failures](docs/INTEGRATION-6-8-9.md)

## Verification and scope

The [car and streaming evidence](docs/evidence/car-streaming-20261020/README.md)
records Android Telecom screen-off/hold/resume/end checks on a physical phone,
CPU STT comparisons, and live audio through the plugin. The server defaults to an
FP32 Moonshine medium export after it outperformed INT8 on the test host.
Physical car/headset acoustics, navigation interruptions and sustained heat remain
open; this is an experimental voice increment, not Android Auto.

The 0.15.0 assistant/context increment passes **758 Android checks with one expected skip**, screenshot verification and lint. Plugin 0.7.0 passes **187 checks with one expected skip**, plus 12 context checks on each supported stock host. Physical fixtures at both widths verify assistant state and phone sources → plugin → native chat prompt → revocation, using synthetic inference. [Phase 15 evidence and limits](docs/PHASE-15-ASSISTANT.md).

The 0.14.10 speaker repair passes **50 focused voice JVM tests**, lint and a real
phone Telecom test covering speaker routing, screen-off, hold/resume, repeated
starts/stops and audio cleanup. The signed update preserves personal app data.
The 0.14.9 foundation passed **740 JVM checks, one expected skip**, lint and signed
release assembly. Unchanged plugin 0.6.2 passes **176 checks, one expected skip**, eight targeted
stock-host checks and seven live Moe inferences through an isolated named-route fixture.
The app’s four thinking-menu regression checks also passed for that plugin update. The preceding 0.14.8 UI increment
verified 191 screenshot baselines, including four new menu views. The 0.14.9 repair
added long-replay regression checks and a physical Android memory/latency test. Six helper tests cover
isolation, malformed audio, overload, authentication and cleanup. See the
[release record](docs/release.md) for physical-device evidence and limitations.

## For contributors and agents

**`master` is the default and current development source for both the Android app
and the Bellpull plugin.** Earlier work is preserved in Git history and release
tags. Use short-lived branches for new changes and merge them back here.

```bash
git clone --recurse-submodules https://github.com/PraxisOG/Bellpull.git
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
| [Phase roadmap](docs/ROADMAP.md) | Implementation status and active work: native Hermes Kanban (Phase 18). |
| [Handoff](docs/HANDOFF.md) | Current evidence, limitations and historical decisions. |
| [API contract](docs/hermes-api.md) | Verified native contracts and Bellpull-owned routes. |
| [Repository instructions](CLAUDE.md) | Scope, dependencies and deployment boundaries. |
| [Build/signing notes](docs/release.md) | Signing lineage, updates and rollback limits. |

The [Linux Mint emulator launcher](desktop/mint-emulator)
is a separate earlier experiment. Desktop packaging, on-device LLMs, offline agent
conversations and Android Auto are not requirements for this preview.

---

Bellpull is [MIT licensed](LICENSE). Hermes and bundled libraries retain their own
notices in the repository and in **Settings → About**. No server credentials,
private phone captures or model weights are included in the release.
