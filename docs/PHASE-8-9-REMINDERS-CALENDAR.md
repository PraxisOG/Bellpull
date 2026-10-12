# Phase 8/9 planning preview

Independent branch `feature/phase8-9-reminders-calendar`, from corrected 0.5.5.
APK `0.8.0-planning-preview`/19; this branch's plugin is `0.4.0-phase8-9`.
The independent OpenCode branch has different features; neither preview is installed,
pushed, merged or published. No personal Hermes server was accessed.

## Implemented

Today has Reminders, Routines and Calendar views. Rendering cached information and
simple UI operations do not require an LLM. Switching views preserves existing chats,
drafts and runs; Today is not forced on startup.

- Reminders: create/edit, due/overdue, snooze one hour, complete, dismiss, reopen and
  delete. Plugin SQLite is authoritative; encrypted phone cache is read-only offline.
  Operation IDs and exact revisions protect retries and concurrent edits. Due is
  separate from completion. **Background/offline alerts are not implemented**; that
  delivery work is Phase 10. Do not rely on this preview as an alarm.
- Hermes routines: list real native cron jobs; create/edit ordinary prompt jobs,
  pause/resume/cancel, next/last run, status/error and delivery destination. New jobs
  deliver to Hermes local results. Existing destinations stay unchanged. Native cron
  remains the only scheduler; no scripts are generated or inference run by UI actions.
  Advanced scripted/skill/monitor edits and complete result bodies stay in Hermes.
  An interrupted mutation with an unknown outcome is visible and never automatically
  repeats native job creation; inspect the refreshed list before another action.
  Hosts lacking the verified per-profile `use_cron_store` context show routines
  unavailable instead of accessing another profile’s scheduler.
- Phone calendar: Android CalendarContract with selected calendars per connection,
  independent read permission, sharing opt-in, busy-only option, independent write
  permission/toggle and chosen destination. All are off/unselected initially. Source
  keys bind calendar ID and account identity. No Google library, CalDAV client or new
  dependency. Existing account/sync adapter owns cloud synchronization.
- Visual day/week calendar blocks, overlapping event columns, all-day lanes and
  unfinished reminders. Day boundaries follow the phone timezone/DST; all-day dates
  retain their UTC-authored date. Recurring occurrences come from Instances, including
  provider exceptions. Week scrolls horizontally on narrow screens.
- Ordinary event create/edit/delete and optional creation-time calendar alerts;
  daily/weekly recurrence creation capped at 100 occurrences (UI default 10). Complex
  or existing recurring occurrence/series edits open the calendar app. No invitations,
  attendees, account management or series-edit engine.
- Refresh on Today/foreground entry, selected day, explicit Refresh and local provider
  changes. Requests coalesce; cached phone-read time remains visible. The provider read
  is bounded to 22 days/500 events. It does not trigger or prove account/cloud sync.
- Optional shared calendar cache and `bellpull_planning` model tool. Reads return at
  most 20 unfinished reminders and 200 events, with freshness/truncation. There is no
  automatic prompt-context injection. Sharing revocation clears the dedicated server
  cache and pending proposals; offline revocation persists until acknowledged. Existing
  chat history is separate. Busy-only mode does not transmit titles/fingerprints.
- Hermes may leave structured timed create/edit/delete proposals. A phone **Apply**
  action revalidates selected calendar, write permission and current event fingerprint.
  Provider assert+mutation batches reject stale edits. Creates carry a stable provider
  marker; receipts retry result reporting without repeating writes. If a pending create
  has no recoverable marker, it stays uncertain rather than inserting another event.
  No automatic inbound phone bridge or unattended calendar writes are implemented.

## Verification and practical limits

Local JVM/controller/provider and Compose screenshot tests exercise this code without
phone access. The CalendarContract fixture is an in-memory provider, **not** Android's
real provider or a Nextcloud sync adapter. Real permission UI, GrapheneOS provider
behavior, marker retention, calendar app handoff, account sync and background behavior
require phone acceptance. The signed personal app remains corrected 0.5.5.

Python tests use disposable unmodified Hermes commit
`d4d2ab7b5d104166e7e22a02cafd12f1fe456a31`, real cron storage APIs, native API bearer
authentication and plugin routes. They cover reminder persistence/retry/conflict,
native routine CRUD and interrupted admission, bounded sharing, stale references,
revocation and separate profile storage. No scheduler/inference execution or personal
provider result is claimed. Final counts/logs are in HANDOFF.

Known follow-ons: background/offline reminder delivery and notification ownership
(Phase 10); automatic phone operation transport with separate revocable capability
token and visible audit log; automatic bounded reminder context after a stable prompt
prefix; richer routine output/delivery controls; natural date entry and richer event
editing. The prototype uses explicit ISO times with UTC offsets to avoid ambiguous
dates. Do not read the branch name as completion of every Phase 8/9 requirement.

## Stock non-Docker installation and manual acceptance

1. Back up each Hermes profile home and the existing plugin. Copy **this branch's**
   `plugins/bellpull/` into the host's configured plugin directory, enable Bellpull and
   restart the API gateway. No core-file replacement, Docker or access by this agent.
   Refresh authenticated discovery; verify `bellpull_planning` and, separately,
   `bellpull_routines`. An unserved native profile must reject without root fallback.
2. Install the preview APK when ready, preserving the established signer/app data.
   Create a reminder, edit/snooze/complete/reopen it, restart app/gateway, and verify
   state. Disconnect and confirm cached content is visible without claiming fresh data.
   Due reminders must not silently become completed. No background alert is promised.
3. Create a harmless local-delivery native routine, pause/resume/edit/cancel it.
   Inspect Hermes' cron list and confirm exactly one native job. Test a dropped response;
   uncertain outcomes must not automatically create another job. Then deliberately
   allow one scheduled run and inspect the real next/last/result/delivery status.
4. Use a test calendar/account. Grant read only, select it and inspect day/week,
   overlapping blocks, all-day/multi-day dates, DST, recurring instances/exceptions.
   Switch dates, background/return and edit elsewhere; confirm refresh and freshness.
5. Enable writes separately and choose the test destination. Create/edit/delete one
   timed event and an all-day event; create a finite daily/weekly series with an alert.
   Existing series/occurrence edits must hand off. Verify phone rows first and account
   sync separately. Revoke write permission and ensure no write occurs.
6. Enable busy-only sharing, ask Hermes to read its bounded cache and verify titles
   are absent. Switch to detailed sharing and explicitly propose a test event. Apply
   once on the phone, drop the result-report connection and retry; no second event.
   Modify an event elsewhere before applying a proposal; stale references must fail.
7. Disable sharing while disconnected, reconnect and verify dedicated server data/
   proposals are removed. Test another saved connection/native profile for isolation.
8. Roll back by restoring the previous APK/plugin and restarting. New metadata under
   `bellpull-planning/` is additive and can remain; no native session schema changes.
