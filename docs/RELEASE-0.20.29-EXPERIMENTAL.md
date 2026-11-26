# Bellpull 0.20.29 experimental · Reminder snooze timing

**Snooze 1h** in Today → Reminders now measures an hour from the moment you tap. Previously, leaving the list open could shorten the snooze. If Hermes is offline, the phone saves the deadline and sends that same time when it reconnects.

Android also removes a posted reminder when you snooze, finish, or reschedule it, even if another reminder is overdue. When part of a posted group is still due, Android updates the existing notification. Dismissing the notification leaves the reminders in your Inbox.

This release includes signed Android build 79 and server plugin 0.9.27-mobile-preview. [Calendar and reminder reliability](https://github.com/PraxisOG/Bellpull/issues/28)
