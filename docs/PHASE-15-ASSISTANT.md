# Phase 15: phone assistant entry and first device actions

Experimental release: **0.15.0-experimental / build 43**, plugin **0.7.0-integration-preview**.
Branch `feature/phase15-phone-assistant`, based on `4cf7c98`.

Settings → Phone assistant offers Android’s default-assistant picker and **Try
assistant**. The activity handles `ACTION_ASSIST` and the platform voice-assist
launch action, reusing the existing task when the caller supplies the normal
single-top/clear-top flags. An assistant request opens a command panel without
creating or replacing a conversation. Repeated entry preserves its request;
rotation restores the panel and text. Dismissed panels stay dismissed on recreation.

The user chooses Bellpull in the system picker. This implementation uses Android’s
activity-based assistant route, not a persistent VoiceInteractionService. OEM
button/gesture behavior needs manual acceptance after selection. No wake word,
lock-screen assistant, screen reading, accessibility automation, or root integration
is included. The panel checks the lock state before executing phone actions.

## Phone actions

- `timer for 10 minutes`, including spoken numbers and `1 hour and 30 minutes`.
  Duration is bounded to 1 second–24 hours.
- `alarm for 7:30 am` or `alarm at 19:30`. Ambiguous 12-hour times ask AM/PM;
  dated/recurring requests are left to Clock.
- `open Maps` / `launch Signal`. Exact unique labels open directly; ambiguous or
  partial matches require choosing an installed launcher activity.
- `open https://example.com`. Only HTTP(S) websites, without embedded credentials.
- `navigate to Union Station`. Opens a map destination; does not claim navigation
  has started.
- `dial 5551234567`; `text 5551234567: Running late`; `share` followed by text.
  Dialer, messaging composer and Android Sharesheet own the final call/send/share.
  Contact-name lookup is not implemented.

These commands use bounded local parsing of direct user input. Model responses,
notifications, and external intent extras cannot supply executable commands. Clock
requests show Clock’s UI; successful intent dispatch is reported as a handoff, not
proof that an alarm was saved. Missing handlers and denied actions remain visible.
No call/SMS permission or server plugin change is needed.

## Speech and chat

Speech uses the existing selected Voice input configuration, including explicit
server transcription and its configured local fallback. With microphone permission,
the panel starts one foreground utterance; otherwise **Speak** requests permission.
No-speech capture ends after 15 seconds; silence after speech ends the utterance.
Closing/backgrounding the panel cancels capture and rejects late transcription.
Existing voice sessions/calls block a competing microphone session. Read-aloud
playback stops before opening the assistant. Recognized direct commands use the
same parser as typed **Go** requests.

Other requests can continue through **Hermes chat** or **Local chat**. Each opens a
new unsent draft; existing drafts stay intact. A server profile is required for
these chat routes. Phone actions themselves work without a profile or model.
Local chat still uses the existing configured Gemma loader and its limitations.
This is the first assistant slice, not arbitrary device-action tools for Hermes
or the local model and not a general natural-language command interpreter.

## Offline banner repair

The large offline/local-fallback notice disappears after five seconds or **Dismiss**.
Android’s accessibility-recommended timeout takes precedence. Model progress and
repeated connection probes do not restart the timeout. Dismissal/deadline survive
Activity recreation; a genuinely new outage gets a new notice. A fresh process
starts a new bounded notice. Both foldable panes share its state. Offline routing,
local loading, and the online-send guard continue to use actual connection state;
hiding the notice does not enable online sends or expose the old cached-list banner.
Local settings and local-fork actions remain in their existing settings/chat menus.

## Phone context and Notifications folder

New controls in Settings → Phone notification context separately opt into server
notification sync, upcoming phone-calendar context, and local-chat context. Existing
per-message excerpt consent does not enable the broader server snapshot. Enabling
server notification sync replaces that profile's legacy excerpt grant. Capture,
Android notification access, exclusions, ongoing-alert choice and clearing still
apply. Calendar context requires read permission and selected calendars; online
context also requires Today's sharing control and the new context switch. Busy-only
remains the default. Nextcloud is not a runtime dependency: its sync adapter can
populate the phone's CalendarContract provider separately.

The app uploads full replacement snapshots to authenticated profile-scoped
`PUT /api/bellpull/context/{device}`. Each persisted monotonic revision supersedes
older uploads; late revisions cannot restore revoked data. The snapshot contains
up to 100 notification observations from the last 24 hours (including removal
state), and up to 200 selected calendar instances over the next seven days.
Provider changes, notification changes and resume trigger coalesced updates;
a one-minute process timer checks freshness and failures retry with backoff.
Only the active profile syncs. No background worker guarantees delivery after
Android kills/force-stops the app; server timestamps expose this staleness.

Plugin 0.7.0 advertises capability support only when the native agent-construction
contract is present. On the first real API-server run of a new logical chat, it
freezes a bounded snapshot: at most 12 notifications and 20 events per device,
under 12,000 prompt characters overall. It supplies this same ephemeral prompt
tail on follow-up turns, preserving native instructions and conversation history.
Retries and compression continuations use the original snapshot; existing chats
are not retroactively seeded. The read-only `bellpull_phone_context` tool retrieves
fresh cached data when needed. Incoming uploads never create chats or model runs.
Automatic injection currently applies to native API-server sessions, not CLI,
Telegram or cron sessions.

Policy changes, exclusions, clearing and revocation remove that device's frozen
excerpts as well as replacing its current cache. Data stops being served 24 hours
after receipt without a refresh; expired payloads are scrubbed on the next cache
access, with revision/session tombstones retained. Storage is profile-scoped SQLite
with restrictive file permissions, not end-to-end encrypted server storage.
Revocation while disconnected takes effect remotely when the update arrives or
the cache expires. Already-sent model requests, responses and backups cannot be
recalled by deleting this cache.

Local chat uses its own independent opt-ins, reads fresh phone data for each send,
and fits at most three notifications/five events into a 2,400-character excerpt.
Its exact-token prompt budget discards this optional context before dropping the
actual question. Source text is always untrusted reference material; it cannot
authorize replies, dismiss notifications or change calendar events. Notification
reply/dismiss actions and new automatic outgoing-alert jobs are not implemented.
The existing assistant's direct commands remain the action path.

With the new folder capability, Conversations and Unfiled exclude native `cron`
and future `bellpull_notification` source chats. **Notifications** shows those
sources; **All including notifications** is an explicit inclusive view. Normal
conversation unread indicators use the filtered list. Direct navigation and real
project memberships remain intact; this is a virtual folder, not a server history
move. An explicit source filter still fetches background sources for their folder.
Partial background-fetch failures appear in that folder without marking ordinary
chat offline. Existing native routines retain their local delivery behavior.

## Validation

The Android suite passes 758 checks with one existing opt-in skip. Screenshot
verification, lint and release assembly pass. Plugin suite: 187 passes and one
existing skip on the compatibility host. Twelve targeted context checks pass on
stock Hermes 0.21.5 and 0.21.1, including real HTTP authentication/profile isolation and native
run admission with synthetic inference. Coverage includes bounded context, stable
retries, revocation, expiry, no upload-triggered runs, capability gating, calendar
selection, local/online consent separation and notification-folder unread scope.

Physical assistant fixtures passed at unfolded and simulated narrow widths:
reentry/recreation preserves words, dismissed panels stay closed, and a new local
draft does not overwrite the previous chat. Screenshots are in
[evidence/phase15-assistant](evidence/phase15-assistant/).

A separate physical-phone fixture passed at both widths with the actual Android
notification listener, encrypted repository, CalendarContract provider, app sync
and stock Hermes API/plugin. Synthetic sources reached the captured native model
prompt; sync created zero sessions/runs, and revocation emptied the remote cache.
The device check uses a deterministic fake agent, not paid/live model inference.
The fixture process, packages, calendar, notification and ADB reverse mapping were
removed afterward; the original display size/density and default assistant remain.

Manual acceptance after selecting Bellpull: invoke the actual configured gesture
and power/home button on the target phone; try speech with the chosen recognizer;
check real Clock behavior, navigation and messaging handoffs with the installed
apps. No new default assistant, personal APK installation, or server deployment is
performed by the fixture tests.

Platform contracts: [Android roles](https://developer.android.com/reference/android/app/role/RoleManager),
[assistant activity action](https://developer.android.com/reference/android/content/Intent#ACTION_ASSIST),
and [common phone intents](https://developer.android.com/guide/components/intents-common).

## Installation and release

Build43 was installed in place on October 28, preserving UID10308 and the existing
data directory. Its installed APK SHA-256 matches the prepared signed release:
`5d20e2a2ba601dc983982e15ff79edee28af742c41469f27c2207ee293c731d5`.
The app opens; Android's default assistant remains unchanged.
[Validation record](evidence/phase15-assistant/validation.json).

[Experimental app and plugin](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.15.0-experimental.1)
are packaged together. Phone-context support requires installing plugin 0.7.0 on
the Hermes host and refreshing capabilities; this release task does not deploy
the plugin to live servers. Its new sharing controls stay opt-in.
