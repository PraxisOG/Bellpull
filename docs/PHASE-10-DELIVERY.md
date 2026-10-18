# Phase 10 delivery framework

Experimental Android foundation on `2026-10-17-car-experimental`. Device acceptance
is pending. It reuses the existing planning plugin and authenticated history APIs;
no model request, new server port, dependency or phone notification-reading access.

## Included

- Today → Inbox retains the newest 200 received replies, reminder occurrences,
  attention notices and changed routine result summaries in the encrypted per-profile
  cache. Reply previews are capped at 24,000 characters with visible truncation.
  Routine summaries use status/error metadata; full routine output is not available
  from the existing planning snapshot contract.
- Received reminders use one AlarmManager alarm per active profile. Local receipts
  prevent repeat alerts for the same reminder ID/due time. Title-only edits do not
  create a new occurrence. Overdue reminders produce one catch-up notification.
- Snooze, complete and dismiss persist a stable operation ID and exact server revision
  before changing local behavior. Network retries reuse the same operation. A confirmed
  operation remains locally applied until the next canonical snapshot. Rejected actions
  require review/discard, never automatic rebasing. One outstanding action per reminder;
  at most 100 queued actions. Create/edit/reopen/delete still require a server response.
- Reading or dismissing an inbox item is separate from reminder state and approval.
  Attention cards link back to the current conversation; they do not approve anything.
- Background catch-up uses the existing user-enabled notification service's one-minute
  loop. Foreground resume, connectivity return and manual refresh also request a sync.
  Server wake events trigger a rate-limited check. Concurrent checks coalesce; failures
  back off to at most 16 minutes for timer-driven checks. No fixed background timing
  guarantee when Android suspends/stops the service.
- Reply history uses existing per-session cursors. Planning currently fetches the
  bounded v1 snapshot; there is no server-side "changed since" planning endpoint yet.
  Bodies are archived before advancing history cursors. Completed notification delay
  jobs are removed, and catch-up scans cannot accumulate a queue of duplicate jobs.
- Boot, package update, time change and precise-alarm permission grant restore local
  reminders without a network call. Encrypted storage becomes available after unlock.
  Force-stopping the app requires reopening it. Only the active connection schedules
  alerts; switching/sign-out cancels its local alarm, retaining isolated saved data.
- Notification and precise-alarm capability are shown in Today. Without precise-alarm
  access the app uses an inexact alarm and explicitly reports possible delay. The
  existing notification opt-in still applies. Denied/off alerts remain in the inbox.

## Phone acceptance

1. Install with the existing signing lineage (`docs/release.md`). CI debug APKs are
   test artifacts, not an assumed in-place update for the release installation.
2. Enable notifications and optionally Alarms & reminders. Refresh Today. Plan a
   reminder a few minutes ahead and confirm it appears on the phone before going offline.
3. Disconnect the network and lock the phone. Verify one reminder notification and a
   readable inbox item. Dismiss the notification; the reminder must remain unfinished.
4. Offline, snooze one reminder and complete/dismiss separate reminders. Restart the
   app offline. Check the local states and pending actions survive. Reconnect; each
   action should synchronize once. Change a reminder on the server before reconnect
   to verify the conflict stays visible rather than overwriting newer server state.
5. Reboot/unlock before a future reminder. Check scheduling returns. With several
   overdue reminders, expect one catch-up alert rather than a burst.
6. Receive a reply, then disconnect. Read its saved body in Inbox. A reply being read
   in the current chat or handled by active voice should not also buzz.
7. Switch profiles, disable notifications, revoke alarm access and change timezone.
   Check isolation, truthful status and absence of stale notifications. Absolute due
   times remain absolute across timezone changes.

Core tests cover receipts, bounded retention, snooze identity, conflict overlays and
idempotent reconnects. Android fixture tests cover OS alarm replacement and recovery
using injected preferences (not an AndroidKeyStore encryption acceptance test), plus
cover/unfolded inbox actions and screenshots. Real Doze, reboot and release-signing
behavior require a device.
