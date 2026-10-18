# Phase 12: phone notification context

Early experimental implementation in **0.12.0-experimental / build 30**, on
`feature/phase12-notification-context-0120`, based on Phase 11 and the car
experimental branch. Plugin source remains **0.4.3-integration-preview**. No new
server endpoint, migration, on-device model, dependency, or server deployment is
required. The app module now applies the serialization compiler plugin already
used by core-model/core-hermes.

## Using it

Open **Settings → Phone notification context**. Enable capture and grant Android
notification access. These are separate from Bellpull's outgoing reply alerts.
Capture defaults to all Android-exposed sources, except Bellpull itself and apps
you exclude. Nothing is enabled by installation.

Local history includes search, last-observed active/removed state, ongoing alerts,
group summaries, first observation, update/removal times, and the last three
text revisions. Repeated identical updates do not create extra records. A removed
key posted again gets a new occurrence. Excluding an app also deletes its local
history; packages can be entered before they produce a notification.

Retention defaults to seven days, with one- and thirty-day options and a hard
limit of 1,000 records. Source text is bounded to 4,000 characters per record.
Clear history removes records/revisions and persists a cutoff so reconnecting
cannot immediately restore the same old notifications. New source updates can
be recorded again. Revoking Android access or disabling capture stops capture
and disables all server-sharing grants; saved records remain available locally
until expiry or clearing. Granting access again does not silently restore sharing.

Enable **Share relevant excerpts** separately for the selected server. The
confirmation names the server and explains that excerpts reach its configured
model provider and become part of chat history. Ask “Summarize my notifications”
or mention a sender, app, or topic. A local lexical selector adds at most six
matching excerpts, bounded to 6,000 characters total. Group summaries with
available children and duplicate content are suppressed. Ongoing alerts remain
in local history and enter context only with a separate switch. Unrelated
messages get no excerpts. No model request or upload occurs simply by browsing
history or opening Settings.

The composer shows a hint when context is selected. Chat displays the submitted
context in an expandable section while keeping the user's words readable. Voice
uses the same send boundary; its preset is excluded from relevance matching.
The selected context is frozen into the existing durable submission, so retries
preserve the same input and idempotency key. Disabling sharing affects new
submissions; already submitted/queued messages retain their original context.
Clearing local history cannot retract copies in chat, pending submissions,
conversation exports, or server/model-provider records.

## Implementation boundaries

`PhoneContextListenerService` is the system-bound Android notification listener,
separate from the pre-existing Hermes reply connection service. It reads exposed
text, messaging-style senders, time, grouping, ongoing flags and removal reasons.
It retains no PendingIntent/RemoteInput actions and does not execute notification
actions. Android 10 uses the exposed text/lines fallback for messaging content.

`PhoneNotificationRepository` serializes changes on IO, reconciles active keys on
reconnect, and rechecks Android access at the send boundary. On reconnect, missing
records are marked “no longer present after reconnect,” not invented user
dismissals. Its event queue is bounded; overflow/disconnection is surfaced as a
possible listening gap. It does not create a second foreground service or poll
Hermes for notification capture.

`PhoneNotificationStorage` incrementally writes encrypted SQLite payloads using
an Android Keystore AES-256-GCM key and fresh per-row nonces. Notification keys,
app/source names and contents are encrypted. SQLite stores only random record
IDs and activity timestamps in plaintext; the small policy uses the existing
EncryptedSharedPreferences approach. Storage failures pause capture/sharing with
an error, with no plaintext fallback. Backup remains disabled. Expired records
are filtered at retrieval/UI boundaries and physically pruned during use and a
15-minute in-process maintenance loop; a stopped app cleans up when next run.

`HermesStore` attaches a clearly labelled, JSON-escaped, untrusted-data envelope
through the existing native chat/run APIs. It explicitly describes a partial
selection, missing/redacted content, stale listener status and the absence of
authority to reply or act. No automatic reply or device-action path was added.
These instructions reduce ambiguity; notification text remains untrusted input
to a general-purpose model and is not a security boundary for server tools.

Android exposes only the notifications its listener API supplies. The app cannot
recover previously dismissed notifications it never observed or unredacted text
the OS withholds. See [Android's listener lifecycle](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
and [sensitive notification restrictions](https://developer.android.com/about/versions/15/behavior-changes-all#otp-redaction).

## Acceptance

Evidence is in `/home/praxisog/Bellpull-builds/0.12.0-experimental/`.

- Android: 676 passed, one opt-in live-server JVM test skipped; 182 screenshot
  baselines verified. Release assembly and lint passed (zero errors).
- Plugin source 0.4.3: 153 passed, one skipped on the compatibility test host;
  71 passed, one skipped in the applicable stock-Hermes suites using the exact
  unmodified image running on this computer (`2fd023efbb8d`).
- Pixel 10 Pro Fold / GrapheneOS: four tests at physical 2076×2152 and four at
  simulated 1080×2400, covering real Android listener connection, synthetic
  post/update/removal, duplicate coalescing, encrypted reopen, activity recreation,
  clear history, sharing isolation and revocation. Original display size restored;
  existing density override retained.
- Live phone-to-Hermes test: real Android notification → encrypted history →
  app selection → native durable run → saved answer → app display. The local
  Hermes 0.21.5 instance with its installed plugin 0.4.2 returned **Cobalt Orchard**
  from the synthetic notification. No tools ran. The synthetic conversation was
  deleted; live server/plugin configuration was not changed. Thus the new client
  also works with the currently installed older plugin.
- A 200-notification encrypted insert burst completed in 3,892 ms unfolded and
  5,662 ms at cover size. These measure background storage throughput, not a
  blanket UI-frame or car-audio performance guarantee.

Phone instrumentation uses only the disposable `.fixture` package. Its listener
rejects all sources except `com.android.shell` before reading text, so tests with
notification access do not save the user's personal notifications. The service
handle used to cancel synthetic test posts is exposed only in that fixture app;
production UI/code has no notification-action caller. Live credentials were
supplied through a private temporary fixture file and removed immediately after
reading. The ADB reverse mapping and fixture apps are removed after acceptance.

The first real-device attempt caught fixture shell-argument quoting and prompted
an explicit null guard for absent messaging extras. Final runs include those fixes.

## Early-version limits and follow-up work

Relevance is lexical, with no embeddings, semantic/date-range retrieval, or remote
notification search tool. Long content and earlier revisions are bounded; a model
sees only the selected current excerpts. The whole-history viewer is a local UI.

Long-duration idle, reboot, OEM throttling, very large bursts, localization and
unusual custom notification formats still need broader acceptance. Access changes
are checked on listener/app lifecycle and directly before sharing. History can
have gaps when the process is force-stopped or Android withholds delivery.
The bounded queue reports loss rather than claiming full capture.

The existing MOSS full-prefix streaming decoder and its known audio issue are
unchanged. This phase adds notification context; it does not establish car audio,
Doze reminder delivery, or general agent prompt-injection immunity.
