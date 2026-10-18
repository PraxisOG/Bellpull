# Phase 11 — Today dashboard

Android **0.11.0-experimental / build 29**, based on car experimental source
`cc7e523`. The existing plugin remains **0.4.3-integration-preview**. This phase
adds no server routes, dependencies, permissions or model calls.

## Behavior

Choose **Today → Overview** for today's selected calendar events, due and overdue
reminders, this phone's pending calendar proposals, tracked runs needing approval
or review, ongoing work and unread replies. Chats remains the normal landing tab.
Inbox, Reminders, Routines and Calendar remain available. Reminder notifications
open Inbox; the selected subtab survives navigation.

The overview projects existing profile-specific calendar/planning snapshots,
durable reminder operations, run records, delivery entries and read receipts.
There is no new database. Historical inbox attention notices are not current
decisions. Imported reply previews are not unread unless conversation receipts say
so. A preview does not mark a conversation read; opening it uses normal chat and
visible-message read tracking. Opening the already-selected chat also exits Today.

Events overlap the local day, including overnight events. All-day events follow
CalendarContract's UTC date convention; daylight-saving days can be 23 or 25 hours.
Unselected calendars and events hidden by revoked read access are excluded. Phone
calendar reads happen before waiting for an unavailable server.

Complete and Snooze 1h use the existing durable handler. Snooze is measured from
tap time. Pending or rejected operations cannot be repeated from the overview;
Reminders exposes synchronization conflicts and review controls. Event details
and proposal review reuse the existing dialogs. Opening a card never approves a
run or applies a proposal.

Local records render immediately. While Today is visible and foreground, existing
planning and conversation readers refresh asynchronously every 30 seconds. Leaving
or backgrounding the view stops this loop. Phase 10's delivery service retains its
own behavior; this phase adds no always-running background service. No inference
is admitted to render or refresh Today.

A lazy list initially shows three events, reminders, running jobs and unread
replies per group, with expansion controls. Pending decisions remain in the work
section. Unavailable sources, saved records and current empty lists have distinct
messages. Activity is explicitly work tracked on this phone, not every server job.

## Acceptance

Final local checks: **655 tests passed**, one opt-in live-server test skipped
(app 585, core-model 27, core-hermes 44 including the skip). All **178 screenshot
baselines** verified. Release assembly and lint passed; lint reports no errors,
69 warnings and five hints. The combined navigation fixture now waits for the
entire send acknowledgement before typing a new draft, rather than racing the
earlier run-ID publication.

The Pixel 10 Pro Fold passed **eight final device-test executions**: startup and
Today acceptance at its unfolded size and a simulated 1080×2400 narrow size, with
the existing 440-dpi override retained. The original display size was restored.
These are fixture results, not personal-server or physical hinge/car acceptance.

Tests cover date overlap/DST, calendar permission and selection, optimistic
reminder actions, current decisions versus old notices, unread receipts, source
links, repeated-action prevention, missing versus empty sources, and same-chat
navigation with draft preservation. An interceptor rejects mutations during a
real Today workspace refresh. Screenshots cover narrow and unfolded layouts.

`TodayDeviceTest` is restricted to `org.praxisog.hermes.fixture`. It checks real
touch, Activity recreation, source callbacks and offline complete/snooze actions
by reopening the encrypted cache. It never contacts the owner's server. Existing
delivery tests cover idempotent reconnect behavior separately.

```sh
./android/gradlew -p android test verifyRoborazziDebug lintDebug assembleRelease --max-workers=4
./android/gradlew -p android :app:assembleDebug :app:assembleDebugAndroidTest \
  -PbellpullDeviceFixture=true -Pandroid.injected.build.abi=arm64-v8a --max-workers=4
```

Instrumentation uses only the disposable fixture APKs. The personal APK uses the
saved release signing lineage and build 29 to update the installed car build 28.

## Limits

This phase does not repair the car branch's separate MOSS full-prefix speech
decoder, establish real-car/Bluetooth acceptance, verify cloud calendar sync or
complete real Doze/reboot delivery acceptance. Optional generated briefings are
deferred. Calendar access, selection and a configured server profile are still
required for the corresponding sources.
